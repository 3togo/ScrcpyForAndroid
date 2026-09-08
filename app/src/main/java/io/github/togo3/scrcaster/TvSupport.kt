package io.github.togo3.scrcaster

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

internal fun Context.isTelevision(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
