package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR sequence both layouts share. The service name and the pairing secret are random, so the
 * assertions tie what the transport was asked to use back to the published payload. The scanned
 * direction feeds the same sequence with a name and secret read off a peer's QR instead.
 */
class QrPairingFlowTest {

    private class Recorder {
        val statuses = mutableListOf<ConnectionStatus>()
        var payload = ""
    }

    private class FakeTransport(
        val qrService: suspend () -> Pair<String, Int>?,
        val pairOutcome: (String, Int, String) -> Boolean = { _, _, _ -> true },
        val connectService: suspend (String) -> Pair<String, Int>? = { null },
    ) : QrPairingTransport {
        var requestedName: String? = null
        var pairedEndpoint: Pair<String, Int>? = null
        var pairedSecret: String? = null
        var connectLookupHost: String? = null

        override suspend fun findQrService(name: String): Pair<String, Int>? {
            requestedName = name
            return qrService()
        }

        override suspend fun pair(host: String, port: Int, secret: String): Boolean {
            pairedEndpoint = host to port
            pairedSecret = secret
            return pairOutcome(host, port, secret)
        }

        override suspend fun findConnection(host: String): Pair<String, Int>? {
            connectLookupHost = host
            return connectService(host)
        }
    }

    private suspend fun run(transport: QrPairingTransport, recorder: Recorder) = runQrPairing(
        transport,
        onPayload = { recorder.payload = it },
        onStatus = { recorder.statuses += it },
    )

    @Test fun publishedPayloadCarriesTheQueriedServiceNameAndSecret() = runBlocking {
        val recorder = Recorder()
        val transport = FakeTransport(qrService = { "172.16.30.109" to 40000 })
        val result = run(transport, recorder)

        assertEquals(
            "WIFI:T:ADB;S:${transport.requestedName};P:${transport.pairedSecret};;",
            recorder.payload,
        )
        assertTrue(recorder.payload, recorder.payload.startsWith("WIFI:T:ADB;S:studio-"))
        assertEquals("172.16.30.109", (result as QrPairingResult.Paired).host)
    }

    @Test fun missingAdvertisementEndsTheSessionWithoutPairing() = runBlocking {
        val recorder = Recorder()
        val transport = FakeTransport(qrService = { null })

        assertEquals(QrPairingResult.Timeout, run(transport, recorder))
        assertEquals(listOf(ConnectionStatus.QR_TIMEOUT), recorder.statuses)
        assertEquals(null, transport.pairedEndpoint)
    }

    @Test fun rejectedPairingIsReportedAndStopsBeforeLookingUpPorts() = runBlocking {
        val recorder = Recorder()
        val transport = FakeTransport(
            qrService = { "172.16.30.109" to 40000 },
            pairOutcome = { _, _, _ -> false },
        )

        assertEquals(QrPairingResult.PairFailed, run(transport, recorder))
        assertEquals(listOf(ConnectionStatus.PAIRING, ConnectionStatus.PAIR_FAILED), recorder.statuses)
        assertEquals(null, transport.connectLookupHost)
    }

    @Test fun pairingUsesTheDiscoveredPairingPortOnly() = runBlocking {
        val transport = FakeTransport(qrService = { "172.16.30.109" to 40000 })

        run(transport, Recorder())

        assertEquals("172.16.30.109" to 40000, transport.pairedEndpoint)
        assertEquals("172.16.30.109", transport.connectLookupHost)
    }

    @Test fun absentConnectServiceIsNotReplacedByThePairingPort() = runBlocking {
        val recorder = Recorder()
        val transport = FakeTransport(qrService = { "172.16.30.109" to 40000 })

        val result = run(transport, recorder) as QrPairingResult.Paired

        assertEquals(null, result.connection)
        assertEquals(
            listOf(ConnectionStatus.PAIRING, ConnectionStatus.FINDING_PORT),
            recorder.statuses,
        )
    }

