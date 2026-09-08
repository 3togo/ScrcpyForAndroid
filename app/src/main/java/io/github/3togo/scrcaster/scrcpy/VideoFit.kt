package io.github.3togo.scrcaster.scrcpy

import kotlin.math.roundToInt

/** Display size for an aspect-preserving video inside (or overflowing) a container. */
data class VideoFitSize(val width: Float, val height: Float)

/** Centred source rectangle selected before it is scaled to the receiver. */
data class VideoCrop(val x: Int, val y: Int, val width: Int, val height: Int)

fun videoCrop(sourceWidth: Int, sourceHeight: Int, targetAspect: Double): VideoCrop {
    if (sourceWidth <= 0 || sourceHeight <= 0) return VideoCrop(0, 0, 0, 0)
    if (!(targetAspect > 0.0) || !targetAspect.isFinite()) {
        return VideoCrop(0, 0, sourceWidth, sourceHeight)
    }
    return if (targetAspect >= sourceWidth.toDouble() / sourceHeight) {
        val height = (sourceWidth / targetAspect).roundToInt().coerceIn(2, sourceHeight)
        VideoCrop(0, (sourceHeight - height) / 2, sourceWidth, height)
    } else {
        val width = (sourceHeight * targetAspect).roundToInt().coerceIn(2, sourceWidth)
        VideoCrop((sourceWidth - width) / 2, 0, width, sourceHeight)
    }
}

/**
 * Calculate the displayed video size without allowing UI constraints to distort it.
 *
 * - FIT: the whole picture is visible.
 * - CROP: the source's short edge fills the matching container edge.
 * - LONG_EDGE: the source's long edge fills the matching container edge.
 * - STRETCH: the container is filled without preserving aspect ratio.
 */
fun videoFitSize(
    mode: String,
    pictureAspect: Float,
    containerWidth: Float,
    containerHeight: Float,
): VideoFitSize {
    if (pictureAspect <= 0f || !pictureAspect.isFinite() ||
        containerWidth <= 0f || containerHeight <= 0f
    ) {
        return VideoFitSize(0f, 0f)
    }

    return when (mode) {
        "STRETCH" -> VideoFitSize(containerWidth, containerHeight)
        "CROP" -> {
            val scale = maxOf(containerWidth / pictureAspect, containerHeight)
            VideoFitSize(pictureAspect * scale, scale)
        }
        "LONG_EDGE" -> if (pictureAspect >= 1f) {
            VideoFitSize(containerWidth, containerWidth / pictureAspect)
        } else {
            VideoFitSize(containerHeight * pictureAspect, containerHeight)
        }
        else -> {
            val height = minOf(containerWidth / pictureAspect, containerHeight)
            VideoFitSize(pictureAspect * height, height)
        }
    }
}
