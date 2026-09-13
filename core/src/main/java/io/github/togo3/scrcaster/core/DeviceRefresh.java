package io.github.togo3.scrcaster.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Shared snapshot reconciliation for transient device lists. */
public final class DeviceRefresh {
    private DeviceRefresh() { }

    /**
     * The authoritative current snapshot and the entries added or removed since the previous one.
     * Lists retain their source order and are immutable so every frontend observes the same result.
     */
    public record Result<T>(List<T> devices, List<T> added, List<T> removed) {
        public Result {
            devices = List.copyOf(devices);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }
    }

    /** Compares device snapshots by a stable transport identity such as an ADB serial. */
    public static <T, K> Result<T> reconcile(
            List<T> previous,
            List<T> current,
            Function<T, K> identity) {
        Set<K> previousIds = identities(previous, identity);
        Set<K> currentIds = identities(current, identity);
        List<T> added = current.stream()
                .filter(device -> !previousIds.contains(identity.apply(device)))
                .toList();
        List<T> removed = previous.stream()
                .filter(device -> !currentIds.contains(identity.apply(device)))
                .toList();
        return new Result<>(current, added, removed);
    }

    private static <T, K> Set<K> identities(List<T> devices, Function<T, K> identity) {
        Set<K> result = new HashSet<>();
        for (T device : devices) result.add(identity.apply(device));
        return result;
    }
}
