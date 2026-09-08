package io.github.togo3.scrcaster.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.togo3.scrcaster.R
import io.github.togo3.scrcaster.constants.UiSpacing
import io.github.togo3.scrcaster.models.DeviceShortcuts
import io.github.togo3.scrcaster.scaffolds.ReorderableList
import io.github.togo3.scrcaster.storage.Settings
import io.github.togo3.scrcaster.storage.Storage.quickDevices
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
fun ReorderDevicesScreen(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    val taskScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val qdBundleShared by quickDevices.bundleState.collectAsState()
    val qdBundleSharedLatest by rememberUpdatedState(qdBundleShared)
    var qdBundle by rememberSaveable(qdBundleShared) { mutableStateOf(qdBundleShared) }
    val qdBundleLatest by rememberUpdatedState(qdBundle)
    LaunchedEffect(qdBundleShared) {
        if (qdBundle != qdBundleShared) {
            qdBundle = qdBundleShared
        }
    }
    LaunchedEffect(qdBundle) {
        delay(Settings.BUNDLE_SAVE_DELAY)
        if (qdBundle != qdBundleSharedLatest) {
            quickDevices.saveBundle(qdBundle)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            taskScope.launch {
                quickDevices.saveBundle(qdBundleLatest)
            }
        }
    }

    var savedShortcuts by remember {
        mutableStateOf(DeviceShortcuts.unmarshalFrom(qdBundle.quickDevicesList))
    }
    LaunchedEffect(qdBundle.quickDevicesList) {
        savedShortcuts = DeviceShortcuts.unmarshalFrom(qdBundle.quickDevicesList)
    }
    LaunchedEffect(savedShortcuts) {
        val serialized = savedShortcuts.marshalToString()
        if (serialized != qdBundle.quickDevicesList) {
            qdBundle = qdBundle.copy(quickDevicesList = serialized)
        }
    }

    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.reorder_devices_title),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        ReorderableList(
            itemsProvider = {
                savedShortcuts.map { device ->
                    ReorderableList.Item(
                        id = device.id,
                        title = device.name.ifBlank { device.host },
                        subtitle = "${device.host}:${device.port}",
                    )
                }
            },
            onSettle = { fromIndex, toIndex ->
                savedShortcuts = savedShortcuts.move(fromIndex, toIndex)
            },
        ).invoke()
        Spacer(Modifier.height(UiSpacing.SheetBottom))
    }
}
