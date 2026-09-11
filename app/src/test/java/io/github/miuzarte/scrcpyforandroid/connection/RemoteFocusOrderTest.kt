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
}
