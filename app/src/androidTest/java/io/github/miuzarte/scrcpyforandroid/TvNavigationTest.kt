package io.github.miuzarte.scrcpyforandroid

import android.view.KeyEvent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogWindow
import androidx.test.espresso.matcher.ViewMatchers.isRoot as isRootView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed as isViewDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.miuzarte.scrcpyforandroid.connection.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Remote-only tests of the shared screen, without a real phone or saved receiver preferences. */
@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Before fun launch() {
        // Drive animation frames explicitly: TV text-field focus/scroll animations can keep
        // the Compose idler pending even while the hardware UI responds normally.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                ConnectionController(scope, NavigationBackend(), object : ConnectionPreferencesStore {
                    override fun load() = ConnectionPreferences()
                    override fun save(preferences: ConnectionPreferences) = Unit
                })
            }
            MaterialTheme { ConnectionContent(controller, remote = true) }
        }
        settleFrames()
    }

    private fun dialogNode(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))
    private fun settleFrames() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
    private fun SemanticsNodeInteraction.key(key: Key): SemanticsNodeInteraction {
        // Semantics may have focus before Android grants focus to a new dialog window.
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
            onView(isRootView()).inRoot(isDialogWindow()).check(matches(isViewDisplayed()))
            settleFrames()
        }
        assertIsFocused()
        val code = when (key) {
            Key.DirectionDown -> KeyEvent.KEYCODE_DPAD_DOWN
            Key.DirectionUp -> KeyEvent.KEYCODE_DPAD_UP
            Key.DirectionCenter -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> error("Unsupported remote key: $key")
        }
        // Send through Android's window/input dispatch, as a real TV remote does.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        settleFrames()
        return this
    }

    @Test fun qrDialogCanReturnToHomeAction() {
        compose.onNodeWithTag("qr").assertIsFocused().performClick()
        dialogNode("back").assertExists().performClick()
        compose.onNodeWithTag("qr").assertIsFocused()
    }

    @Test fun manualFieldsLeadToAllActionsAndBack() {
        compose.onNodeWithTag("qr").key(Key.DirectionDown)
        compose.onNodeWithTag("methods").assertIsFocused().key(Key.DirectionCenter)
        dialogNode("code").assertIsFocused().key(Key.DirectionCenter)
        dialogNode("address").assertIsFocused().key(Key.DirectionDown)
        dialogNode("code").assertIsFocused().key(Key.DirectionDown)
        dialogNode("submit").assertIsFocused().key(Key.DirectionDown)
        dialogNode("qr").assertIsFocused().key(Key.DirectionDown)
        dialogNode("back").assertIsFocused().key(Key.DirectionCenter)
        compose.onNodeWithTag("methods").assertIsFocused()
    }

    @Test fun playbackSettingsAndCustomRatioAreReachable() {
        compose.onNodeWithTag("qr").key(Key.DirectionDown)
        compose.onNodeWithTag("methods").key(Key.DirectionDown)
        compose.onNodeWithTag("settings").assertIsFocused().key(Key.DirectionCenter)
        dialogNode("audio").assertIsFocused().key(Key.DirectionDown)
        dialogNode("fill").assertIsFocused().key(Key.DirectionDown)
        dialogNode("ratio").assertIsFocused()
        repeat(4) { dialogNode("ratio").key(Key.DirectionCenter) }
        dialogNode("ratio").key(Key.DirectionDown)
        dialogNode("custom").assertIsFocused().key(Key.DirectionDown)
        dialogNode("back").assertIsFocused().key(Key.DirectionCenter)
        compose.onNodeWithTag("settings").assertIsFocused()
    }

    @Test fun invalidAddressLeavesFormOpenForCorrection() {
        compose.onNodeWithTag("qr").key(Key.DirectionDown)
        compose.onNodeWithTag("methods").key(Key.DirectionCenter)
        dialogNode("code").key(Key.DirectionDown)
        dialogNode("address").key(Key.DirectionCenter)
        dialogNode("address").performTextInput("172.16.30.109")
        settleFrames()
        dialogNode("address").key(Key.DirectionDown)
        dialogNode("submit").key(Key.DirectionCenter)
        dialogNode("address").assertExists()
        dialogNode("back").assertIsEnabled()
    }

    @Test fun validAddressAndKeyboardDoneConnectTheEnteredEndpoint() {
        compose.onNodeWithTag("qr").key(Key.DirectionDown)
        compose.onNodeWithTag("methods").key(Key.DirectionCenter)
        dialogNode("code").key(Key.DirectionDown)
        dialogNode("address").key(Key.DirectionCenter)
        dialogNode("address").performTextInput("172.16.30.109:39691")
        settleFrames()
        dialogNode("address").performImeAction()
        settleFrames()
        dialogNode("submit").assertIsFocused().key(Key.DirectionCenter)
        compose.onNodeWithText("172.16.30.109:39691").assertExists()
        compose.onNodeWithTag("reconnect").assertExists()
    }

    private class NavigationBackend : ConnectionBackend {
        override fun isStreaming() = false
        override fun cancelPendingConnect() = Unit
        override suspend fun connect(endpoint: ConnectionEndpoint, preferences: PlaybackPreferences) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun pair(endpoint: ConnectionEndpoint, secret: String) = false
        override suspend fun findQrService(name: String): ConnectionEndpoint? = awaitCancellation()
        override suspend fun findConnection(host: String): ConnectionEndpoint? = null
    }
}
