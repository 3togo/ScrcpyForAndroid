package io.github.3togo.scrcaster.desktop;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.3togo.scrcaster.core.AspectRatio;

/** Dependency-free integration tests using a fake executable, never a real device. */
public final class BackendTest {
    public static void main(String[] args) throws Exception {
        assert Backend.endpoint(" phone.local:5555 ").equals("phone.local:5555");
        assert Backend.endpoint("[::1]:37001").equals("[::1]:37001");
        for (String invalid : List.of("localhost", "-host:5", "a:0", "a:65536", "a:1;touch /tmp/x", "::1:5")) {
            try { Backend.endpoint(invalid); throw new AssertionError(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        var devices = Backend.parseDevices("* daemon started successfully *\nList of devices attached\nUSB123 device product:p model:Pixel_9 transport_id:1\n192.168.0.2:55 offline\nusb unauthorized\nother no permissions (udev rules)\n");
        assert devices.size() == 4;
        assert devices.get(0).ready() && devices.get(0).model().equals("Pixel 9");
        assert !devices.get(2).ready();
        assert devices.get(3).state().equals("no permissions");
        Path temp = Files.createTempDirectory("scrcaster-desktop-test");
        Path fake = temp.resolve("fake adb");
        Files.writeString(fake, "#!/bin/sh\ncase \"$1\" in\npair) read code; [ \"$code\" = 123456 ] || exit 2; echo paired;;\nfail) echo failure; exit 7;;\nwait) exec sleep 20;;\n*) printf '%s\\n' \"$@\";;\nesac\n");
        fake.toFile().setExecutable(true);
        try (Backend backend = new Backend(fake.toString(), "/path with spaces/scrcpy")) {
            var command = backend.streamCommand("serial;echo unsafe", new Backend.Options(1920, 60, 8, false, false, true, Backend.Fill.STRETCH, "", false, "/tmp/file with spaces.mkv"));
            assert command.contains("--serial=serial;echo unsafe");
            assert command.contains("--record=/tmp/file with spaces.mkv");
            assert command.containsAll(List.of("--no-audio", "--no-control", "--fullscreen", "--render-fit=stretched"));
            // A fixed aspect-ratio crop preserves its shape and suppresses stretching.
            assert !backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, true, Backend.Fill.FIT, "1080:1920:0:240", false, "")).contains("--render-fit");
            assert backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, true, Backend.Fill.FIT, "1080:1920:0:240", false, "")).contains("--crop=1080:1920:0:240");
            var cropLong = backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, true,
                Backend.Fill.CROP_LONG_EDGE, "1080:1920:0:240", true, ""));
            assert cropLong.contains("--crop=1080:1920:0:240");
            // Long-edge is an invariant: even a bad/stale stretch hint must never distort
            // portrait into landscape.
            assert !cropLong.contains("--render-fit=stretched");
            var cropShort = backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, true,
                Backend.Fill.CROP_SHORT_EDGE, "1080:1920:0:240", true, ""));
            assert cropShort.contains("--crop=1080:1920:0:240");
            assert cropShort.contains("--render-fit=stretched");
            // Crop applies whether or not the window is fullscreen; stretch needs no crop.
            assert backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, false, Backend.Fill.CROP_LONG_EDGE, "1080:1920:0:240", false, "")).contains("--crop=1080:1920:0:240");
            assert !backend.streamCommand("s", new Backend.Options(1920, 60, 8, true, true, false, Backend.Fill.STRETCH, "", false, "")).contains("--crop");
            assert backend.adb("echo", "a b", "$(touch nope)").equals("echo\na b\n$(touch nope)\n");
            char[] code = "123456".toCharArray();
            assert backend.pair("localhost:1234", code).contains("paired");
            assert Arrays.equals(code, new char[6]);
            try { backend.adb("fail"); throw new AssertionError("Missing exit error"); }
            catch (java.io.IOException expected) { assert expected.getMessage().contains("7"); }
            long begin = System.nanoTime();
            try { backend.run(List.of(fake.toString(), "wait"), null, Duration.ofMillis(150)); throw new AssertionError("Missing timeout"); }
            catch (java.io.IOException expected) { assert expected.getMessage().contains("timed out"); }
            assert Duration.ofNanos(System.nanoTime() - begin).toSeconds() < 5;
            Process child = backend.start(List.of(fake.toString(), "wait"));
            backend.close();
            assert child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            try { backend.start(List.of(fake.toString())); throw new AssertionError("Started after close"); }
            catch (java.io.IOException expected) { }
        } finally { Files.deleteIfExists(fake); Files.deleteIfExists(temp); }
        // Crop math for a 1080x2400 phone. The crop is expressed in the natural orientation
        // and rotated with the device, so it is the same 9:16 region whatever the shape of the
        // window: a portrait source stays portrait and a landscape source stays landscape.
        assert Backend.fillCrop(1080, 2400, true, 3840, 2160).equals("1080:1920:0:240");
        assert Backend.fillCrop(1080, 2400, false, 3840, 2160).equals("1080:1920:0:240");
        assert Backend.fillCrop(1080, 2400, true, 2160, 3840).equals("1080:1920:0:240");
        assert Backend.fillCrop(1080, 2400, false, 2160, 3840).equals("1080:1920:0:240");
        // Short-edge/cover fill may deliberately change the displayed orientation so the
        // monitor is completely covered; long-edge fill above never does.
        assert Backend.coverCrop(1080, 2400, false, 3840, 2160).equals("1080:608:0:896");
        assert Backend.coverCrop(1080, 2400, true, 3840, 2160).equals("1080:1920:0:240");
        // Aspect-ratio targets flip with device orientation (3:4 / 9:16 for portrait).
        assert Backend.targetRatio(AspectRatio.Ratio.DEVICE, false, "") == 0.0;
        assert Backend.targetRatio(AspectRatio.Ratio.SQUARE, false, "") == 1.0;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.CLASSIC, false, "") - 3.0 / 4) < 1e-9;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.CLASSIC, true, "") - 4.0 / 3) < 1e-9;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.WIDE, false, "") - 9.0 / 16) < 1e-9;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.WIDE, true, "") - 16.0 / 9) < 1e-9;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.CUSTOM, false, "21:9") - 21.0 / 9) < 1e-9;
        assert Math.abs(Backend.targetRatio(AspectRatio.Ratio.CUSTOM, false, "1.78") - 1.78) < 1e-9;
        // Android's renderer uses the same rule: presets follow the mirrored source,
        // never the receiving screen, so portrait input cannot become a landscape crop.
        assert Math.abs(AspectRatio.orientToSource(16.0 / 9, 1080, 2400) - 9.0 / 16) < 1e-9;
        assert Math.abs(AspectRatio.orientToSource(16.0 / 9, 2400, 1080) - 16.0 / 9) < 1e-9;
        try { Backend.targetRatio(AspectRatio.Ratio.CUSTOM, false, "bad"); throw new AssertionError("bad ratio accepted"); }
        catch (IllegalArgumentException expected) { }
        // cropForRatio keeps the requested ratio and stays within the screen, even boundaries.
        // For a landscape device the crop is rotated, so its height becomes the picture width.
        String[] r169 = Backend.cropForRatio(1080, 2400, true, 16.0 / 9).split(":");
        assert Integer.parseInt(r169[0]) % 2 == 0 && Integer.parseInt(r169[1]) % 2 == 0;
        assert Math.abs(Integer.parseInt(r169[1]) / (double) Integer.parseInt(r169[0]) - 16.0 / 9) < 0.02;
        // A landscape target on a portrait device is turned around, so the picture stays portrait.
        String[] r916 = Backend.cropForRatio(1080, 2400, false, 16.0 / 9).split(":");
        assert Math.abs(Integer.parseInt(r916[0]) / (double) Integer.parseInt(r916[1]) - 9.0 / 16) < 0.02;
        assert Backend.cropForRatio(1080, 2400, false, 0).equals(Backend.cropForRatio(1080, 2400, false, (double) 1080 / 2400));
        int[] natural = Backend.naturalSize("Physical size: 1080x2400\n");
        assert natural[0] == 1080 && natural[1] == 2400;
        assert Backend.landscape("DisplayViewport{type=INTERNAL, valid=true, displayId=0, orientation=1, deviceWidth=2400, deviceHeight=1080}");
        assert !Backend.landscape("DisplayViewport{type=INTERNAL, valid=true, displayId=0, orientation=0, deviceWidth=1080, deviceHeight=2400}");
        QrPairingTest.run();
        System.out.println("All desktop backend tests passed.");
    }
}
