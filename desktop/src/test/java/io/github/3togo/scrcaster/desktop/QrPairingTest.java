package io.github.3togo.scrcaster.desktop;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class QrPairingTest {
    static void run() throws Exception {
        var parsed = QrPairing.services("List of discovered mdns services\nwrong _adb._tcp 10.0.0.1:5555\nstudio-one._adb-tls-pairing._tcp. _adb-tls-pairing._tcp. [::1]:1234\nbad _adb-tls-pairing._tcp a:99999\n");
        assert parsed.size() == 1;
        assert parsed.get(0).name().equals("studio-one");
        assert parsed.get(0).host().equals("[::1]");
        assert new QrPairing.Service("\"studio-quoted\"", "_adb-tls-pairing._tcp", "10.0.0.1:1234").name().equals("studio-quoted");
        Path dir = Files.createTempDirectory("qr-pairing-test");
        Path script = dir.resolve("fake adb");
        try {
            try (QrPairing session = new QrPairing(new Backend(script.toString(), "unused"), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
                String payload = session.payload();
                assert payload.matches("WIFI:T:ADB;S:studio-[a-f0-9]{32};P:[a-f0-9]{32};;");
                var image = session.image();
                int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
                assert new MultiFormatReader().decode(bitmap).getText().equals(payload);
                String secret = payload.split(";P:")[1].replace(";;", "");
                Files.writeString(script, "#!/bin/sh\ncase \"$1\" in\nmdns) cat <<'SERVICES'\n" +
                    "studio-unrelated _adb-tls-pairing._tcp 10.0.0.99:9000\n" +
                    session.name() + " _adb-tls-pairing._tcp 10.0.0.2:3000\n" +
                    "device _adb-tls-connect._tcp 10.0.0.2:4000\nSERVICES\n;;\n" +
                    "pair) [ \"$#\" = 2 ] && [ \"$2\" = 10.0.0.2:3000 ] || exit 8\nread secret\n[ \"$secret\" = '" + secret + "' ] || exit 9\necho 'Successfully paired to 10.0.0.2:3000'\n;;\n" +
                    "connect) [ \"$2\" = 10.0.0.2:4000 ] || exit 10\necho 'connected to 10.0.0.2:4000';;\nesac\n");
                script.toFile().setExecutable(true);
                List<String> statuses = new ArrayList<>();
                assert session.awaitPairing(statuses::add).equals("10.0.0.2:4000");
                assert statuses.size() == 3;
                Files.writeString(script, Files.readString(script).replace("echo 'connected to 10.0.0.2:4000'", "echo 'connection refused'; exit 1"));
                assert session.awaitPairing(s -> {}) == null;
                session.close();
                try { session.payload(); throw new AssertionError("Closed QR remained usable"); }
                catch (java.util.concurrent.CancellationException expected) { }
            }
            // adb may print an error while returning exit code zero.
            Files.writeString(script, "#!/bin/sh\nread secret\necho \"Failed to pair $secret\"\n");
            try (Backend backend = new Backend(script.toString(), "unused")) {
                char[] secret = "private-secret".toCharArray();
                try { backend.pairQr("localhost:1234", secret); throw new AssertionError("False pairing success"); }
                catch (java.io.IOException e) { assert !e.getMessage().contains("private-secret"); }
                assert Arrays.equals(secret, new char[secret.length]);
            }
            Files.writeString(script, "#!/bin/sh\nread secret\nif [ \"$secret\" = '\"private-secret\"' ]; then echo 'Successfully paired'; else echo 'wrong password'; fi\n");
            try (Backend backend = new Backend(script.toString(), "unused")) {
                backend.pairQr("localhost:1234", "private-secret".toCharArray());
            }
            Files.writeString(script, Files.readString(script).replace("wrong password", "protocol fault (unable to read status message): Success"));
            try (Backend backend = new Backend(script.toString(), "unused")) {
                backend.pairQr("localhost:1234", "private-secret".toCharArray());
            }
            // Linux adb can lack its mDNS daemon entirely; direct discovery must still pair.
            Files.writeString(script, "#!/bin/sh\ncase \"$1\" in\nmdns) echo 'ERROR: mdns daemon unavailable'; exit 1;;\npair) read code; echo 'Successfully paired';;\nconnect) echo \"connected to $2\";;\nesac\n");
            List<QrPairing.Service> discovered = new ArrayList<>();
            boolean[] lifecycle = new boolean[2];
            QrPairing.Discovery discovery = new QrPairing.Discovery() {
                public void start() { lifecycle[0] = true; }
                public List<QrPairing.Service> services() { return discovered; }
                public void close() { lifecycle[1] = true; }
            };
            try (QrPairing session = new QrPairing(new Backend(script.toString(), "unused"), Duration.ofSeconds(3), Duration.ofSeconds(3), discovery)) {
                discovered.add(new QrPairing.Service("\"" + session.name() + "\"", "_adb-tls-pairing._tcp", "10.0.0.2:1234"));
                discovered.add(new QrPairing.Service("phone", "_adb-tls-connect._tcp", "10.0.0.2:5678"));
                assert session.awaitPairing(s -> {}).equals("10.0.0.2:5678");
            }
            assert lifecycle[0] && lifecycle[1];
            try (QrPairing session = new QrPairing(new Backend(script.toString(), "unused"), Duration.ZERO, Duration.ZERO)) {
                try { session.awaitPairing(s -> {}); throw new AssertionError("Missing discovery timeout"); }
                catch (java.io.IOException expected) { assert expected.getMessage().contains("No phone discovered"); }
            }
            Files.writeString(script, "#!/bin/sh\nexec sleep 20\n");
            try (QrPairing session = new QrPairing(new Backend(script.toString(), "unused"), Duration.ofSeconds(30), Duration.ZERO)) {
                java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var pending = executor.submit(() -> {
                        try { session.awaitPairing(s -> started.countDown()); throw new AssertionError("Cancelled pairing succeeded"); }
                        catch (Exception expected) { }
                    });
                    assert started.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    session.close();
                    pending.get(3, java.util.concurrent.TimeUnit.SECONDS);
                } finally { executor.shutdownNow(); }
            }
        } finally { Files.deleteIfExists(script); Files.deleteIfExists(dir); }
        System.out.println("QR generation, discovery, pairing, connection, timeout, and cancellation tests passed.");
    }
}
