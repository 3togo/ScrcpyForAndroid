package io.github.miuzarte.scrcpyforandroid.password

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Names and passwords round-trip through text fields, saved files and injected key events, so the
 * control-character filtering rules are pinned here.
 */
class PasswordSanitizerTest {

    @Test
    fun nameDropsControlCharactersAndTrims() {
        assertEquals("Pixel 9", PasswordSanitizer.filterName("  Pixel 9\n\t"))
        assertEquals("ab", PasswordSanitizer.filterName("a\u0007b"))
        // Characterization: NUL is an ISO control character, so it is dropped rather than being
        // turned into the space that the later replace('\u0000', ' ') branch intends; that branch
        // is unreachable. Kept as-is until the sanitizer is deliberately fixed.
        assertEquals("ab", PasswordSanitizer.filterName("a\u0000b"))
        assertEquals("", PasswordSanitizer.filterName("\u0000\u0001\u001f"))
    }

    @Test
    fun passwordDropsControlCharactersWithoutTrimmingSpaces() {
        // Passwords keep significant leading/trailing spaces; names do not.
        assertEquals("  pass word  ", PasswordSanitizer.filterPassword("  pass word  "))
        assertEquals("ab", PasswordSanitizer.filterPassword("a\nb"))
        assertEquals("ab", PasswordSanitizer.filterPassword("a\u0000b"))
        assertEquals("", PasswordSanitizer.filterPassword("\r\n"))
    }

    @Test
    fun printableUnicodeSurvivesBothFilters() {
        val input = "pässwörd-日本語-1234!@#"
        assertEquals(input, PasswordSanitizer.filterName(input))
        assertEquals(input, PasswordSanitizer.filterPassword(input))
    }
}