    @Test fun discoveredConnectServiceIsReturnedSeparately() = runBlocking {
        val transport = FakeTransport(
            qrService = { "172.16.30.109" to 40000 },
            connectService = { "172.16.30.109" to 39691 },
        )

        val result = run(transport, Recorder()) as QrPairingResult.Paired

        assertEquals("172.16.30.109" to 39691, result.connection)
    }

    @Test fun cancelledSessionCannotPairAfterItsDialogIsGone() = runBlocking {
        var paired = false
        val transport = object : QrPairingTransport {
            override suspend fun findQrService(name: String): Pair<String, Int>? = awaitCancellation()
            override suspend fun pair(host: String, port: Int, secret: String): Boolean {
                paired = true
                return true
            }

            override suspend fun findConnection(host: String): Pair<String, Int>? = null
        }

        val job = launch { runQrPairing(transport, onPayload = {}, onStatus = {}) }
        job.cancelAndJoin()

        assertFalse(paired)
    }

    @Test fun scannedNameAndSecretAreUsedVerbatim() = runBlocking {
        val statuses = mutableListOf<ConnectionStatus>()
        val transport = FakeTransport(qrService = { "172.16.30.109" to 40000 })

        val result = runQrPairingWith(
            transport,
            name = "studio-c308",
            secret = "2f6d",
            onStatus = { statuses += it },
        ) as QrPairingResult.Paired

        assertEquals("studio-c308", transport.requestedName)
        assertEquals("2f6d", transport.pairedSecret)
        assertEquals("172.16.30.109", result.host)
        assertEquals(null, result.connection)
    }

    @Test fun scannedServiceWithoutAdvertisementTimesOut() = runBlocking {
        val statuses = mutableListOf<ConnectionStatus>()
        val transport = FakeTransport(qrService = { null })

        val result = runQrPairingWith(transport, "studio-gone", "2f6d") { statuses += it }

        assertEquals(QrPairingResult.Timeout, result)
        assertEquals(listOf(ConnectionStatus.QR_TIMEOUT), statuses)
        assertEquals(null, transport.pairedEndpoint)
    }

    @Test fun scannedSecretRejectedByThePeerStopsBeforeLookingUpPorts() = runBlocking {
        val statuses = mutableListOf<ConnectionStatus>()
        val transport = FakeTransport(
            qrService = { "172.16.30.109" to 40000 },
            pairOutcome = { _, _, _ -> false },
        )

        val result = runQrPairingWith(transport, "studio-c308", "stale") { statuses += it }

        assertEquals(QrPairingResult.PairFailed, result)
        assertEquals(listOf(ConnectionStatus.PAIRING, ConnectionStatus.PAIR_FAILED), statuses)
        assertEquals(null, transport.connectLookupHost)
    }

    @Test fun scannedPairingUsesTheSeparatelyDiscoveredConnectPort() = runBlocking {
        val transport = FakeTransport(
            qrService = { "172.16.30.109" to 40000 },
            connectService = { "172.16.30.109" to 39691 },
        )

        val result = runQrPairingWith(transport, "studio-c308", "2f6d") {} as QrPairingResult.Paired

        assertEquals("172.16.30.109" to 40000, transport.pairedEndpoint)
        assertEquals("172.16.30.109" to 39691, result.connection)
    }

    @Test fun cancelledScanCannotPairAfterItsDialogIsGone() = runBlocking {
        var paired = false
        val transport = object : QrPairingTransport {
            override suspend fun findQrService(name: String): Pair<String, Int>? = awaitCancellation()
            override suspend fun pair(host: String, port: Int, secret: String): Boolean {
                paired = true
                return true
            }

            override suspend fun findConnection(host: String): Pair<String, Int>? = null
        }

        val job = launch { runQrPairingWith(transport, "studio-c308", "2f6d") {} }
        job.cancelAndJoin()

        assertFalse(paired)
    }
}
