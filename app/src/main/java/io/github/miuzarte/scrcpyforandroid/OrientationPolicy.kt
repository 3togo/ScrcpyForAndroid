package io.github.miuzarte.scrcpyforandroid

import android.content.pm.ActivityInfo

/** Phones taller than this keep the main interface portrait unless the user opts out. */
private const val PHONE_LANDSCAPE_LOCK_ASPECT_RATIO = 16f / 9f

/**
 * Orientation policy for the phone main interface, free of Activity/View access so the rule is
 * unit-testable.
 *
 * Tall phones (over 16:9) are locked to portrait because a sideways settings list is unusable
 * there, which also ignores the system rotation state. [allowLandscapeOnTallPhones] hands the
 * decision back to the system, so the phone rotates like any ordinary app.
 */
internal fun mainUiRequestedOrientation(
    displayAspectRatio: Float,
    allowLandscapeOnTallPhones: Boolean,
): Int =
    if (!allowLandscapeOnTallPhones && displayAspectRatio > PHONE_LANDSCAPE_LOCK_ASPECT_RATIO)
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    else
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
