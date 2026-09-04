package io.github.miuzarte.scrcpyforandroid

import android.view.KeyEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    private fun launch() = ActivityScenario.launch(TvActivity::class.java)
    @Test fun qrDialogActionsAreReachableWithRemote() {
        launch().use {
            onView(withText(R.string.tv_qr_start)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withText(R.string.tv_back)).check(matches(hasFocus()))
            onView(withText(R.string.tv_back)).perform(pressKey(KeyEvent.KEYCODE_DPAD_UP))
            onView(withText(R.string.tv_code_start)).check(matches(hasFocus()))
            onView(withText(R.string.tv_code_start)).perform(pressKey(KeyEvent.KEYCODE_DPAD_UP))
            onView(withText(R.string.tv_qr_retry)).check(matches(hasFocus()))
        }
    }
    @Test fun manualFieldsLeadToAllActions() {
        launch().use {
            onView(withText(R.string.tv_qr_start)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withText(R.string.tv_manual_options)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withText(R.string.tv_code_start)).perform(pressKey(KeyEvent.KEYCODE_DPAD_CENTER))
            onView(withContentDescription(R.string.tv_full_address)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withContentDescription(R.string.tv_pair_code)).check(matches(hasFocus()))
            onView(withContentDescription(R.string.tv_pair_code)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withText(R.string.tv_pair_and_connect)).check(matches(hasFocus()))
            onView(withText(R.string.tv_pair_and_connect)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withText(R.string.tv_qr_instead)).check(matches(hasFocus()))
            onView(withText(R.string.tv_qr_instead)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
            onView(withText(R.string.tv_back)).check(matches(hasFocus()))
        }
    }
}
