package io.github.miuzarte.scrcpyforandroid.connection

/**
 * Receiver-side QR handoff.
 *
 * The TV receiver has no camera and mDNS does not cross routers, so wireless pairing cannot
 * discover a phone on another subnet. Instead the TV runs a tiny unicast server and encodes its
 * own address in a QR; the phone pushes its Wireless debugging `host:port` back over TCP, which
 * routers do forward.
 *
 * Two encodings share one payload:
 *  - `http://host:port/token` — universal; any camera opens the receiver's fallback web page.
 *    That page immediately hands off to [HANDOFF_SCHEME] when the phone has ScrCaster installed.
 *  - `scrcaster://connect?h=..&p=..&t=..` — opens ScrCaster directly so it can auto-send.
 *
 * Pure Kotlin so the URL shapes are unit-tested without Android.
 */
internal data class HandoffTarget(val host: String, val port: Int, val token: String) {

    /** QR text shown for the universal/browser path. */
    val payload: String = "http://${if (':' in host) "[$host]" else host}:$port/$token"

    /** Custom-scheme deep link that opens ScrCaster itself. */
    val appUri: String =
        "$HANDOFF_SCHEME://$HANDOFF_HOST?h=$host&p=$port&t=$token"
}

internal const val HANDOFF_SCHEME = "scrcaster"
internal const val HANDOFF_HOST = "connect"

/**
 * Parses either a receiver handoff URL or the custom-scheme deep link. Returns null for anything
 * else so it can safely run before the generic `host:port` address parser.
 */
internal fun parseHandoffTarget(raw: String): HandoffTarget? {
    val text = raw.trim()
    text.removePrefixIgnoreCase("http://")?.let { return parseHttpHandoff(it) }
    text.removePrefixIgnoreCase("$HANDOFF_SCHEME://")?.let { return parseAppHandoff(it) }
    return null
}

private fun parseHttpHandoff(rest: String): HandoffTarget? {
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val authority = rest.substring(0, slash)
    // Token is the first path segment; ignore any later path/query/fragment.
    val token = rest.substring(slash + 1).substringBefore('/').substringBefore('?').substringBefore('#')
    if (token.isEmpty()) return null

    val host: String
    val portText: String
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0 || !authority.startsWith(":", close + 1)) return null
        host = authority.substring(1, close)
        portText = authority.substring(close + 2)
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon <= 0) return null
        host = authority.substring(0, colon)
        portText = authority.substring(colon + 1)
    }
    return buildHandoff(host, portText, token)
}

private fun parseAppHandoff(rest: String): HandoffTarget? {
    val host = rest.substringBefore('?')
    if (!host.equals(HANDOFF_HOST, ignoreCase = true)) return null
    val query = rest.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    val fields = query.split('&').mapNotNull { pair ->
        val equals = pair.indexOf('=')
        if (equals <= 0) null
        else pair.substring(0, equals).lowercase() to decodeComponent(pair.substring(equals + 1))
    }.toMap()
    return buildHandoff(fields["h"].orEmpty(), fields["p"].orEmpty(), fields["t"].orEmpty())
}

private fun buildHandoff(host: String, portText: String, token: String): HandoffTarget? {
    val port = portText.toIntOrNull() ?: return null
    if (host.isEmpty() || host.any(Char::isWhitespace)) return null
    if (token.isEmpty()) return null
    if (port !in 1..65535) return null
    return HandoffTarget(host, port, token)
}

private fun decodeComponent(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

private fun String.removePrefixIgnoreCase(prefix: String): String? =
    if (regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) substring(prefix.length) else null
