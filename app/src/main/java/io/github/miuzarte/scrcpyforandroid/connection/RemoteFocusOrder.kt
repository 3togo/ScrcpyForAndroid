package io.github.miuzarte.scrcpyforandroid.connection

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
