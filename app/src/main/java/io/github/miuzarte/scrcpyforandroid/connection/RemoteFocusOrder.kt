package io.github.miuzarte.scrcpyforandroid.connection

/** Stable focus order for the dynamic TV home device list. */
internal fun connectionHomeFocusKeys(
    deviceCount: Int,
    streaming: Boolean,
    busy: Boolean,
): List<String> = buildList {
    repeat(deviceCount) { index ->
        add("device-$index")
        add("forget-$index")
    }
    if (deviceCount > 0) add("refresh")
    add("qr")
    add("handoff")
    add("methods")
    add("settings")
    if (streaming) add("disconnect")
    if (busy) add("cancel")
}

/**
 * The deterministic part of remote focus traversal.  Keeping this outside Compose
 * lets the order be checked on the JVM; Android still owns the actual focus window.
 */
internal class RemoteFocusOrder(private val keys: List<String>) {
    init { require(keys.isNotEmpty()) { "Focus order cannot be empty" } }

    fun move(current: String, direction: Int): String {
        val index = keys.indexOf(current).takeIf { it >= 0 } ?: 0
        return keys[(index + direction % keys.size + keys.size) % keys.size]
    }

    fun previous(current: String) = move(current, -1)
    fun next(current: String) = move(current, 1)
}
