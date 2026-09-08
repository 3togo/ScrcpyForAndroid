package io.github.3togo.scrcaster

/** wm size reports the effective display size in its natural orientation. */
internal fun phoneAspectRatio(output: String, streamWidth: Int, streamHeight: Int): Float? {
    val sizes = Regex("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)")
        .findAll(output).map { it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull() }.toList()
    val (width, height) = sizes.lastOrNull() ?: return null
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val short = minOf(width, height).toFloat()
    val long = maxOf(width, height).toFloat()
    return if (streamWidth >= streamHeight) long / short else short / long
}
