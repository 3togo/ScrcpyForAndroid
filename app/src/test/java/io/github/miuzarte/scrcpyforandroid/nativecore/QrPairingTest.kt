package io.github.miuzarte.scrcpyforandroid.nativecore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QrPairingTest {
    @Test fun normalScannerUsesOneAttempt() = runBlocking {
        val seen = mutableListOf<String>()
        assertTrue(pairQrSecret("test-secret") { seen.add(it); true })
        assertEquals(listOf("test-secret"), seen)
    }
    @Test fun authenticationMismatchRetriesQuotedSecretOnce() = runBlocking {
        val seen = mutableListOf<String>()
        assertTrue(pairQrSecret("test-secret") {
            seen.add(it)
            if (seen.size == 1) throw IllegalStateException(AdbInvalidPairingCodeException())
            true
        })
        assertEquals(listOf("test-secret", "\"test-secret\""), seen)
    }
    @Test fun networkFailureDoesNotRetry() = runBlocking {
        var attempts = 0
        try {
            pairQrSecret("test-secret") { attempts++; throw IOException("offline") }
            fail("Expected network failure")
        } catch (_: IOException) { }
        assertEquals(1, attempts)
    }
    @Test fun secondAuthenticationFailureIsReported() = runBlocking {
        var attempts = 0
        try {
            pairQrSecret("test-secret") { attempts++; throw AdbInvalidPairingCodeException() }
            fail("Expected authentication failure")
        } catch (_: AdbInvalidPairingCodeException) { }
        assertEquals(2, attempts)
    }
}
