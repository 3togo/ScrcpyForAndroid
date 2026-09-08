package io.github.3togo.scrcaster.desktop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** One short-lived QR session, with its own cancellable native commands. */
final class QrPairing implements AutoCloseable {
    interface Discovery extends AutoCloseable {
        void start() throws IOException;
        List<Service> services();
        @Override void close();
    }
    record Service(String name, String type, String address) {
        Service {
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\""))
                name = name.substring(1, name.length() - 1);
        }
        String host() { return address.substring(0, address.lastIndexOf(':')); }
    }
    private final Backend backend;
    private final Duration discoveryTimeout;
    private final Duration connectionTimeout;
    private final String name = "studio-" + randomHex();
    private final char[] secret = randomHex().toCharArray();
    private volatile boolean closed;
    private final Discovery directDiscovery;
    private boolean directStarted;

    QrPairing() { this(new Backend(), Duration.ofMinutes(2), Duration.ofSeconds(12), new MdnsDiscovery()); }
    QrPairing(Backend backend, Duration discoveryTimeout, Duration connectionTimeout) {
        this(backend, discoveryTimeout, connectionTimeout, null);
    }
    QrPairing(Backend backend, Duration discoveryTimeout, Duration connectionTimeout, Discovery directDiscovery) {
        this.backend = backend;
        this.directDiscovery = directDiscovery;
        this.discoveryTimeout = discoveryTimeout;
        this.connectionTimeout = connectionTimeout;
    }
    private static String randomHex() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    String name() { return name; }
    synchronized String payload() {
        ensureActive();
        return "WIFI:T:ADB;S:" + name + ";P:" + new String(secret) + ";;";
    }
    BufferedImage image() throws Exception {
        var matrix = new QRCodeWriter().encode(payload(), BarcodeFormat.QR_CODE, 320, 320);
        BufferedImage image = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++)
            image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xffffff);
        return image;
    }
    static List<Service> services(String output) {
        List<Service> services = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3) continue;
            String type = parts[1].replaceFirst("\\.$", "");
            if (!type.equals("_adb-tls-pairing._tcp") && !type.equals("_adb-tls-connect._tcp")) continue;
            String name = parts[0].replaceFirst("\\.$", "");
            if (name.endsWith("." + type)) name = name.substring(0, name.length() - type.length() - 1);
            try { services.add(new Service(name, type, Backend.endpoint(parts[2]))); }
            catch (IllegalArgumentException ignored) { }
        }
        return services;
    }
    private List<Service> discover() throws Exception {
        ensureActive();
        if (directStarted) return directDiscovery.services();
        List<Service> services;
        try { services = services(backend.adb(Duration.ofSeconds(5), "mdns", "services")); }
        catch (IOException error) {
            if (directDiscovery == null) throw error;
            services = List.of();
        }
        if (directDiscovery != null && services.stream().noneMatch(s -> s.name.equals(name))) {
            directDiscovery.start();
            directStarted = true;
            return directDiscovery.services();
        }
        return services;
    }
    /** Returns a connection address, or null when pairing succeeds without discovery. */
    String awaitPairing(Consumer<String> status) throws Exception {
        status.accept("Waiting for scan… Keep the phone and computer on the same network.");
        Service pairing = null;
        long deadline = System.nanoTime() + discoveryTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            pairing = discover().stream().filter(s -> s.type.equals("_adb-tls-pairing._tcp") && s.name.equals(name)).findFirst().orElse(null);
            if (pairing != null) break;
            Thread.sleep(1000);
        }
        ensureActive();
        if (pairing == null) throw new IOException("No phone discovered. Keep both devices on the same network and allow multicast UDP 5353, then generate a new QR code.");
        status.accept("Phone found. Pairing…");
        char[] password;
        synchronized (this) { ensureActive(); password = secret.clone(); }
        backend.pairQr(pairing.address, password);
        ensureActive();
        status.accept("Paired. Finding the connection port…");
        deadline = System.nanoTime() + connectionTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            String host = pairing.host();
            List<Service> connections;
            try {
                connections = discover().stream().filter(s -> s.type.equals("_adb-tls-connect._tcp") && s.host().equals(host)).distinct().toList();
            } catch (IOException e) { ensureActive(); return null; }
            // Do not guess if a host advertises multiple Android endpoints.
            if (connections.size() == 1) {
                ensureActive();
                String endpoint = connections.get(0).address;
                String output;
                try { output = backend.adb(Duration.ofSeconds(10), "connect", endpoint); }
                catch (IOException e) { ensureActive(); return null; }
                ensureActive();
                if (output.contains("connected to " + endpoint)) return endpoint;
                return null;
            }
            Thread.sleep(1000);
        }
        ensureActive();
        return null;
    }
    private void ensureActive() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(secret, '\0');
        if (directDiscovery != null) directDiscovery.close();
        backend.close();
    }
}
