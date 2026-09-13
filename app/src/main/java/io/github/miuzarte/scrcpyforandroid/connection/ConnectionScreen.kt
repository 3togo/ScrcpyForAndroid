package io.github.miuzarte.scrcpyforandroid.connection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import io.github.miuzarte.scrcpyforandroid.ui.createThemeController
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared Compose shell; TV chooses roomy spacing and explicit remote focus traversal. */
@Composable
internal fun ConnectionScreen(controller: ConnectionController, remote: Boolean) {
    val settings by Storage.appSettings.bundleState.collectAsState()
    val theme = remember(settings.themeBaseIndex, settings.monet, settings.monetSeedIndex,
        settings.monetPaletteStyle, settings.monetColorSpec) { settings.createThemeController() }
    MiuixTheme(controller = theme) {
        // Use the same user-selected palette as the phone UI, with keyboard-aware Material controls.
        val colors = MiuixTheme.colorScheme
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            primary = colors.primary, background = colors.background,
            surface = colors.surface, onSurface = colors.onSurface,
            onBackground = colors.onBackground, surfaceVariant = colors.surfaceContainer,
            onSurfaceVariant = colors.onSurfaceVariantSummary, outline = colors.outline,
        )) {
            ConnectionContent(controller, remote)
        }
    }
}

