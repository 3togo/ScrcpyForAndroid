package io.github.miuzarte.scrcpyforandroid.connection

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.scrcpy.ScrcpyAspectRatio
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import io.github.miuzarte.scrcpyforandroid.ui.createThemeController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val hasDevice = state.preferences.lastEndpoint != null || state.streaming
    val keys = buildList {
        if (hasDevice) add("reconnect")
        add("qr"); add("methods"); add("settings")
        if (state.streaming) add("disconnect")
        if (state.busy) add("cancel")
    }
    val focus = rememberFocusChain(keys, remote)
    var returnFocus by remember { mutableStateOf(if (hasDevice) "reconnect" else "qr") }
    LaunchedEffect(state.dialog, state.busy) {
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
                        Text(stringResource(if (hasDevice) R.string.connection_last_device else R.string.connection_add_device),
                            style = MaterialTheme.typography.headlineSmall)
                        state.preferences.lastEndpoint?.let {
                            Text(it.toString(), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hasDevice) ActionButton(
                            stringResource(if (state.streaming) R.string.tv_resume else R.string.tv_reconnect),
                            focus.modifier("reconnect"), enabled = !state.busy,
                        ) { returnFocus = "reconnect"; controller.reconnect() }
                        ActionButton(stringResource(R.string.tv_qr_start), focus.modifier("qr"), enabled = !state.busy) {
                            returnFocus = "qr"; controller.showDialog(ConnectionDialog.QR)
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
                            returnFocus = "reconnect"; controller.disconnect()
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
        ConnectionDialog.METHODS -> listOf("code", "address", "back")
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
            val first = if (kind == ConnectionDialog.QR) "back" else keys.first()
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            var initialFocusDelivered by remember { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier
                .fillMaxWidth(if (remote) .84f else .94f)
                .heightIn(max = maxDialogHeight)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (kind) {
                        ConnectionDialog.QR -> R.string.tv_qr_pair
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
                        }
                        ConnectionDialog.QR -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            val options = state.preferences.playback
                            Text(stringResource(R.string.connection_settings_help))
                            ActionButton(stringResource(if (options.audio) R.string.connection_audio_on else R.string.connection_audio_off), focus.modifier("audio")) {
                                controller.setPlayback(options.copy(audio = !options.audio))
                            }
                            ActionButton(fillLabel(options.renderFit), focus.modifier("fill")) {
                                val modes = listOf("FIT", "STRETCH", "CROP", "LONG_EDGE")
                                controller.setPlayback(options.copy(renderFit = modes[(modes.indexOf(options.renderFit) + 1) % modes.size]))
                            }
                            ActionButton(ratioLabel(options), focus.modifier("ratio")) {
                                val modes = ScrcpyAspectRatio.presets
                                controller.setPlayback(options.copy(aspectRatio = modes[(modes.indexOfFirst { it.name == options.aspectRatio } + 1) % modes.size].name))
                            }
                            if (custom) InputField(options.customRatio, { controller.setPlayback(options.copy(customRatio = it)) },
                                stringResource(R.string.scrcpyopt_aspect_ratio_custom), focus, "custom")
                        }
                        else -> Unit
                    }
                    if (kind != ConnectionDialog.QR) ActionButton(stringResource(R.string.tv_back), focus.modifier("back"), onClick = controller::dismissDialog)
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
private fun QrImage(payload: String, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, payload) {
        value = if (payload.isEmpty()) null else withContext(Dispatchers.Default) {
            val size = 480
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.tv_qr_pair), Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator()
    }
}

@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) colors.primary else colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (focused) colors.primary.copy(alpha = .15f) else colors.surface,
            contentColor = colors.onSurface,
        ), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InputField(value: String, onValueChange: (String) -> Unit, label: String, focus: FocusChain,
    key: String, numeric: Boolean = false, enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(!focus.remote) }
    var activatingKey by remember { mutableStateOf<Key?>(null) }
    // State-based fields support keeping the keyboard closed while navigating with a remote.
    val textState = remember { TextFieldState(value) }
    val latestValue by rememberUpdatedState(value)
    val updateValue by rememberUpdatedState(onValueChange)
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.distinctUntilChanged().collect {
            if (it != latestValue) updateValue(it)
        }
    }
    OutlinedTextField(state = textState, label = { Text(label) }, lineLimits = TextFieldLineLimits.SingleLine, enabled = enabled,
        modifier = focus.modifier(key).fillMaxWidth()
            .onFocusChanged { if (!it.isFocused && focus.remote) editing = false }
            .onPreviewKeyEvent {
                if (focus.remote && (it.key == Key.DirectionDown || it.key == Key.DirectionUp)) {
                    if (it.type == KeyEventType.KeyDown) {
                        editing = false
                        keyboard?.hide()
                        focus.move(key, if (it.key == Key.DirectionDown) 1 else -1)
                    }
                    true
                } else if (focus.remote && (it.key == Key.DirectionCenter || it.key == Key.Enter) &&
                    (!editing || activatingKey == it.key)) {
                    // Turning this option on starts an input session even if focus was already held.
                    // Consume both halves of the activation key so its release cannot submit Done.
                    if (it.type == KeyEventType.KeyDown) { activatingKey = it.key; editing = true }
                    else if (it.type == KeyEventType.KeyUp) activatingKey = null
                    true
                } else false
            },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
            imeAction = ImeAction.Done, showKeyboardOnFocus = editing),
        onKeyboardAction = { editing = !focus.remote; keyboard?.hide(); focus.move(key, 1) })
}

