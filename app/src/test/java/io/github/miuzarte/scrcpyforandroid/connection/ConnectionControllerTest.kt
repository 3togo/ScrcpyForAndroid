package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ConnectionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val phone = ConnectionEndpoint("172.16.30.109", 39691)
    private val pairing = ConnectionEndpoint("172.16.30.109", 40000)
    private val store = MemoryStore()
    private val backend = FakeBackend()
    private fun controller() = ConnectionController(scope, backend, store)
    @After fun tearDown() { scope.cancel() }

    @Test fun pairingUsesDiscoveredConnectionPortAndPersistsSuccessfulPhone() = runBlocking {
        backend.discovered = phone
        val controller = controller()
        controller.showDialog(ConnectionDialog.CODE)
        controller.setAddress(pairing.toString())
        controller.setPairingCode("123456")
        controller.submitAddress()
        assertEquals(listOf(pairing), backend.paired)
        assertEquals(listOf(phone), backend.connected)
        assertEquals(phone, store.value.lastEndpoint)
        assertEquals(ConnectionDialog.NONE, controller.state.value.dialog)
        assertEquals("", controller.state.value.pairingCode)
        assertEquals(ConnectionEvent.PLAYBACK, controller.events.first())
    }

    @Test fun missingAdvertisementAsksForAddressWithoutReusingPairingPort() {
        val controller = controller()
        controller.showDialog(ConnectionDialog.CODE)
        controller.setAddress(pairing.toString())
        controller.setPairingCode("123456")
        controller.submitAddress()
        assertTrue(backend.connected.isEmpty())
        assertEquals(ConnectionStatus.PAIRED, controller.state.value.status)
        assertEquals(ConnectionDialog.ADDRESS, controller.state.value.dialog)
        assertEquals("172.16.30.109:", controller.state.value.address)
        assertNull(store.value.lastEndpoint)
    }

    @Test fun invalidPairingInputDoesNotContactDevice() {
        val controller = controller()
        controller.showDialog(ConnectionDialog.CODE)
        controller.setAddress("172.16.30.109")
        controller.submitAddress()
        assertEquals(ConnectionStatus.INVALID_ADDRESS, controller.state.value.status)
        controller.setAddress(pairing.toString())
        controller.setPairingCode("123")
        controller.submitAddress()
        assertEquals(ConnectionStatus.INVALID_CODE, controller.state.value.status)
        assertTrue(backend.paired.isEmpty())
    }

    @Test fun cancelPairingCannotConnectAfterLateDiscovery() {
        val pending = CompletableDeferred<ConnectionEndpoint?>()
        backend.find = { pending.await() }
        val controller = controller()
        controller.showDialog(ConnectionDialog.CODE)
        controller.setAddress(pairing.toString())
        controller.setPairingCode("123456")
        controller.submitAddress()
        assertTrue(controller.state.value.busy)
        controller.dismissDialog()
        pending.complete(phone)
        assertFalse(controller.state.value.busy)
        assertEquals(ConnectionDialog.NONE, controller.state.value.dialog)
        assertTrue(backend.connected.isEmpty())
    }

    @Test fun connectionFailureCleansSessionAndRetainsPreviousPhone() {
        store.value = ConnectionPreferences(lastEndpoint = phone)
        backend.connectAction = { error("network unavailable") }
        val controller = controller()
        controller.showDialog(ConnectionDialog.ADDRESS)
        controller.setAddress("172.16.30.50:5555")
        controller.submitAddress()
        assertEquals(1, backend.disconnects)
        assertEquals(phone, store.value.lastEndpoint)
        assertEquals(ConnectionStatus.ERROR, controller.state.value.status)
        assertFalse(controller.state.value.busy)
    }

    @Test fun cancellingConnectCleansUpBeforeControlsAreEnabled() {
        backend.connectAction = { awaitCancellation() }
        val controller = controller()
        controller.showDialog(ConnectionDialog.ADDRESS)
        controller.setAddress(phone.toString())
        controller.submitAddress()
        assertTrue(controller.state.value.busy)
        controller.back()
        assertEquals(1, backend.disconnects)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.streaming)
    }

    @Test fun rapidMethodChangesWaitForCancelledConnectionCleanup() {
        val cleanup = CompletableDeferred<Unit>()
        backend.connectAction = { awaitCancellation() }
        backend.disconnectAction = { cleanup.await() }
        backend.qrFind = { awaitCancellation() }
        val controller = controller()
        controller.showDialog(ConnectionDialog.ADDRESS)
        controller.setAddress(phone.toString())
        controller.submitAddress()
        controller.showDialog(ConnectionDialog.METHODS)
        controller.showDialog(ConnectionDialog.QR)
        assertEquals("", controller.state.value.qrPayload)
        cleanup.complete(Unit)
        assertEquals(ConnectionDialog.QR, controller.state.value.dialog)
        assertTrue(controller.state.value.qrPayload.isNotEmpty())
        assertTrue(controller.state.value.busy)
    }

    @Test fun resumeDoesNotReconnectOrReplacePlaybackSettings() = runBlocking {
        backend.streaming = true
        val controller = controller()
        controller.reconnect()
        assertEquals(ConnectionEvent.PLAYBACK, controller.events.first())
        assertTrue(backend.connected.isEmpty())
    }

    @Test fun qrRetryCancelsPriorDiscoveryAndGeneratesNewPayload() {
        backend.qrFind = { awaitCancellation() }
        val controller = controller()
        controller.showDialog(ConnectionDialog.QR)
        val first = controller.state.value.qrPayload
        assertTrue(first.startsWith("WIFI:T:ADB;S:studio-"))
        controller.showDialog(ConnectionDialog.QR)
        assertNotEquals(first, controller.state.value.qrPayload)
        controller.stopPairing()
        assertEquals(ConnectionDialog.NONE, controller.state.value.dialog)
        assertEquals("", controller.state.value.qrPayload)
        assertFalse(controller.state.value.busy)
    }

    @Test fun certificateRejectionReturnsToQrPairing() {
        backend.connectAction = { error("CERTIFICATE_UNKNOWN") }
        backend.qrFind = { null }
        val controller = controller()
        controller.showDialog(ConnectionDialog.ADDRESS)
        controller.setAddress(phone.toString())
        controller.submitAddress()
        assertEquals(1, backend.disconnects)
        assertEquals(ConnectionDialog.QR, controller.state.value.dialog)
        assertEquals(ConnectionStatus.QR_TIMEOUT, controller.state.value.status)
    }

    @Test fun playbackPreferencesPersistWithoutChangingSavedEndpoint() {
        store.value = ConnectionPreferences(lastEndpoint = phone)
        val controller = controller()
        val options = PlaybackPreferences(audio = false, renderFit = "FIT", aspectRatio = "CUSTOM", customRatio = "21:9")
        controller.setPlayback(options)
        assertEquals(phone, store.value.lastEndpoint)
        assertEquals(options, store.value.playback)
    }

    private class MemoryStore : ConnectionPreferencesStore {
        var value = ConnectionPreferences()
        override fun load() = value
        override fun save(preferences: ConnectionPreferences) { value = preferences }
    }
    private class FakeBackend : ConnectionBackend {
        var streaming = false
        var disconnects = 0
        var discovered: ConnectionEndpoint? = null
        var find: (suspend () -> ConnectionEndpoint?)? = null
        var qrFind: suspend () -> ConnectionEndpoint? = { null }
        var connectAction: suspend () -> Unit = { streaming = true }
        var disconnectAction: suspend () -> Unit = {}
        val paired = mutableListOf<ConnectionEndpoint>()
        val connected = mutableListOf<ConnectionEndpoint>()
        override fun isStreaming() = streaming
        override fun cancelPendingConnect() = Unit
        override suspend fun connect(endpoint: ConnectionEndpoint, preferences: PlaybackPreferences) {
            connected += endpoint; connectAction()
        }
        override suspend fun disconnect() { disconnects++; disconnectAction(); streaming = false }
        override suspend fun pair(endpoint: ConnectionEndpoint, secret: String): Boolean { paired += endpoint; return true }
        override suspend fun findQrService(name: String) = qrFind()
        override suspend fun findConnection(host: String) = find?.invoke() ?: discovered
    }
}
