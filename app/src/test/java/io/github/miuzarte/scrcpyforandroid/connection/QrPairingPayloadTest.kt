package io.github.miuzarte.scrcpyforandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR payload is consumed by an external pairing tool, so its exact shape is a wire format.
 */
class QrPairingPayloadTest {

    @Test
    fun payloadMatchesTheAdbWirelessDebuggingFormat() {
        val pairing = buildAdbQrPairing()
        assertEquals("WIFI:T:ADB;S:${pairing.name};P:${pairing.secret};;", pairing.payload)
    }

    @Test
    fun serviceNameAndSecretUseTheAdbQrTenCharacterFields() {
        // Regression: UUID-sized fields made the TV-sized QR too dense for a real phone to scan.
        val pairing = buildAdbQrPairing()
        assertTrue(pairing.name, pairing.name.matches(Regex("studio-[a-zA-Z0-9]{10}")))
        assertTrue(pairing.secret, pairing.secret.matches(Regex("[a-zA-Z0-9]{10}")))
    }

    @Test
    fun eachPairingIsFresh() {
        val first = buildAdbQrPairing()
        val second = buildAdbQrPairing()
        assertNotEquals(first.payload, second.payload)
        assertNotEquals(first.secret, second.secret)
    }
}
