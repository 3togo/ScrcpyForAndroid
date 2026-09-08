package io.github.miuzarte.scrcpyforandroid

import io.github.miuzarte.scrcpyforandroid.scrcpy.videoFitSize
import io.github.miuzarte.scrcpyforandroid.scrcpy.videoCrop
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFitTest {
    @Test
    fun cropRemovesWideSourceEdgesBeforeScaling() {
        val crop = videoCrop(1920, 864, 16.0 / 9.0)
        assertEquals(192, crop.x)
        assertEquals(0, crop.y)
        assertEquals(1536, crop.width)
        assertEquals(864, crop.height)
    }

    @Test
    fun shortEdgeFillsWithoutChangingAspectRatio() {
        val portrait = videoFitSize("CROP", 9f / 16f, 1920f, 1080f)
        assertEquals(1920f, portrait.width, 0.01f)
        assertEquals(9f / 16f, portrait.width / portrait.height, 0.0001f)

        val landscape = videoFitSize("CROP", 2f, 1920f, 1080f)
        assertEquals(1080f, landscape.height, 0.01f)
        assertEquals(2f, landscape.width / landscape.height, 0.0001f)
    }

    @Test
    fun longEdgeFillsItsMatchingContainerEdge() {
        val portrait = videoFitSize("LONG_EDGE", 9f / 16f, 1920f, 1080f)
        assertEquals(1080f, portrait.height, 0.01f)
        assertEquals(9f / 16f, portrait.width / portrait.height, 0.0001f)

        val landscape = videoFitSize("LONG_EDGE", 2f, 1920f, 1080f)
        assertEquals(1920f, landscape.width, 0.01f)
        assertEquals(2f, landscape.width / landscape.height, 0.0001f)
    }
}
