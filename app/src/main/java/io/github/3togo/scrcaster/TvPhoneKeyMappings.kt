package io.github.3togo.scrcaster

import android.view.KeyEvent

/** These actions apply only while this app is controlling the phone. */
enum class TvPhoneAction(val label: Int, val keycode: Int? = null) {
    BACK(R.string.tv_phone_back, KeyEvent.KEYCODE_BACK),
    HOME(R.string.tv_phone_home, KeyEvent.KEYCODE_HOME),
    RECENTS(R.string.tv_phone_recents, KeyEvent.KEYCODE_APP_SWITCH),
    PLAY_PAUSE(R.string.tv_phone_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    POINTER(R.string.tv_toggle_pointer),
    DRAG(R.string.tv_toggle_drag),
}

class TvPhoneKeyMappings(saved: Map<String, *> = emptyMap<String, String>()) {
    private val bindings = mutableMapOf<Int, TvPhoneAction>()

    init {
        for ((key, value) in saved) {
            val code = key.toIntOrNull() ?: continue
            val action = TvPhoneAction.entries.firstOrNull { it.name == value } ?: continue
            if (canAssign(code)) assign(code, action)
        }
    }

    operator fun get(code: Int): TvPhoneAction? = bindings[code]
    fun keyFor(action: TvPhoneAction): Int? = bindings.entries.firstOrNull { it.value == action }?.key
    fun saved(): Map<String, String> = bindings.mapKeys { it.key.toString() }.mapValues { it.value.name }
    fun clear() = bindings.clear()

    fun assign(code: Int, action: TvPhoneAction) {
        require(canAssign(code))
        bindings.entries.removeAll { it.value == action }
        bindings[code] = action
    }

    companion object {
        // Preserve navigation, the receiver escape/menu buttons, and system controls.
        fun canAssign(code: Int): Boolean = code > KeyEvent.KEYCODE_UNKNOWN && code !in reserved
        private val reserved = TvRemoteController.pointerKeys + setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_APP_SWITCH,
        )
    }
}
