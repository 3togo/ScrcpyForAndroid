package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Backend-agnostic keep-alive / auto-reconnect loop, shared by both the TV and phone layouts.
 *
 * Both modes can lose an established session (device sleeps, network drops, scrcpy crashes)
 * without any user action. This primitive polls [KeepAlivePolicy.keepAliveCheck] on an interval
 * while [KeepAlivePolicy.isConnected] holds; when liveness fails it calls
 * [KeepAlivePolicy.reconnect] and reports success/failure. The phone's multi-device reconnect
 * (USB/shortcut/mDNS discovery) is intentionally *not* part of this primitive — it stays in the
 * phone-specific [io.github.miuzarte.scrcpyforandroid.services.DeviceAdbBackgroundRunner]; only
 * the polling mechanics are shared so there is exactly one implementation of the loop.
 */
internal data class KeepAlivePolicy(
    val intervalMs: Long,
    val isConnected: () -> Boolean,
    val isForeground: () -> Boolean = { true },
    val keepAliveCheck: suspend () -> Boolean,
    val reconnect: suspend () -> Unit,
    val onReconnectSuccess: suspend () -> Unit = {},
    val onReconnectFailure: suspend (Throwable) -> Unit = {},
    val shouldAutoReconnect: () -> Boolean = { true },
)

internal class ConnectionKeepAlive(
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, policy: KeepAlivePolicy): Job {
        stop()
        job = scope.launch(dispatcher) { runLoop(policy) }
        return job!!
    }

    /**
     * Runs the keep-alive loop inline on the caller's dispatcher. Used when the caller already
     * owns a coroutine scope (e.g. the phone's single-thread [dispatcher] wrapper) and wants the
     * suspending call to complete only when the loop ends.
     */
    suspend fun runLoop(policy: KeepAlivePolicy) {
        while (coroutineContext.isActive && policy.isConnected()) {
            if (!policy.isForeground()) {
                delay(policy.intervalMs)
                continue
            }
            delay(policy.intervalMs)
            val alive = runCatching { policy.keepAliveCheck() }.getOrDefault(false)
            if (alive) continue
            if (!policy.shouldAutoReconnect()) break
            try {
                policy.reconnect()
                withContext(Dispatchers.Main) { policy.onReconnectSuccess() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { policy.onReconnectFailure(error) }
                break
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    val isActive: Boolean get() = job?.isActive == true
}
