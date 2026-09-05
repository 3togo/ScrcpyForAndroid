package io.github.miuzarte.scrcpyforandroid

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvRemoteControllerTest {
    private class Input : TvRemoteInput {
        override var size = 1080 to 2400
        val keys = mutableListOf<List<Int>>()
        val touches = mutableListOf<List<Int>>()
        override suspend fun key(action: Int, code: Int, repeat: Int, metaState: Int) {
            keys.add(listOf(action, code, repeat, metaState))
        }
        override suspend fun touch(action: Int, x: Int, y: Int, width: Int, height: Int) {
            touches.add(listOf(action, x, y, width, height))
        }
    }

    @Test fun pointerClampsAndMapsToStreamRegardlessOfDisplayAspect() {
        val pointer = TvPointer().move(10f, -10f)
        assertEquals(1079 to 0, pointer.pixels(1080, 2400))
        assertEquals(2399 to 0, pointer.pixels(2400, 1080))
        assertEquals(0 to 0, pointer.pixels(1, 1))
    }

    @Test fun heldOkProducesOnlyOneTap() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.setPointer(true)
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        remote.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), input.touches.map { it[0] })
        assertTrue(input.keys.isEmpty())
    }

    @Test fun dragMovesAndReleasesWithCurrentDimensionsOnRotation() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.toggleDrag()
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        input.size = 2400 to 1080
        remote.release()
        assertEquals(listOf(0, 2, 1), input.touches.map { it[0] })
        assertTrue(input.touches[1][1] > input.touches[0][1])
        assertEquals(listOf(2400, 1080), input.touches.last().takeLast(2))
        assertFalse(remote.pointer.value.dragging)
    }

    @Test fun switchingModeReleasesHeldNavigationKeysAndIgnoresLateUp() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, metaState = 1)
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat = 2)
        remote.setPointer(true)
        remote.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(0, 0, 1), input.keys.map { it[0] })
        assertEquals(2, input.keys[1][2])
        assertEquals(1, input.keys[0][3])
    }

    @Test fun okEndsDragWithoutExtraTapAndReleaseIsIdempotent() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.toggleDrag()
        remote.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        remote.release()
        assertEquals(listOf(0, 1), input.touches.map { it[0] })
        assertFalse(remote.pointer.value.dragging)
    }

    @Test fun canceledPointerEventReleasesDrag() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.toggleDrag()
        remote.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, canceled = true)
        assertEquals(listOf(0, 1), input.touches.map { it[0] })
        assertFalse(remote.pointer.value.dragging)
    }

    @Test fun phoneHomeReleasesDragAndSendsCompleteKeyPress() = runBlocking {
        val input = Input()
        val remote = TvRemoteController(input)
        remote.toggleDrag()
        remote.press(KeyEvent.KEYCODE_HOME)
        assertEquals(listOf(0, 1), input.touches.map { it[0] })
        assertEquals(listOf(0, 1), input.keys.map { it[0] })
        assertTrue(input.keys.all { it[1] == KeyEvent.KEYCODE_HOME })
        assertFalse(remote.pointer.value.dragging)
    }

}
