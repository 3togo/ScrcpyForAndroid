package io.github.miuzarte.scrcpyforandroid

import android.view.KeyEvent
import android.view.MotionEvent
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Normalized coordinates keep the cursor aligned with the video at either display aspect. */
data class TvPointer(val enabled: Boolean = false, val dragging: Boolean = false,
    val x: Float = .5f, val y: Float = .5f) {
    fun move(dx: Float, dy: Float) = copy(x = (x + dx).coerceIn(0f, 1f), y = (y + dy).coerceIn(0f, 1f))
    fun pixels(width: Int, height: Int) =
        (x * (width - 1).coerceAtLeast(0)).roundToInt() to
            (y * (height - 1).coerceAtLeast(0)).roundToInt()
}

interface TvRemoteInput {
    val size: Pair<Int, Int>
    suspend fun key(action: Int, code: Int, repeat: Int = 0, metaState: Int = 0)
    suspend fun touch(action: Int, x: Int, y: Int, width: Int, height: Int)
}

class ScrcpyTvRemoteInput(private val scrcpy: Scrcpy) : TvRemoteInput {
    override val size: Pair<Int, Int> get() = scrcpy.currentSessionState.value
        ?.let { it.width to it.height } ?: error("No active session")
    override suspend fun key(action: Int, code: Int, repeat: Int, metaState: Int) {
        check(scrcpy.isControlAvailable) { "Phone control unavailable" }
        scrcpy.injectKeycode(action, code, repeat, metaState)
        check(scrcpy.isControlAvailable) { "Phone control disconnected" }
    }
    override suspend fun touch(action: Int, x: Int, y: Int, width: Int, height: Int) {
        check(scrcpy.isControlAvailable) { "Phone control unavailable" }
        scrcpy.injectTouch(action, -2L, x, y, width, height,
            if (action == MotionEvent.ACTION_UP) 0f else 1f)
        check(scrcpy.isControlAvailable) { "Phone control disconnected" }
    }
}

/** Called only by the activity's ordered input queue. */
class TvRemoteController(private val input: TvRemoteInput) {
    private val mutablePointer = MutableStateFlow(TvPointer())
    val pointer = mutablePointer.asStateFlow()
    private val pressedKeys = mutableSetOf<Int>()
    private var touchActive = false

    suspend fun setPointer(enabled: Boolean) {
        release()
        mutablePointer.value = pointer.value.copy(enabled = enabled)
    }

    suspend fun toggleDrag() {
        if (pointer.value.dragging) release() else {
            mutablePointer.value = pointer.value.copy(enabled = true)
            touch(MotionEvent.ACTION_DOWN)
            mutablePointer.value = pointer.value.copy(dragging = true)
        }
    }

    suspend fun key(action: Int, code: Int, repeat: Int = 0, metaState: Int = 0, canceled: Boolean = false) {
        if (pointer.value.enabled && code in pointerKeys) {
            if (canceled) { release(); return }
            if (action != KeyEvent.ACTION_DOWN) return
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (repeat != 0) return
                if (pointer.value.dragging) release() else {
                    touch(MotionEvent.ACTION_DOWN)
                    touch(MotionEvent.ACTION_UP)
                }
            } else {
                val step = if (repeat > 8) .035f else .012f
                mutablePointer.value = pointer.value.move(
                    when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -step; KeyEvent.KEYCODE_DPAD_RIGHT -> step; else -> 0f },
                    when (code) { KeyEvent.KEYCODE_DPAD_UP -> -step; KeyEvent.KEYCODE_DPAD_DOWN -> step; else -> 0f },
                )
                if (pointer.value.dragging) touch(MotionEvent.ACTION_MOVE)
            }
        } else if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            if (action == KeyEvent.ACTION_DOWN) pressedKeys.add(code)
            else if (code !in pressedKeys) return
            input.key(action, code, repeat, metaState)
            if (action == KeyEvent.ACTION_UP) pressedKeys.remove(code)
        }
    }

    suspend fun perform(action: TvPhoneAction) {
        action.keycode?.let { press(it); return }
        when (action) {
            TvPhoneAction.POINTER -> setPointer(!pointer.value.enabled)
            TvPhoneAction.DRAG -> toggleDrag()
            else -> Unit
        }
    }

    suspend fun press(code: Int) {
        release()
        pressedKeys.add(code)
        input.key(KeyEvent.ACTION_DOWN, code)
        input.key(KeyEvent.ACTION_UP, code)
        pressedKeys.remove(code)
    }

    suspend fun release() {
        try {
            if (touchActive) touch(MotionEvent.ACTION_UP)
            for (code in pressedKeys.toList()) {
                input.key(KeyEvent.ACTION_UP, code)
                pressedKeys.remove(code)
            }
        } finally {
            mutablePointer.value = pointer.value.copy(dragging = false)
        }
    }

    private suspend fun touch(action: Int) {
        if (action != MotionEvent.ACTION_DOWN && !touchActive) return
        // The server rejects events tagged with stale video dimensions after rotation.
        val size = input.size.also { require(it.first > 0 && it.second > 0) }
        if (action == MotionEvent.ACTION_DOWN) touchActive = true
        val (x, y) = pointer.value.pixels(size.first, size.second)
        input.touch(action, x, y, size.first, size.second)
        if (action == MotionEvent.ACTION_UP) touchActive = false
    }

    companion object {
        val pointerKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
    }
}
