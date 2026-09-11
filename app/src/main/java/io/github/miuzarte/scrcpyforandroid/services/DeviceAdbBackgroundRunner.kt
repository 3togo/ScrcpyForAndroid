package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.connection.ConnectionKeepAlive
import io.github.miuzarte.scrcpyforandroid.connection.KeepAlivePolicy
import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.models.DeviceConnectionType
import io.github.miuzarte.scrcpyforandroid.models.DeviceShortcut
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.Executors

internal class DeviceAdbBackgroundRunner: Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "device-adb-monitor").apply { isDaemon = true }
    }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val keepAlive = ConnectionKeepAlive(dispatcher)

    suspend fun runKeepAliveLoop(
        sessionState: () -> DeviceAdbSessionState,
        isForeground: () -> Boolean,
        intervalMs: Long,
        keepAliveCheck: suspend (host: String, port: Int) -> Boolean,
        reconnect: suspend (host: String, port: Int) -> Unit,
        onReconnectSuccess: suspend (host: String, port: Int) -> Unit,
        onReconnectFailure: suspend (Throwable) -> Unit,
        shouldAutoReconnect: () -> Boolean = { true },
    ) {
        val target = sessionState().currentTarget ?: return
        val host = target.host
        val port = target.port
        withContext(dispatcher) {
            keepAlive.runLoop(KeepAlivePolicy(
                intervalMs = intervalMs,
                isConnected = { sessionState().isConnected && sessionState().currentTarget == target },
                isForeground = isForeground,
                keepAliveCheck = { keepAliveCheck(host, port) },
                reconnect = { reconnect(host, port) },
                onReconnectSuccess = { onReconnectSuccess(host, port) },
                onReconnectFailure = onReconnectFailure,
                shouldAutoReconnect = shouldAutoReconnect,
            ))
        }
    }

    suspend fun runAutoReconnectLoop(
        isConnected: () -> Boolean,
        isForeground: () -> Boolean,
        isAutoReconnectEnabled: () -> Boolean,
        isBusy: () -> Boolean,
        isAdbConnecting: () -> Boolean,
        hasActiveSession: () -> Boolean,
        savedShortcuts: () -> List<DeviceShortcut>,
        isBlacklisted: (String) -> Boolean,
        probeTcpReachable: suspend (host: String, port: Int) -> Boolean,
        discoverConnectService: suspend () -> Pair<String, Int>?,
        onMdnsPortChanged: suspend (host: String, oldPort: Int, newPort: Int) -> Unit,
        connectKnownShortcut: suspend (DeviceShortcut, ConnectionTarget) -> Boolean,
        connectDiscoveredShortcut: suspend (
            host: String,
            port: Int,
            shortcut: DeviceShortcut,
        ) -> Boolean,
        retryIntervalMs: Long,
    ) = withContext(dispatcher) {
        val quickConnectTriedOnce = mutableSetOf<String>()
        while (!isConnected() && isAutoReconnectEnabled()) {
            if (!isForeground() || isBusy() || isAdbConnecting() || hasActiveSession()) {
                delay(retryIntervalMs)
                continue
            }

            val quickCandidates = savedShortcuts()
            if (quickCandidates.isNotEmpty()) {
                for (device in quickCandidates) {
                    if (isConnected() || isAdbConnecting()) break
                    for (addr in device.addresses) {
                        if (isConnected() || isAdbConnecting()) break
                        val target = ConnectionTarget.unmarshalFrom(addr) ?: continue
                        // 跳过 USB 地址 (自动重连不支持 USB 设备的 TCP 连接)
                        if (target.connectionType == DeviceConnectionType.USB) continue
                        if (isBlacklisted(target.host)) continue
                        val targetKey = "${target.host}:${target.port}"
                        if (quickConnectTriedOnce.contains(targetKey)) continue
                        if (!probeTcpReachable(target.host, target.port)) continue
                        quickConnectTriedOnce += targetKey
                        if (connectKnownShortcut(device, target)) break
                    }
                }
                if (isConnected()) break
            }

            val discovered = discoverConnectService()
            if (discovered == null) {
                delay(retryIntervalMs)
                continue
            }

            val (discoveredHost, discoveredPort) = discovered
            if (isBlacklisted(discoveredHost)) {
                delay(retryIntervalMs)
                continue
            }

            val knownDevice = savedShortcuts().firstOrNull { it.matchesHost(discoveredHost) }
            if (knownDevice == null) {
                delay(retryIntervalMs)
                continue
            }

            // When mDNS advertises a new port for a host we already know under a different port,
            // swap the stale port before attempting the discovered connection.
            val portToReplace = computeMdnsPortChange(savedShortcuts(), knownDevice, discoveredHost, discoveredPort)
            if (portToReplace != null) {
                withContext(Dispatchers.Main) {
                    onMdnsPortChanged(discoveredHost, portToReplace, discoveredPort)
                }
            }

            if (isConnected() || isAdbConnecting()) {
                delay(retryIntervalMs)
                continue
            }

            connectDiscoveredShortcut(discoveredHost, discoveredPort, knownDevice)
            delay(retryIntervalMs)
        }
    }

    override fun close() {
        dispatcher.close()
        executor.shutdownNow()
    }
}

/**
 * Pure helper backing the mDNS auto-reconnect loop: given the discovered host/port and the known
 * device it matches, find a stale port recorded for that same host on a *different* shortcut and
 * return it so the caller can swap it. Returns null when no such stale port exists.
 */
private fun computeMdnsPortChange(
    savedShortcuts: List<DeviceShortcut>,
    knownDevice: DeviceShortcut,
    discoveredHost: String,
    discoveredPort: Int,
): Int? = savedShortcuts
    .filter { it != knownDevice }
    .firstNotNullOfOrNull { device ->
        device.addresses.firstNotNullOfOrNull { addr ->
            val ct = ConnectionTarget.unmarshalFrom(addr)
            if (ct != null && ct.host == discoveredHost && ct.port != discoveredPort) ct.port
            else null
        }
    }
