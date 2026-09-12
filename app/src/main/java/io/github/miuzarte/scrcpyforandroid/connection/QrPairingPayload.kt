package io.github.miuzarte.scrcpyforandroid.connection

import java.security.SecureRandom

/**
 * Single source of truth for the scrcpy ADB wireless-pairing QR payload format.
 * The `WIFI:T:ADB;S:<name>;P:<secret>;;` shape is duplicated in the desktop pairing tool and
 * asserted by tests, so it lives here instead of being inlined in a controller.
 *
 * @return the QR `name` (service name) and `secret` (pairing code), plus the ready-to-render payload.
 */
internal data class AdbQrPairing(val name: String, val secret: String, val payload: String)

private const val ADB_QR_PREFIX = "WIFI:T:ADB;S:"
/*
 * Keep both random fields at 10 characters. This is the shape used by Android's ADB QR flow
 * (`studio-<RANDOM-10>` plus a 10-character secret), and it is important for scan reliability:
 * replacing these values with 32-character UUIDs nearly doubled the payload and produced a much
 * denser QR code. After the TV UI began displaying that bitmap at 220dp, a real Android 15 phone
 * could not scan/pair from it, although manual address pairing still worked. The same phone paired
 * successfully with this shorter payload from the desktop test UI. Do not lengthen these fields
 * without testing a QR rendered at the TV's actual on-screen size on physical hardware.
 */
private const val ADB_QR_RANDOM_LENGTH = 10
private const val ADB_QR_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private val adbQrRandom = SecureRandom()

private fun randomAdbQrValue(): String = buildString(ADB_QR_RANDOM_LENGTH) {
    repeat(ADB_QR_RANDOM_LENGTH) {
        append(ADB_QR_ALPHABET[adbQrRandom.nextInt(ADB_QR_ALPHABET.length)])
    }
}

internal fun buildAdbQrPairing(): AdbQrPairing {
    val name = "studio-" + randomAdbQrValue()
    val secret = randomAdbQrValue()
    return AdbQrPairing(name, secret, "$ADB_QR_PREFIX$name;P:$secret;;")
}
