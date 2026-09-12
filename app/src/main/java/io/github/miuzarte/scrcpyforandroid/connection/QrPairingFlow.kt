package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The two ADB lookups plus the pairing call a QR session needs. Endpoints stay plain host/port
 * pairs so the TV [ConnectionController] and the phone device manager can both drive one flow
 * with their own coordinator.
 */
internal interface QrPairingTransport {
    /** Resolves the pairing service the scanning device advertises under [name]. */
    suspend fun findQrService(name: String): Pair<String, Int>?

    suspend fun pair(host: String, port: Int, secret: String): Boolean

    /** Resolves the connect service of an already paired host. */
    suspend fun findConnection(host: String): Pair<String, Int>?
}

internal sealed class QrPairingResult {
    /** Paired; [connection] is the separate connect endpoint, null when it was not advertised. */
    data class Paired(val host: String, val connection: Pair<String, Int>?) : QrPairingResult()
    data object Timeout : QrPairingResult()
    data object PairFailed : QrPairingResult()
}

/**
 * What one QR pairing session reports to whichever layout shows it. [active] keeps the dialog
 * open; it is cleared once pairing succeeded and the connection attempt was handed over.
 */
internal data class QrPairingUiState(
    val active: Boolean = false,
    val payload: String = "",
    val status: ConnectionStatus = ConnectionStatus.READY,
)

/**
 * Single source of truth for the QR pairing sequence, which serves both directions: our own QR
 * waiting for a peer to scan it, and a peer's QR we read with the camera. Either way the same
 * three lookups follow: resolve the pairing service advertised under [name], pair with [secret]
 * on the pairing port, then look up the connect port. The pairing port is never reused as a
 * connection port.
 *
 * Cancellation is checked after each blocking lookup so a discarded dialog cannot pair or connect
 * after its UI is gone.
 */
internal suspend fun runQrPairingWith(
    transport: QrPairingTransport,
    name: String,
    secret: String,
    onStatus: (ConnectionStatus) -> Unit,
): QrPairingResult {
    val endpoint = transport.findQrService(name)
    coroutineContext.ensureActive()
    if (endpoint == null) {
        onStatus(ConnectionStatus.QR_TIMEOUT)
        return QrPairingResult.Timeout
    }

    onStatus(ConnectionStatus.PAIRING)
    if (!transport.pair(endpoint.first, endpoint.second, secret)) {
        onStatus(ConnectionStatus.PAIR_FAILED)
        return QrPairingResult.PairFailed
    }

    onStatus(ConnectionStatus.FINDING_PORT)
    val connection = transport.findConnection(endpoint.first)
    coroutineContext.ensureActive()
    return QrPairingResult.Paired(endpoint.first, connection)
}

/**
 * Generates this session's credentials, publishes the payload for the peer to scan, and hands the
 * pair of name and secret to [runQrPairingWith].
 */
internal suspend fun runQrPairing(
    transport: QrPairingTransport,
    onPayload: (String) -> Unit,
    onStatus: (ConnectionStatus) -> Unit,
): QrPairingResult {
    val pairing = buildAdbQrPairing()
    onPayload(pairing.payload)
    return runQrPairingWith(transport, pairing.name, pairing.secret, onStatus)
}
