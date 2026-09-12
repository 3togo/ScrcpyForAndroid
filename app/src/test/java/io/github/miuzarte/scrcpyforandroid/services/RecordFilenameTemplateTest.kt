package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Codec
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording filename template is user-authored text that ends up as a real file path, so its
 * placeholder expansion, unknown-key handling and trimming behaviour are pinned here.
 */
class RecordFilenameTemplateTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 12, 13, 5, 7)

    private val session = Scrcpy.Session.SessionInfo(
        deviceName = "Pixel 9",
        codecId = Codec.H264.id,
        codec = Codec.H264,
        width = 1080,
        height = 2400,
        audioCodec = Codec.OPUS,
        controlEnabled = true,
        host = "192.168.1.42",
        port = 5555,
    )

    private fun resolve(template: String) = RecordFilenameTemplate.resolve(template, session, now)

    @Test
    fun blankTemplateStaysBlank() {
        assertEquals("", resolve(""))
        assertEquals("", resolve("   "))
    }

    @Test
    fun dateAndTimePlaceholdersAreZeroPadded() {
        assertEquals("2026 09 12 13 05 07", resolve("${'$'}{YYYY} ${'$'}{MM} ${'$'}{DD} ${'$'}{HH} ${'$'}{mm} ${'$'}{SS}"))
        assertEquals("26 9 12 13 5 7", resolve("${'$'}{YY} ${'$'}{M} ${'$'}{D} ${'$'}{H} ${'$'}{m} ${'$'}{S}"))
        // 13:00 in 12-hour form is 01 pm.
        assertEquals("01 1", resolve("${'$'}{hh} ${'$'}{h}"))
    }

    @Test
    fun sessionPlaceholdersComeFromTheLiveSession() {
        assertEquals(
            "Pixel 9-192.168.1.42-5555-h264-opus-1080x2400",
            resolve(
                "${'$'}{deviceName}-${'$'}{deviceIp}-${'$'}{devicePort}-" +
                    "${'$'}{videoCodec}-${'$'}{audioCodec}-${'$'}{width}x${'$'}{height}",
            ),
        )
    }

    @Test
    fun missingAudioCodecFallsBackToUnknown() {
        val noAudio = session.copy(audioCodec = null)
        assertEquals(
            "unknown",
            RecordFilenameTemplate.resolve("${'$'}{audioCodec}", noAudio, now),
        )
    }

    @Test
    fun unknownAndMalformedPlaceholdersArePassedThrough() {
        // Literal text that is not a placeholder survives untouched.
        assertEquals("${'$'}{nope}", resolve("${'$'}{nope}"))
        assertEquals("${'$'}{not a key}", resolve("${'$'}{not a key}"))
        // An unterminated placeholder is kept verbatim instead of swallowing the rest.
        assertEquals("${'$'}{YYYY", resolve("${'$'}{YYYY"))
        assertEquals("a${'$'}{b}c", resolve("a${'$'}{b}c"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("rec 2026", resolve("  rec \${YYYY}  \t"))
    }

    @Test
    fun everyTemplateEntryExpands() {
        val failures = RecordFilenameTemplate.entries
            .filter { it.isTemplate }
            .filter { resolve(it.value) == it.value }
        assertTrue("placeholders that did not expand: $failures", failures.isEmpty())
    }
}
