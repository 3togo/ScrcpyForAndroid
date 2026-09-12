package io.github.miuzarte.scrcpyforandroid

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPolicyTest {
    // A 1260x2856 phone: the ratio that made the main interface ignore rotation.
    private val tallPhone = 2856f / 1260f

    @Test fun tallPhoneStaysPortraitUntilTheUserAllowsLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            mainUiRequestedOrientation(tallPhone, allowLandscapeOnTallPhones = false),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainUiRequestedOrientation(tallPhone, allowLandscapeOnTallPhones = true),
        )
    }

    @Test fun exactlySixteenByNineIsNotConsideredTall() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainUiRequestedOrientation(16f / 9f, allowLandscapeOnTallPhones = false),
        )
    }

    @Test fun squareAndWideDisplaysAlwaysFollowTheSystem() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainUiRequestedOrientation(1f, allowLandscapeOnTallPhones = false),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainUiRequestedOrientation(4f / 3f, allowLandscapeOnTallPhones = false),
        )
    }
}
