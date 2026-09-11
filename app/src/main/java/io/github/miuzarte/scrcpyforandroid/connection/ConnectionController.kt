package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class ConnectionEndpoint(val host: String, val port: Int) {
    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"
    companion object {
        fun parse(text: String): ConnectionEndpoint? {
            val match = Regex("^([a-zA-Z0-9._-]+|\\[[0-9a-fA-F:%._-]+\\]):([0-9]{1,5})$").matchEntire(text.trim()) ?: return null
            val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return ConnectionEndpoint(match.groupValues[1].removeSurrounding("[", "]"), port)
        }
    }
}

internal data class PlaybackPreferences(
    val audio: Boolean = true,
    val renderFit: String = "LONG_EDGE",
    val aspectRatio: String = "DEVICE",
    val customRatio: String = "",
)

internal data class ConnectionPreferences(
    val lastEndpoint: ConnectionEndpoint? = null,
    val playback: PlaybackPreferences = PlaybackPreferences(),
)

internal enum class ConnectionDialog { NONE, METHODS, ADDRESS, CODE, QR, PLAYBACK }
internal enum class ConnectionStatus {
    READY, CONNECTING, CONNECTED, DISCONNECTED, PAIRING, FINDING_PORT, PAIRED,
    PAIR_REQUIRED, INVALID_ADDRESS, INVALID_CODE, QR_TIMEOUT, PAIR_FAILED, ERROR,
}

internal data class ConnectionUiState(
    val preferences: ConnectionPreferences,
    val dialog: ConnectionDialog = ConnectionDialog.NONE,
    val status: ConnectionStatus = ConnectionStatus.READY,
    val busy: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    val address: String = "",
    val pairingCode: String = "",
    val qrPayload: String = "",
)

/** No Activity, Views or Compose: both layouts can use the same connection state machine. */
internal interface ConnectionBackend {
    fun isStreaming(): Boolean
    fun cancelPendingConnect()
    suspend fun connect(endpoint: ConnectionEndpoint, preferences: PlaybackPreferences)
    suspend fun disconnect()
    suspend fun pair(endpoint: ConnectionEndpoint, secret: String): Boolean
    suspend fun findQrService(name: String): ConnectionEndpoint?
    suspend fun findConnection(host: String): ConnectionEndpoint?
}

internal interface ConnectionPreferencesStore {
    fun load(): ConnectionPreferences
    fun save(preferences: ConnectionPreferences)
}

internal enum class ConnectionEvent { PLAYBACK, FINISH }

