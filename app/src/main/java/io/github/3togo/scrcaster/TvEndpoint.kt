package io.github.3togo.scrcaster

/** Accept the complete address shown by Android Wireless debugging. */
internal fun parseTvEndpoint(text: String): Pair<String, Int>? {
    val match = Regex("^([a-zA-Z0-9._-]+|\\[[0-9a-fA-F:%._-]+\\]):([0-9]{1,5})$").matchEntire(text.trim()) ?: return null
    val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return match.groupValues[1].removeSurrounding("[", "]") to port
}
