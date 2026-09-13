package io.github.togo3.scrcaster.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DeviceRefreshTest {
    private record Device(String serial, String state) { }

    @Test
    public void reconcilesSnapshotsByStableIdentity() {
        Device retainedBefore = new Device("phone", "offline");
        Device retainedNow = new Device("phone", "device");
        Device removed = new Device("tablet", "device");
        Device added = new Device("tv", "device");

        DeviceRefresh.Result<Device> result = DeviceRefresh.reconcile(
                List.of(retainedBefore, removed),
                List.of(retainedNow, added),
                Device::serial);

        assertEquals(List.of(retainedNow, added), result.devices());
        assertEquals(List.of(added), result.added());
        assertEquals(List.of(removed), result.removed());
    }

    @Test
    public void resultDoesNotChangeWhenTheSourceListChanges() {
        ArrayList<String> current = new ArrayList<>(List.of("phone"));
        DeviceRefresh.Result<String> result = DeviceRefresh.reconcile(
                List.of(), current, value -> value);
        current.clear();

        assertEquals(List.of("phone"), result.devices());
        assertThrows(UnsupportedOperationException.class, () -> result.devices().clear());
    }
}
