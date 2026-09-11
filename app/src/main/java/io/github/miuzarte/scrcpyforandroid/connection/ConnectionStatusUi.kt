package io.github.miuzarte.scrcpyforandroid.connection

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Shared status-line text used by both the TV [ConnectionScreen] and the phone connection UI.
 * Centralises the styling (body-medium, polite live region, error tint) so a connection status
 * reads the same in either layout. The richer phone [io.github.miuzarte.scrcpyforandroid.widgets.StatusCard]
 * keeps its own bespoke multi-card layout and only shares this primitive for the label rendering.
 */
@Composable
internal fun StatusLine(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
