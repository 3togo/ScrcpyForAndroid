package io.github.togo3.scrcaster.desktop;

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
    private final String name = "studio-" + randomValue();
    private final char[] secret = randomValue().toCharArray();
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
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static String randomValue() {
        // Match Android's RANDOM-10 ADB QR fields. UUID-sized fields make the QR substantially
        // denser and caused the reported TV-to-Android-15 failure after the TV UI reduced its
        // display size; this shorter desktop payload paired with that phone in the hardware proxy
        // test. Keep both generators identical so future computer-to-phone tests remain useful.
        StringBuilder value = new StringBuilder(10);
        for (int i = 0; i < 10; i++) value.append(RANDOM_ALPHABET.charAt(RANDOM.nextInt(RANDOM_ALPHABET.length())));
        return value.toString();
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
        if (pairing == null) throw new IOException(
            "No phone discovered after the QR scan. Confirm the phone and computer are on the " +
            "same Wi-Fi subnet (not a guest network), Wireless debugging is still enabled, and " +
            "VPN, hotspot/client isolation, or a firewall is not blocking multicast UDP 5353. " +
            "Then generate a new QR code and scan it again."
        );
        status.accept("Phone found. Pairing…");
        char[] password;
        synchronized (this) { ensureActive(); password = secret.clone(); }
        backend.pairQr(pairing.address, password);
        ensureActive();
        status.accept("Paired. Finding the connection port…");
        deadline = System.nanoTime() + connectionTimeout.toNanos();
        Set<String> attempted = new HashSet<>();
        while (System.nanoTime() < deadline) {
            String host = pairing.host();
            List<Service> connections;
            try {
                connections = discover().stream().filter(s -> s.type.equals("_adb-tls-connect._tcp") && s.host().equals(host)).distinct().toList();
            } catch (IOException e) { ensureActive(); return null; }
            // Android/mDNS may retain a stale service when adbd changes its random TLS port. A
            // real phone advertised one open and one closed endpoint simultaneously; requiring
            // exactly one made QR pairing appear to fail even though manual address entry worked.
            // Trying each unique advertisement is safe: ADB still performs TLS authentication,
            // and only an endpoint authorized by the pairing completed above can succeed.
            for (Service connection : connections) {
                ensureActive();
                String endpoint = connection.address;
                if (!attempted.add(endpoint)) continue;
                String output;
                try { output = backend.adb(Duration.ofSeconds(5), "connect", endpoint); }
                catch (IOException e) { ensureActive(); continue; }
                ensureActive();
                if (output.contains("connected to " + endpoint)) return endpoint;
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