@Composable
internal fun ConnectionContent(controller: ConnectionController, remote: Boolean) {
    val state by controller.state.collectAsState()
    val rememberedDevices = state.preferences.rememberedEndpoints
    val keys = connectionHomeFocusKeys(rememberedDevices.size, state.streaming, state.busy)
    val focus = rememberFocusChain(keys, remote)
    var returnFocus by remember { mutableStateOf(if (rememberedDevices.isNotEmpty()) "device-0" else "qr") }
    LaunchedEffect(state.dialog, state.busy, rememberedDevices) {
        if (remote && state.dialog == ConnectionDialog.NONE) focus.requestWhenPlaced(if (state.busy) "cancel" else returnFocus)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (remote) 48.dp else 20.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.main_tab_devices), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.tv_home_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 680.dp
                val devicePanel: @Composable () -> Unit = {
                    Section(stringResource(R.string.main_tab_devices)) {
                        Text(stringResource(if (rememberedDevices.isNotEmpty()) R.string.connection_saved_devices else R.string.connection_add_device),
                            style = MaterialTheme.typography.headlineSmall)
                        rememberedDevices.forEachIndexed { index, endpoint ->
                            val selected = endpoint == state.preferences.lastEndpoint
                            val endpointLabel = if (selected) {
                                "$endpoint · ${stringResource(R.string.connection_current_device)}"
                            } else endpoint.toString()
                            Text(
                                endpointLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton(
                                    stringResource(
                                        if (selected && state.streaming) R.string.tv_resume
                                        else R.string.tv_connect_now,
                                    ),
                                    focus.modifier("device-$index").weight(1f),
                                    enabled = !state.busy,
                                ) {
                                    returnFocus = "device-$index"
                                    controller.reconnect(endpoint)
                                }
                                ActionButton(
                                    stringResource(R.string.connection_forget_device),
                                    focus.modifier("forget-$index").weight(1f),
                                    enabled = !state.busy && !(selected && state.streaming),
                                ) {
                                    returnFocus = "qr"
                                    controller.forget(endpoint)
                                }
                            }
                        }
                        if (rememberedDevices.isNotEmpty()) ActionButton(
                            stringResource(R.string.device_refresh),
                            focus.modifier("refresh"),
                            enabled = !state.busy,
                        ) {
                            returnFocus = "refresh"
                            controller.refreshDevices()
                        }
                        ActionButton(stringResource(R.string.tv_qr_start), focus.modifier("qr"), enabled = !state.busy) {
                            returnFocus = "qr"; controller.showDialog(ConnectionDialog.QR)
                        }
                        ActionButton(stringResource(R.string.tv_handoff_start), focus.modifier("handoff"), enabled = !state.busy) {
                            returnFocus = "handoff"; controller.showDialog(ConnectionDialog.HANDOFF)
                        }
                        ActionButton(stringResource(R.string.tv_manual_options), focus.modifier("methods"), enabled = !state.busy) {
                            returnFocus = "methods"; controller.showDialog(ConnectionDialog.METHODS)
                        }
                        if (state.busy) ActionButton(stringResource(R.string.button_cancel), focus.modifier("cancel")) { controller.back() }
                    }
                }
                val settingsPanel: @Composable () -> Unit = {
                    Section(stringResource(R.string.main_tab_settings)) {
                        Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.connection_playback), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(if (state.preferences.playback.audio) R.string.connection_audio_on else R.string.connection_audio_off))
                        Text(fillLabel(state.preferences.playback.renderFit), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ratioLabel(state.preferences.playback), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ActionButton(stringResource(R.string.connection_playback_settings), focus.modifier("settings"), enabled = !state.busy) {
                            returnFocus = "settings"; controller.showDialog(ConnectionDialog.PLAYBACK)
                        }
                        if (state.streaming) ActionButton(stringResource(R.string.tv_disconnect), focus.modifier("disconnect"), enabled = !state.busy) {
                            returnFocus = "device-0"; controller.disconnect()
                        }
                        Text(stringResource(R.string.connection_remote_help), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState())) { devicePanel() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { settingsPanel() }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    devicePanel(); settingsPanel()
                }
            }
            Spacer(Modifier.height(12.dp))
            Status(state)
        }
    }
    if (state.dialog != ConnectionDialog.NONE) ConnectionDialogContent(state, controller, remote)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ConnectionDialogContent(state: ConnectionUiState, controller: ConnectionController, remote: Boolean) {
    val kind = state.dialog
    val custom = state.preferences.playback.aspectRatio == "CUSTOM"
    val keys = when (kind) {
        ConnectionDialog.QR -> listOf("retry", "code", "back")
        ConnectionDialog.HANDOFF -> listOf("retry", "back")
        ConnectionDialog.METHODS -> listOf("code", "address", "handoff", "back")
        ConnectionDialog.ADDRESS -> listOf("address", "submit", "qr", "back")
        ConnectionDialog.CODE -> listOf("address", "code", "submit", "qr", "back")
        ConnectionDialog.PLAYBACK -> listOfNotNull("audio", "fill", "ratio", "custom".takeIf { custom }, "back")
        else -> emptyList()
    }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * .9f).dp
    // Keep one Android window when changing methods; only replace its Compose content.
    Dialog(onDismissRequest = controller::dismissDialog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        key(kind) {
            val focus = rememberFocusChain(keys, remote)
            val first = if (kind == ConnectionDialog.QR || kind == ConnectionDialog.HANDOFF) "back" else keys.first()
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            var initialFocusDelivered by remember { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier
                .fillMaxWidth(if (remote) .84f else .94f)
                .heightIn(max = maxDialogHeight)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (kind) {
                        ConnectionDialog.QR -> R.string.tv_qr_pair
                        ConnectionDialog.HANDOFF -> R.string.tv_handoff_title
                        ConnectionDialog.CODE -> R.string.tv_code_start
                        ConnectionDialog.ADDRESS -> R.string.tv_address_start
                        ConnectionDialog.PLAYBACK -> R.string.connection_playback_settings
                        else -> R.string.tv_manual_options
                    }), style = MaterialTheme.typography.headlineSmall)
                    when (kind) {
                        ConnectionDialog.METHODS -> {
                            Text(stringResource(R.string.tv_manual_help))
                            ActionButton(stringResource(R.string.tv_code_start), focus.modifier("code")) { controller.showDialog(ConnectionDialog.CODE) }
                            ActionButton(stringResource(R.string.tv_address_start), focus.modifier("address")) { controller.showDialog(ConnectionDialog.ADDRESS) }
                            ActionButton(stringResource(R.string.tv_handoff_start), focus.modifier("handoff")) { controller.showDialog(ConnectionDialog.HANDOFF) }
                        }
                        ConnectionDialog.QR -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                // QR scanability depends on this rendered size *and* the short RANDOM-10
                                // fields in QrPairingPayload.kt. A previous 32-character UUID payload
                                // became too dense at this 220dp TV size and failed with a physical
                                // Android 15 phone while manual address pairing still worked. Do not
                                // shrink this or lengthen the payload without testing the actual TV UI.
                                QrImage(state.qrPayload, Modifier.size(if (remote) 220.dp else 140.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.tv_qr_instructions))
                                    Status(state)
                                    ActionButton(stringResource(R.string.tv_qr_retry), focus.modifier("retry")) { controller.showDialog(ConnectionDialog.QR) }
                                    ActionButton(stringResource(R.string.tv_code_start), focus.modifier("code")) { controller.showDialog(ConnectionDialog.CODE) }
                                    ActionButton(stringResource(R.string.tv_back), focus.modifier("back"), onClick = controller::dismissDialog)
                                }
                            }
                        }
                        ConnectionDialog.HANDOFF -> {
                            // Receiver-side unicast handoff: mDNS does not cross routers, so the TV
                            // publishes its own server address and the phone pushes its address back.
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                QrImage(state.qrPayload, Modifier.size(if (remote) 220.dp else 140.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.tv_handoff_help))
                                    Status(state)
                                    ActionButton(stringResource(R.string.tv_qr_retry), focus.modifier("retry")) { controller.showDialog(ConnectionDialog.HANDOFF) }
                                    ActionButton(stringResource(R.string.tv_back), focus.modifier("back"), onClick = controller::dismissDialog)
                                }
                            }
                        }
                        ConnectionDialog.ADDRESS, ConnectionDialog.CODE -> {
                            val pairing = kind == ConnectionDialog.CODE
                            Text(stringResource(if (pairing) R.string.tv_code_help else R.string.tv_address_help))
                            InputField(state.address, controller::setAddress, stringResource(R.string.tv_full_address),
                                focus, "address", enabled = !state.busy)
                            if (pairing) InputField(state.pairingCode, controller::setPairingCode, stringResource(R.string.tv_pair_code),
                                focus, "code", numeric = true, enabled = !state.busy)
                            Status(state)
                            ActionButton(stringResource(if (pairing) R.string.tv_pair_and_connect else R.string.tv_connect_now),
                                focus.modifier("submit"), enabled = !state.busy, onClick = controller::submitAddress)
                            ActionButton(stringResource(R.string.tv_qr_instead), focus.modifier("qr"), enabled = !state.busy) {
                                controller.showDialog(ConnectionDialog.QR)
                            }
                        }
                        ConnectionDialog.PLAYBACK -> {
                            PlaybackSettingsBody(
                                options = state.preferences.playback,
                                setPlayback = controller::setPlayback,
                                focus = focus,
                            )
                        }
                        else -> Unit
                    }
                    if (kind != ConnectionDialog.QR && kind != ConnectionDialog.HANDOFF) ActionButton(stringResource(R.string.tv_back), focus.modifier("back"), onClick = controller::dismissDialog)
                }
            }
            LaunchedEffect(windowFocused) {
                if (remote && windowFocused && !initialFocusDelivered) {
                    focus.requestWhenPlaced(first)
                    initialFocusDelivered = true
                }
            }
            // Busy forms keep Back available even though the submitting control is disabled.
            LaunchedEffect(state.busy) {
                if (remote && state.busy && kind in listOf(ConnectionDialog.CODE, ConnectionDialog.ADDRESS)) focus.requestWhenPlaced("back")
            }
        }
    }
}

