package io.github.miuzarte.scrcpyforandroid.scrcpy

import io.github.togo3.scrcaster.core.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted aspect-ratio option is a [AspectRatio.Ratio] name plus free text; both are read
 * back from storage and must never throw while the renderer resolves them.
 */
class AspectRatioPresetsTest {

    @Test
    fun presetsExposeEveryCoreRatio() {
        assertEquals(AspectRatio.Ratio.entries.toList(), ScrcpyAspectRatio.presets)
    }

    @Test
    fun persistedNamesResolveToDisplayRatios() {
        assertEquals(0.0, ScrcpyAspectRatio.targetRatioFromName("DEVICE", ""), 0.0)
        assertEquals(1.0, ScrcpyAspectRatio.targetRatioFromName("SQUARE", ""), 1e-9)
        assertEquals(16.0 / 9, ScrcpyAspectRatio.targetRatioFromName("WIDE", ""), 1e-9)
        assertEquals(4.0 / 3, ScrcpyAspectRatio.targetRatioFromName("CLASSIC", ""), 1e-9)
        assertEquals(21.0 / 9, ScrcpyAspectRatio.targetRatioFromName("CUSTOM", "21:9"), 1e-9)
    }

    @Test
    fun unknownNamesAndBrokenCustomTextFallBackToZero() {
        assertEquals(0.0, ScrcpyAspectRatio.targetRatioFromName("", ""), 0.0)
        assertEquals(0.0, ScrcpyAspectRatio.targetRatioFromName("not-a-ratio", ""), 0.0)
        assertEquals(0.0, ScrcpyAspectRatio.targetRatioFromName("CUSTOM", ""), 0.0)
        assertEquals(0.0, ScrcpyAspectRatio.targetRatioFromName("CUSTOM", "wide"), 0.0)
    }

    @Test
    fun customTextIsOnlyReadForTheCustomRatio() {
        // A preset name ignores the free-text field entirely, so a broken value cannot break it.
        assertEquals(16.0 / 9, ScrcpyAspectRatio.targetRatioFromName("WIDE", "ignored"), 1e-9)
    }

    @Test
    fun cropStringsStayEvenAndInsideTheSource() {
        val crop = ScrcpyAspectRatio.cropForRatio(1080, 1920, landscape = false, target = 16.0 / 9)
        val (width, height, x, y) = crop.split(':').map { it.toInt() }
        assertTrue("crop must stay even for scrcpy: $crop", width % 2 == 0 && height % 2 == 0)
        assertTrue("crop must fit the source: $crop", width <= 1080 && height <= 1920)
        assertTrue("crop must be inside the source: $crop", x + width <= 1080 && y + height <= 1920)
    }
}
