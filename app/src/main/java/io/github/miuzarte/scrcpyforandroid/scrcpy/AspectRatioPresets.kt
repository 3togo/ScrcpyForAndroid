package io.github.miuzarte.scrcpyforandroid.scrcpy

import io.github.miuzarte.scrcpy.core.AspectRatio

/**
 * Shared aspect-ratio presets and crop math, delegated to the :core module so the
 * desktop and Android frontends compute identical crops. The Android options UI
 * should build its aspect-ratio selector from [presets] and compute the crop string
 * with [cropForRatio].
 */
object ScrcpyAspectRatio {
    val presets: List<AspectRatio.Ratio> get() = AspectRatio.Ratio.entries
    fun targetRatio(mode: AspectRatio.Ratio, landscape: Boolean, custom: String = "") =
        AspectRatio.targetRatio(mode, landscape, custom)
    fun cropForRatio(naturalWidth: Int, naturalHeight: Int, landscape: Boolean, target: Double) =
        AspectRatio.cropForRatio(naturalWidth, naturalHeight, landscape, target)

    /**
     * Resolve a persisted ratio name (one of [AspectRatio.Ratio.name]) plus an optional custom
     * text into a target display ratio. Returns 0.0 (keep device ratio) for DEVICE or when the
     * name is unknown / the custom text is invalid. The base ratio is given for landscape; the
     * renderer re-orients it to the surface at draw time.
     */
    fun targetRatioFromName(name: String, custom: String): Double =
        runCatching { targetRatio(AspectRatio.Ratio.valueOf(name), landscape = true, custom) }
            .getOrDefault(0.0)
}
