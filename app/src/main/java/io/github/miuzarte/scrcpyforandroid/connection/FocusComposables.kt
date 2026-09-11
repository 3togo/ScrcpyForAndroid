package io.github.miuzarte.scrcpyforandroid.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reusable Compose building blocks for connection screens (TV + phone). Extracted from
 * [ConnectionScreen] so other screens can reuse the remote-aware focus traversal and inputs without
 * importing the full screen file.
 */

@Composable
internal fun ActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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
internal fun InputField(value: String, onValueChange: (String) -> Unit, label: String, focus: FocusChain,
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

internal class FocusTarget {
    val requester = FocusRequester()
    val placed = CompletableDeferred<Unit>()
}

internal class FocusChain(val keys: List<String>, val targets: Map<String, FocusTarget>, val remote: Boolean) {
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
internal fun rememberFocusChain(keys: List<String>, remote: Boolean): FocusChain {
    val pool = remember { mutableMapOf<String, FocusTarget>() }
    return FocusChain(keys, keys.associateWith { pool.getOrPut(it) { FocusTarget() } }, remote)
}
