package io.github.3togo.scrcaster
import org.junit.Assert.*
import org.junit.Test
class TvAspectRatioTest {
    @Test fun phoneRatioIsIndependentOfEncodedResolution() {
        assertEquals(1080f / 2400f, phoneAspectRatio("Physical size: 1080x2400", 720, 1280)!!, 0.0001f)
        assertEquals(2400f / 1080f, phoneAspectRatio("Physical size: 1080x2400", 1280, 720)!!, 0.0001f)
    }
    @Test fun overrideSizeAndInvalidOutput() {
        assertEquals(0.5f, phoneAspectRatio("Physical size: 1080x2400\nOverride size: 1000x2000", 720, 1280)!!, 0.0001f)
        assertNull(phoneAspectRatio("unavailable", 720, 1280))
        assertNull(phoneAspectRatio("Physical size: 0x2400", 720, 1280))
    }
}
