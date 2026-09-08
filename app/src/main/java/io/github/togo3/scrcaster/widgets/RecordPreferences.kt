package io.github.togo3.scrcaster.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.togo3.scrcaster.R
import io.github.togo3.scrcaster.pages.LocalRootNavigator
import io.github.togo3.scrcaster.pages.RootScreen
import io.github.togo3.scrcaster.scrcpy.ClientOptions.RecordFormat
import io.github.togo3.scrcaster.services.NativeRecordingSupport
import io.github.togo3.scrcaster.ui.contextClick
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles

@Composable
fun RecordPreferences(
    profileId: String,
    recordFilenameTemplate: String,
    recordFormat: String,
    enabled: Boolean,
    onRecordFormatChange: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    val navigator = LocalRootNavigator.current
    val supportedFormats = remember { NativeRecordingSupport.supportedFormats }

    val formatItems = supportedFormats.map {
        if (it == RecordFormat.AUTO) stringResource(R.string.text_auto)
        else it.string
    }

    val formatIndex = remember(recordFormat) {
        supportedFormats.indexOfFirst { it.string == recordFormat }
            .coerceAtLeast(0)
    }
    val currentTemplateSummary = recordFilenameTemplate
        .ifBlank { stringResource(R.string.text_off) }

    ArrowPreference(
        title = stringResource(R.string.record_title),
        summary = "--record",
        enabled = enabled,
        onClick = {
            haptic.contextClick()
            navigator.push(RootScreen.ScrcpyOptionRecord(profileId))
        },
        endActions = {
            Text(
                text = currentTemplateSummary,
                color = colorScheme.onSurfaceVariantActions,
                fontSize = textStyles.body2.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )

    OverlayDropdownPreference(
        title = stringResource(R.string.record_format),
        summary = "--record-format",
        items = formatItems,
        selectedIndex = formatIndex,
        enabled = enabled,
        onSelectedIndexChange = { index ->
            onRecordFormatChange(supportedFormats[index].string)
        },
    )
}
