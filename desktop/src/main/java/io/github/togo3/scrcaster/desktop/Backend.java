package io.github.togo3.scrcaster.desktop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.*;
import io.github.togo3.scrcaster.core.AspectRatio;

/** Native Linux transport and rendering; never invokes a local shell. */
final class Backend implements AutoCloseable {
    record Device(String serial, String state, String model) {
        boolean ready() { return state.equals("device"); }
        @Override public String toString() { return model + "  ·  " + serial + "  (" + state + ")"; }
    }
    /** How the video is fitted to a fullscreen window. */
    enum Fill {
        FIT("Fit (black bars)"),
        STRETCH("Stretch"),
        CROP_SHORT_EDGE("Crop to fill short edge"),
        CROP_LONG_EDGE("Crop to fill long edge");
        private final String label;
        Fill(String label) { this.label = label; }
        boolean cropsToFill() { return this == CROP_SHORT_EDGE || this == CROP_LONG_EDGE; }
        @Override public String toString() { return label; }
    }
    // Aspect-ratio / crop math (incl. the Ratio enum) is shared with the Android app
    // via the :core module (io.github.togo3.scrcaster.core.AspectRatio).
    record Options(int size, int fps, int bitrate, boolean audio, boolean control,
                   boolean fullscreen, Fill fill, String crop, boolean stretchCroppedFrame,
                   String recording) {
        Options {
            if (size < 0 || size > 16384 || fps < 1 || fps > 240 || bitrate < 1 || bitrate > 200)
                throw new IllegalArgumentException("Invalid stream settings");
        }
    }
    private final String adb;
    private final String scrcpy;
    private final Set<Process> children = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private boolean closed;

    Backend() { this(System.getenv().getOrDefault("ADB", "adb"), System.getenv().getOrDefault("SCRCPY", "scrcpy")); }
    Backend(String adb, String scrcpy) { this.adb = adb; this.scrcpy = scrcpy; }

