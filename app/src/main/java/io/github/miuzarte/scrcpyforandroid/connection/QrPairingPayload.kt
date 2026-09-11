package io.github.miuzarte.scrcpyforandroid.connection

import java.util.UUID

/**
 * Single source of truth for the scrcpy ADB wireless-pairing QR payload format.
 * The `WIFI:T:ADB;S:<name>;P:<secret>;;` shape is duplicated in the desktop pairing tool and
 * asserted by tests, so it lives here instead of being inlined in a controller.
 *
 * @return the QR `name` (service name) and `secret` (pairing code), plus the ready-to-render payload.
 */
internal data class AdbQrPairing(val name: String, val secret: String, val payload: String)

private const val ADB_QR_PREFIX = "WIFI:T:ADB;S:"

internal fun buildAdbQrPairing(): AdbQrPairing {
    val name = "studio-" + UUID.randomUUID().toString().replace("-", "")
    val secret = UUID.randomUUID().toString().replace("-", "")
    return AdbQrPairing(name, secret, "$ADB_QR_PREFIX$name;P:$secret;;")
}
