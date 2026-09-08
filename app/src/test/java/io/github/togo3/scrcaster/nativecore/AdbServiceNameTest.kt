package io.github.togo3.scrcaster.nativecore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbServiceNameTest {
    @Test fun acceptsPlainAndLegacyQuotedNames() {
        assertTrue(matchesAdbServiceName("studio-abc123", "studio-abc123"))
        assertTrue(matchesAdbServiceName("\"studio-abc123\"", "studio-abc123"))
    }

    @Test fun rejectsOtherPhonesAndPartialMatches() {
        assertFalse(matchesAdbServiceName("studio-other", "studio-abc123"))
        assertFalse(matchesAdbServiceName("studio-abc123-extra", "studio-abc123"))
        assertFalse(matchesAdbServiceName("\"studio-abc123", "studio-abc123"))
        assertFalse(matchesAdbServiceName("\"\"studio-abc123\"\"", "studio-abc123"))
    }
}