private class FocusTarget {
    val requester = FocusRequester()
    val placed = CompletableDeferred<Unit>()
}

private class FocusChain(val keys: List<String>, val targets: Map<String, FocusTarget>, val remote: Boolean) {
    private val order = RemoteFocusOrder(keys)
    private val requesters = targets.mapValues { it.value.requester }
    suspend fun requestWhenPlaced(key: String) {
        val target = targets[key] ?: targets.getValue(keys.first())
        // BoxWithConstraints and Dialog subcompose after the parent's effects run.
        target.placed.await()
        target.requester.requestFocus()
    }
    fun request(key: String) { (requesters[key] ?: requesters.getValue(keys.first())).requestFocus() }
    fun move(key: String, direction: Int) = request(order.move(key, direction))
    fun modifier(key: String): Modifier = Modifier.testTag(key)
        .onGloballyPositioned { targets.getValue(key).placed.complete(Unit) }
        .focusRequester(requesters.getValue(key)).focusProperties {
        if (remote) {
            up = requesters.getValue(order.previous(key))
            down = requesters.getValue(order.next(key))
            previous = up; next = down
        }
    }
}

@Composable
private fun rememberFocusChain(keys: List<String>, remote: Boolean): FocusChain {
    val pool = remember { mutableMapOf<String, FocusTarget>() }
    return FocusChain(keys, keys.associateWith { pool.getOrPut(it) { FocusTarget() } }, remote)
}

@Composable
private fun fillLabel(mode: String) = stringResource(R.string.tv_fullscreen_fill, stringResource(when (mode) {
    "STRETCH" -> R.string.scrcpyopt_render_fit_stretch
    "CROP" -> R.string.scrcpyopt_render_fit_crop
    "LONG_EDGE" -> R.string.scrcpyopt_render_fit_long_edge
    else -> R.string.scrcpyopt_render_fit_fit
}))

@Composable
private fun ratioLabel(options: PlaybackPreferences) = stringResource(R.string.tv_video_ratio,
    ScrcpyAspectRatio.presets.firstOrNull { it.name == options.aspectRatio }?.toString().orEmpty())

@Composable
private fun Status(state: ConnectionUiState) {
    val message = stringResource(when (state.status) {
        ConnectionStatus.READY -> when (state.dialog) {
            ConnectionDialog.NONE -> R.string.tv_home_ready
            ConnectionDialog.QR -> R.string.connection_waiting_phone
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
        ConnectionStatus.ERROR -> R.string.tv_error
    }, state.error.orEmpty())
    Text(message, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (state.status in listOf(ConnectionStatus.ERROR, ConnectionStatus.INVALID_ADDRESS,
                ConnectionStatus.INVALID_CODE, ConnectionStatus.QR_TIMEOUT, ConnectionStatus.PAIR_FAILED)) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
}