    static String endpoint(String value) {
        String s = value.trim();
        if (!s.matches("(?:[a-zA-Z0-9][a-zA-Z0-9.-]*|\\[[a-fA-F0-9:%._-]+]):[0-9]{1,5}"))
            throw new IllegalArgumentException("Enter host:port, or [IPv6]:port.");
        int port = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        return s;
    }
    static List<Device> parseDevices(String output) {
        List<Device> result = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || line.startsWith("List of") || line.startsWith("*")) continue;
            if (!Set.of("device", "offline", "unauthorized", "recovery", "sideload", "no").contains(fields[1])) continue;
            String model = fields[0];
            for (String field : fields) if (field.startsWith("model:")) model = field.substring(6).replace('_', ' ');
            result.add(new Device(fields[0], fields[1].equals("no") ? "no permissions" : fields[1], model));
        }
        return result;
    }
    /** Natural (unrotated) device size from `adb shell wm size`, e.g. "Physical size: 1080x2400". */
    static int[] naturalSize(String wmSize) {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(wmSize);
        if (!m.find()) throw new IllegalArgumentException("Cannot read the device screen size");
        return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
    }
    /** True when the internal display is currently rotated to landscape. */
    static boolean landscape(String dumpsysDisplay) {
        Matcher m = Pattern.compile("DisplayViewport\\{type=INTERNAL[^}]*?deviceWidth=(\\d+), deviceHeight=(\\d+)")
            .matcher(dumpsysDisplay);
        if (!m.find()) throw new IllegalArgumentException("Cannot read the device orientation");
        return Integer.parseInt(m.group(1)) > Integer.parseInt(m.group(2));
    }
    // The four methods below delegate to the shared :core module so the desktop and
    // Android frontends compute identical crops from the same source of truth.
    static String fillCrop(int naturalW, int naturalH, boolean landscape, int windowW, int windowH) {
        return AspectRatio.fillCrop(naturalW, naturalH, landscape, windowW, windowH);
    }
    static String coverCrop(int naturalW, int naturalH, boolean landscape, int windowW, int windowH) {
        return AspectRatio.coverCrop(naturalW, naturalH, landscape, windowW, windowH);
    }
    static double targetRatio(AspectRatio.Ratio mode, boolean landscape, String custom) {
        return AspectRatio.targetRatio(mode, landscape, custom);
    }
    static double parseRatio(String text) { return AspectRatio.parseRatio(text); }
    static String cropForRatio(int naturalW, int naturalH, boolean landscape, double target) {
        return AspectRatio.cropForRatio(naturalW, naturalH, landscape, target);
    }
    List<String> streamCommand(String serial, Options o) {
        List<String> args = new ArrayList<>(List.of(scrcpy, "--serial=" + serial,
            "--max-size=" + o.size, "--max-fps=" + o.fps, "--video-bit-rate=" + o.bitrate + "M"));
        if (!o.audio) args.add("--no-audio");
        if (!o.control) args.add("--no-control");
        boolean hasCrop = o.crop != null && !o.crop.isBlank();
        if (o.fullscreen) {
            args.add("--fullscreen");
            // A fill crop is deliberately two-stage: crop to the monitor ratio first, then
            // stretch that already-matching frame over the window. The latter cannot distort
            // it, and avoids SDL reintroducing a one-pixel letterbox through rounding.
            if ((o.fill == Fill.STRETCH && !hasCrop)
                    || (o.fill == Fill.CROP_SHORT_EDGE && o.stretchCroppedFrame && hasCrop))
                args.add("--render-fit=stretched");
        }
        if (hasCrop) args.add("--crop=" + o.crop);
        if (!o.recording.isBlank()) args.add("--record=" + o.recording);
        return args;
    }
    synchronized Process start(List<String> args) throws IOException {
        if (closed) throw new IOException("Application is closing");
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().put("ADB", adb);
        Process p = builder.start();
        children.add(p);
        p.onExit().thenRun(() -> children.remove(p));
        return p;
    }
    String adb(String... args) throws Exception {
        return adb(Duration.ofSeconds(60), args);
    }
    String adb(Duration timeout, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(adb));
        command.addAll(List.of(args));
        return run(command, null, timeout);
    }
    String pair(String address, char[] code) throws Exception {
        try {
            if (code.length != 6) throw new IllegalArgumentException("Pairing code must contain six digits.");
            for (char c : code) if (c < '0' || c > '9') throw new IllegalArgumentException("Pairing code must contain six digits.");
            return run(List.of(adb, "pair", endpoint(address)), code, Duration.ofSeconds(60));
        } finally { Arrays.fill(code, '\0'); }
    }
    void pairQr(String address, char[] secret) throws Exception {
        try {
            String result;
            try {
                result = run(List.of(adb, "pair", endpoint(address)), secret, Duration.ofSeconds(30));
            } catch (IOException error) {
                result = error.getMessage();
            }
            // Some Android QR scanners retain Wi-Fi-style quotes around the secret.
            String failure = result.toLowerCase(Locale.ROOT);
            if (failure.contains("wrong password") || failure.contains("protocol fault")) {
                char[] quoted = new char[secret.length + 2];
                quoted[0] = quoted[quoted.length - 1] = '"';
                System.arraycopy(secret, 0, quoted, 1, secret.length);
                try { result = run(List.of(adb, "pair", endpoint(address)), quoted, Duration.ofSeconds(30)); }
                finally { Arrays.fill(quoted, '\0'); }
            }
            if (!result.contains("Successfully paired")) throw new IOException("Pairing did not succeed");
        } catch (IOException error) {
            // Do not expose native command output: a backend could echo its input.
            throw new IOException(
                "The phone was discovered, but ADB rejected QR pairing. Keep Wireless debugging " +
                "enabled, confirm both devices are on the same Wi-Fi subnet, disable VPN or guest " +
                "network isolation, then generate a new QR code and scan it again."
            );
        } finally { Arrays.fill(secret, '\0'); }
    }
    String run(List<String> command, char[] input, Duration timeout) throws Exception {
        Process p = start(command);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> deadline = timer.schedule(() -> {
            timedOut.set(true);
            stop(p);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            try (Writer writer = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                if (input != null) { writer.write(input); writer.write('\n'); }
            }
            StringBuilder output = new StringBuilder();
            drain(p, line -> {
                output.append(line).append('\n');
                if (output.length() > 100_000) output.delete(0, output.length() - 100_000);
            });
            int exit = p.waitFor();
            if (timedOut.get()) throw new IOException("Command timed out");
            if (exit != 0) throw new IOException("Command exited with " + exit + ":\n" + output);
            return output.toString();
        } finally { deadline.cancel(false); stop(p); }
    }
    static void drain(Process p, Consumer<String> output) throws IOException {
        try (BufferedReader reader = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) output.accept(line);
        }
    }
    void stop(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroy(); // SIGTERM lets scrcpy finalize recording containers.
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            if (p.isAlive()) p.destroyForcibly();
        });
    }
    @Override public synchronized void close() {
        closed = true;
        children.forEach(this::stop);
        timer.shutdownNow();
    }
}
