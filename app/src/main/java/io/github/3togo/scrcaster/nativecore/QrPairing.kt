package io.github.3togo.scrcaster.nativecore

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Accommodate QR scanners that retain Wi-Fi-style quotes around the password. */
internal suspend fun pairQrSecret(secret: String, attempt: suspend (String) -> Boolean): Boolean {
    try {
        return attempt(secret)
    } catch (error: Exception) {
        if (generateSequence<Throwable>(error) { it.cause }
                .none { it is AdbInvalidPairingCodeException }) throw error
        currentCoroutineContext().ensureActive()
        return attempt("\"$secret\"")
    }
}
