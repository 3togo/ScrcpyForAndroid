package io.github.miuzarte.scrcpyforandroid

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class TvPhoneKeyMappingsTest {
    @Test fun assignmentsSurviveSavingAndLoading() {
        val mappings = TvPhoneKeyMappings()
        mappings.assign(KeyEvent.KEYCODE_PROG_RED, TvPhoneAction.HOME)
        val restored = TvPhoneKeyMappings(mappings.saved())
        assertEquals(TvPhoneAction.HOME, restored[KeyEvent.KEYCODE_PROG_RED])
        assertNull(restored[KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE])
    }

    @Test fun reassignmentRemovesOldButtonAndReplacesConflictingAction() {
        val mappings = TvPhoneKeyMappings()
        mappings.assign(KeyEvent.KEYCODE_PROG_RED, TvPhoneAction.HOME)
        mappings.assign(KeyEvent.KEYCODE_PROG_GREEN, TvPhoneAction.HOME)
        assertNull(mappings[KeyEvent.KEYCODE_PROG_RED])
        mappings.assign(KeyEvent.KEYCODE_PROG_GREEN, TvPhoneAction.BACK)
        assertNull(mappings.keyFor(TvPhoneAction.HOME))
        assertEquals(TvPhoneAction.BACK, mappings[KeyEvent.KEYCODE_PROG_GREEN])
    }

    @Test fun corruptAndReservedSavedMappingsAreIgnored() {
        val mappings = TvPhoneKeyMappings(mapOf(
            "bad" to "HOME",
            KeyEvent.KEYCODE_BACK.toString() to "HOME",
            KeyEvent.KEYCODE_PROG_RED.toString() to "REMOVED_ACTION",
        ))
        assertTrue(mappings.saved().isEmpty())
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER)) {
            assertFalse(TvPhoneKeyMappings.canAssign(code))
        }
    }

    @Test fun resetRestoresDefaultInputHandling() {
        val mappings = TvPhoneKeyMappings()
        mappings.assign(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, TvPhoneAction.POINTER)
        mappings.clear()
        assertNull(mappings[KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE])
        assertTrue(mappings.saved().isEmpty())
    }
}
