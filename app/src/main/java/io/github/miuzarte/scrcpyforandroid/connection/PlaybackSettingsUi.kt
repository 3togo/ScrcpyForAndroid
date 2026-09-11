package io.github.miuzarte.scrcpyforandroid.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.scrcpy.ScrcpyAspectRatio

/**
 * Shared playback-settings body used by both the TV [ConnectionScreen] and the phone
 * connection UI. It depends only on [PlaybackPreferences] plus a [setPlayback] callback,
 * so it can render inside either layout. When [focus] is provided (TV / remote navigation)
 * each control participates in the surrounding [FocusChain]; when null, controls are plain
 * touch targets.
 */
@Composable
internal fun PlaybackSettingsBody(
    options: PlaybackPreferences,
    setPlayback: (PlaybackPreferences) -> Unit,
    focus: FocusChain? = null,
    modifier: Modifier = Modifier,
) {
    val custom = options.aspectRatio == "CUSTOM"
    val keys = listOfNotNull("audio", "fill", "ratio", "custom".takeIf { custom }, "back")
    val chain = focus ?: rememberFocusChain(keys, remote = false)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.connection_settings_help))
        ActionButton(stringResource(if (options.audio) R.string.connection_audio_on else R.string.connection_audio_off),
            chain.modifier("audio")) {
            setPlayback(options.copy(audio = !options.audio))
        }
        ActionButton(fillLabel(options.renderFit), chain.modifier("fill")) {
            val modes = listOf("FIT", "STRETCH", "CROP", "LONG_EDGE")
            setPlayback(options.copy(renderFit = modes[(modes.indexOf(options.renderFit) + 1) % modes.size]))
        }
        ActionButton(ratioLabel(options), chain.modifier("ratio")) {
            val modes = ScrcpyAspectRatio.presets
            setPlayback(options.copy(aspectRatio = modes[(modes.indexOfFirst { it.name == options.aspectRatio } + 1) % modes.size].name))
        }
        if (custom) InputField(options.customRatio, { setPlayback(options.copy(customRatio = it)) },
            stringResource(R.string.scrcpyopt_aspect_ratio_custom), chain, "custom")
    }
}

@Composable
internal fun fillLabel(mode: String) = stringResource(R.string.tv_fullscreen_fill, stringResource(when (mode) {
    "STRETCH" -> R.string.scrcpyopt_render_fit_stretch
    "CROP" -> R.string.scrcpyopt_render_fit_crop
    "LONG_EDGE" -> R.string.scrcpyopt_render_fit_long_edge
    else -> R.string.scrcpyopt_render_fit_fit
}))

@Composable
internal fun ratioLabel(options: PlaybackPreferences) = stringResource(R.string.tv_video_ratio,
    ScrcpyAspectRatio.presets.firstOrNull { it.name == options.aspectRatio }?.toString().orEmpty())
