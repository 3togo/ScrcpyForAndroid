package io.github.miuzarte.scrcpyforandroid.connection

/** A local IPv4 interface address together with its prefix length. */
internal data class LocalIpv4(val address: String, val prefixLength: Int)

/**
 * Picks which of this phone's IPv4 addresses to advertise to the TV.
 *
 * The mDNS-resolved host of the phone's own `_adb-tls-connect._tcp` service can be an IPv6 address;
 * sending that verbatim produced a malformed `host:port` that the receiver rejected. The receiver
 * reaches us over IPv4, so prefer an IPv4 on the receiver's subnet, then any private/site-local
 * IPv4, and only then whatever is left. Pure so it can be unit-tested offline.
 */
internal fun pickAdvertisedIpv4(candidates: List<LocalIpv4>, receiverHost: String): String? {
    if (candidates.isEmpty()) return null
    parseIpv4(receiverHost)?.let { receiver ->
        candidates.firstOrNull { sameSubnet(it, receiver) }?.let { return it.address }
    }
    return candidates.firstOrNull { isPrivateIpv4(it.address) }?.address ?: candidates.first().address
}

/** Formats `host:port`, bracketing IPv6 hosts so the receiver parses it. */
internal fun formatEndpoint(host: String, port: Int): String =
    if (':' in host) "[$host]:$port" else "$host:$port"

private fun parseIpv4(text: String): IntArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (index in 0 until 4) {
        val value = parts[index].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        octets[index] = value
    }
    return octets
}

private fun sameSubnet(candidate: LocalIpv4, receiver: IntArray): Boolean {
    val local = parseIpv4(candidate.address) ?: return false
    var remaining = candidate.prefixLength.coerceIn(0, 32)
    if (remaining == 0) return true
    for (index in 0 until 4) {
        val take = minOf(8, remaining)
        remaining -= take
        if (take == 0) break
        val mask = if (take == 8) 0xFF else (0xFF shl (8 - take)) and 0xFF
        if ((local[index] and mask) != (receiver[index] and mask)) return false
    }
    return true
}

private fun isPrivateIpv4(address: String): Boolean {
    val octets = parseIpv4(address) ?: return false
    return octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 169 && octets[1] == 254)
}
