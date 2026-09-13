package io.github.miuzarte.scrcpyforandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFocusOrderTest {
    private val qr = RemoteFocusOrder(listOf("retry", "code", "back"))

    @Test fun qrFocusOrderWrapsForRemoteNavigation() {
        assertEquals("code", qr.previous("back"))
        assertEquals("retry", qr.previous("code"))
        assertEquals("code", qr.next("retry"))
        assertEquals("back", qr.next("code"))
    }

    @Test fun unknownFocusedNodeFallsBackToFirstAction() {
        assertEquals("code", qr.next("missing"))
    }

    @Test fun multiDeviceHomeIncludesEveryActionInVisualOrder() {
        assertEquals(
            listOf(
                "device-0", "forget-0",
                "device-1", "forget-1",
                "refresh", "qr", "handoff", "methods", "settings", "disconnect", "cancel",
            ),
            connectionHomeFocusKeys(deviceCount = 2, streaming = true, busy = true),
        )
    }

    @Test fun emptyDeviceHomeOmitsRefreshAndDeviceActions() {
        assertEquals(
            listOf("qr", "handoff", "methods", "settings"),
            connectionHomeFocusKeys(deviceCount = 0, streaming = false, busy = false),
        )
    }

    @Test fun refreshParticipatesInRemoteTraversal() {
        val order = RemoteFocusOrder(
            connectionHomeFocusKeys(deviceCount = 1, streaming = false, busy = false),
        )
        assertEquals("refresh", order.next("forget-0"))
        assertEquals("qr", order.next("refresh"))
    }
}
