package io.github.miuzarte.scrcpy.desktop;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

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
        Path temp = Files.createTempDirectory("scrcpy-desktop-test");
        Path fake = temp.resolve("fake adb");
        Files.writeString(fake, "#!/bin/sh\ncase \"$1\" in\npair) read code; [ \"$code\" = 123456 ] || exit 2; echo paired;;\nfail) echo failure; exit 7;;\nwait) exec sleep 20;;\n*) printf '%s\\n' \"$@\";;\nesac\n");
        fake.toFile().setExecutable(true);
        try (Backend backend = new Backend(fake.toString(), "/path with spaces/scrcpy")) {
            var command = backend.streamCommand("serial;echo unsafe", new Backend.Options(1920, 60, 8, false, false, true, "/tmp/file with spaces.mkv"));
            assert command.contains("--serial=serial;echo unsafe");
            assert command.contains("--record=/tmp/file with spaces.mkv");
            assert command.containsAll(List.of("--no-audio", "--no-control", "--fullscreen"));
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
        QrPairingTest.run();
        System.out.println("All desktop backend tests passed.");
    }
}
