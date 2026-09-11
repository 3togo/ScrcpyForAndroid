package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget

/**
 * Centralized status-line strings used across connection state updates. Previously scattered as
 * inline literals in [ConnectionStateStore] and [DeviceConnectionController].
 */
internal object ConnectionStatusLines {
    const val DISCONNECTED = "Disconnected"
    const val ADB_CONNECTION_FAILED = "ADB connection failed"
    const val SCRCPY_RUNNING = "scrcpy running"

    /** Formats a `host:port` endpoint label shared by connect / reconnect / stop flows. */
    fun endpoint(host: String, port: Int): String = "$host:$port"
    fun endpoint(target: ConnectionTarget): String = endpoint(target.host, target.port)
}