internal class ConnectionController(
    private val scope: CoroutineScope,
    private val backend: ConnectionBackend,
    private val store: ConnectionPreferencesStore,
) {
    private val mutableState = MutableStateFlow(ConnectionUiState(store.load(), streaming = backend.isStreaming()))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<ConnectionEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var operation: Job? = null

    fun refresh() { mutableState.update { it.copy(streaming = backend.isStreaming()) } }
    fun setAddress(value: String) { mutableState.update { it.copy(address = value) } }
    fun setPairingCode(value: String) { mutableState.update { it.copy(pairingCode = value) } }
    fun setPlayback(value: PlaybackPreferences) {
        if (state.value.busy) return
        val preferences = state.value.preferences.copy(playback = value)
        store.save(preferences)
        mutableState.update { it.copy(preferences = preferences) }
    }

    fun showDialog(dialog: ConnectionDialog) {
        // Retry / change method waits for the previous pairing job to release its resources.
        val previous = operation
        if (previous?.isActive == true) backend.cancelPendingConnect()
        previous?.cancel()
        operation = scope.launch {
            withContext(NonCancellable) { previous?.join() }
            coroutineContext.ensureActive()
            mutableState.update { it.copy(
                dialog = dialog, busy = false, error = null, pairingCode = "", qrPayload = "",
                status = ConnectionStatus.READY,
                address = if (dialog == ConnectionDialog.ADDRESS) it.preferences.lastEndpoint?.toString().orEmpty() else "",
            ) }
            if (dialog == ConnectionDialog.QR) perform { pairQr() }
        }
    }

    fun dismissDialog() {
        val previous = operation
        if (previous?.isActive == true) backend.cancelPendingConnect()
        previous?.cancel()
        operation = scope.launch {
            withContext(NonCancellable) { previous?.join() }
            coroutineContext.ensureActive()
            mutableState.update { it.copy(dialog = ConnectionDialog.NONE, busy = false,
                pairingCode = "", qrPayload = "", error = null,
                status = if (backend.isStreaming()) ConnectionStatus.CONNECTED else ConnectionStatus.READY) }
        }
    }

    fun submitAddress() {
        if (state.value.busy) return
        val endpoint = ConnectionEndpoint.parse(state.value.address)
        if (endpoint == null) {
            mutableState.update { it.copy(status = ConnectionStatus.INVALID_ADDRESS) }
            return
        }
        if (state.value.dialog == ConnectionDialog.CODE) {
            val code = state.value.pairingCode.trim()
            if (!code.matches(Regex("[0-9]{6}"))) {
                mutableState.update { it.copy(status = ConnectionStatus.INVALID_CODE) }
                return
            }
            operation = scope.launch { perform {
                mutableState.update { it.copy(status = ConnectionStatus.PAIRING) }
                if (!backend.pair(endpoint, code)) {
                    mutableState.update { it.copy(status = ConnectionStatus.PAIR_FAILED) }
                    return@perform
                }
                afterPairing(endpoint.host)
            } }
        } else connect(endpoint)
    }

    fun reconnect() {
        if (state.value.busy) return
        if (backend.isStreaming()) {
            eventChannel.trySend(ConnectionEvent.PLAYBACK)
            return
        }
        state.value.preferences.lastEndpoint?.let(::connect)
    }

    private fun connect(endpoint: ConnectionEndpoint) {
        if (state.value.busy) return
        operation = scope.launch { perform { startStream(endpoint) } }
    }

    private suspend fun startStream(endpoint: ConnectionEndpoint) {
        mutableState.update { it.copy(status = ConnectionStatus.CONNECTING, error = null) }
        try {
            backend.connect(endpoint, state.value.preferences.playback)
            coroutineContext.ensureActive()
            val preferences = state.value.preferences.copy(lastEndpoint = endpoint)
            store.save(preferences)
            mutableState.update { it.copy(preferences = preferences, streaming = true,
                status = ConnectionStatus.CONNECTED, dialog = ConnectionDialog.NONE, pairingCode = "", qrPayload = "") }
            eventChannel.send(ConnectionEvent.PLAYBACK)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try { backend.disconnect() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            if (error is CancellationException) throw error
            if (generateSequence<Throwable>(error) { it.cause }.any {
                it.message?.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) == true
            }) {
                mutableState.update { it.copy(status = ConnectionStatus.PAIR_REQUIRED, dialog = ConnectionDialog.QR) }
                pairQr()
            } else throw error
        }
    }

    private suspend fun pairQr() {
        val name = "studio-" + UUID.randomUUID().toString().replace("-", "")
        val secret = UUID.randomUUID().toString().replace("-", "")
        mutableState.update { it.copy(qrPayload = "WIFI:T:ADB;S:$name;P:$secret;;") }
        val endpoint = backend.findQrService(name)
        coroutineContext.ensureActive()
        if (endpoint == null) {
            mutableState.update { it.copy(status = ConnectionStatus.QR_TIMEOUT) }
            return
        }
        mutableState.update { it.copy(status = ConnectionStatus.PAIRING) }
        if (!backend.pair(endpoint, secret)) {
            mutableState.update { it.copy(status = ConnectionStatus.PAIR_FAILED) }
            return
        }
        afterPairing(endpoint.host)
    }

    private suspend fun afterPairing(host: String) {
        coroutineContext.ensureActive()
        mutableState.update { it.copy(status = ConnectionStatus.FINDING_PORT) }
        val endpoint = backend.findConnection(host)
        coroutineContext.ensureActive()
        if (endpoint == null) {
            // Never reuse the pairing port as a connection port.
            mutableState.update { it.copy(dialog = ConnectionDialog.ADDRESS, status = ConnectionStatus.PAIRED,
                address = if (':' in host) "[$host]:" else "$host:", qrPayload = "", pairingCode = "") }
        } else startStream(endpoint)
    }

    fun disconnect(finish: Boolean = false) {
        if (state.value.busy) return
        operation = scope.launch { perform {
            backend.disconnect()
            mutableState.update { it.copy(streaming = false, status = ConnectionStatus.DISCONNECTED) }
            if (finish) eventChannel.send(ConnectionEvent.FINISH)
        } }
    }

    fun back() {
        when {
            state.value.dialog != ConnectionDialog.NONE -> dismissDialog()
            state.value.busy -> dismissDialog()
            backend.isStreaming() -> disconnect(finish = true)
            else -> eventChannel.trySend(ConnectionEvent.FINISH)
        }
    }

    fun stopPairing() {
        if (state.value.dialog in setOf(ConnectionDialog.QR, ConnectionDialog.CODE)) dismissDialog()
    }

    fun dispose() { backend.cancelPendingConnect(); operation?.cancel() }

    private suspend fun perform(block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(status = ConnectionStatus.ERROR, error = error.message ?: error.javaClass.simpleName) }
        } finally {
            mutableState.update { it.copy(busy = false, streaming = backend.isStreaming()) }
        }
    }
}