@Composable
private fun Status(state: ConnectionUiState) {
    val message = stringResource(when (state.status) {
        ConnectionStatus.READY -> when (state.dialog) {
            ConnectionDialog.NONE -> R.string.tv_home_ready
            ConnectionDialog.QR -> R.string.connection_waiting_phone
            ConnectionDialog.HANDOFF -> R.string.tv_handoff_waiting
            else -> R.string.tv_form_help
        }
        ConnectionStatus.CONNECTING -> R.string.tv_connecting
        ConnectionStatus.CONNECTED -> R.string.tv_connected
        ConnectionStatus.DISCONNECTED -> R.string.tv_disconnected
        ConnectionStatus.PAIRING -> R.string.tv_pairing
        ConnectionStatus.FINDING_PORT -> R.string.tv_qr_finding_port
        ConnectionStatus.PAIRED -> R.string.tv_paired
        ConnectionStatus.PAIR_REQUIRED -> R.string.tv_pair_required
        ConnectionStatus.INVALID_ADDRESS -> R.string.tv_invalid_full_address
        ConnectionStatus.INVALID_CODE -> R.string.tv_invalid_pairing
        ConnectionStatus.QR_TIMEOUT -> R.string.tv_qr_timeout
        ConnectionStatus.PAIR_FAILED -> R.string.tv_pair_failed
        ConnectionStatus.REFRESHING -> R.string.connection_refreshing_devices
        ConnectionStatus.DEVICES_REMOVED -> R.string.connection_devices_removed
        ConnectionStatus.ERROR -> R.string.tv_error
    }, state.error.orEmpty())
    StatusLine(message, state.status in listOf(ConnectionStatus.ERROR, ConnectionStatus.INVALID_ADDRESS,
        ConnectionStatus.INVALID_CODE, ConnectionStatus.QR_TIMEOUT, ConnectionStatus.PAIR_FAILED,
        ConnectionStatus.DEVICES_REMOVED))
}
