package dev.remotecode.ui.desktop

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.DesktopDisplay
import dev.remotecode.data.DesktopPreferences
import dev.remotecode.data.DesktopResolution
import java.text.NumberFormat

@Composable
internal fun DesktopOptionsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.desktop_options), style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
private fun OptionGroup(@StringRes title: Int, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OptionSwitch(@StringRes label: Int, icon: ImageVector, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(stringResource(label), Modifier.weight(1f))
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun OptionAction(@StringRes label: Int, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label))
    }
}

@Composable
private fun <T> Choices(@StringRes title: Int, choices: List<T>, selected: T, label: @Composable (T) -> String,
                        enabled: Boolean = true, available: (T) -> Boolean = { true }, change: (T) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(selected = choice == selected, onClick = { change(choice) }, enabled = enabled && available(choice),
                label = { Text(label(choice)) }, leadingIcon = if (choice == selected) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                } else null)
        }
    }
}

@Composable
internal fun DesktopInputOptions(
    prefs: DesktopPreferences, supportsTouchpad: Boolean, touchpad: Boolean, supportsClipboard: Boolean, knownCapabilities: Boolean, clipboardError: String?,
    change: (DesktopPreferences) -> Unit, keyboardEnabled: Boolean, onKeyboard: () -> Unit, onHelp: () -> Unit,
) {
    OptionGroup(R.string.desktop_input_options, Icons.Rounded.Mouse) {
        OptionSwitch(R.string.desktop_control, Icons.Rounded.TouchApp, prefs.control) { change(prefs.copy(control = it)) }
        Choices(R.string.desktop_pointer_mode, listOf(true, false), touchpad,
            { stringResource(if (it) R.string.desktop_touchpad else R.string.desktop_direct) },
            enabled = supportsTouchpad) { change(prefs.copy(touchpad = it)) }
        if (touchpad) {
            Choices(R.string.desktop_pointer_speed, DesktopPreferences.pointerSpeeds, prefs.pointerSpeed,
                { "${NumberFormat.getNumberInstance().format(it)}×" }) { change(prefs.copy(pointerSpeed = it)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionAction(R.string.desktop_keyboard, Icons.Rounded.Keyboard, keyboardEnabled, onKeyboard)
            OptionAction(R.string.desktop_help, Icons.Rounded.HelpOutline, action = onHelp)
        }
        HorizontalDivider()
        OptionSwitch(R.string.desktop_clipboard, Icons.Rounded.ContentPaste, prefs.clipboard && supportsClipboard, supportsClipboard) {
            change(prefs.copy(clipboard = it))
        }
        Detail(stringResource(R.string.desktop_clipboard_hint))
        clipboardError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (knownCapabilities && !supportsTouchpad) Detail(stringResource(R.string.desktop_upgrade_options))
    }
}

@Composable
internal fun DesktopDisplayOptions(
    display: String, monitors: List<DesktopDisplay>, error: String?, onSelect: (String) -> Unit,
    onRefresh: () -> Unit, zoom: Float, onZoomBy: (Float) -> Unit, onFit: () -> Unit,
) {
    OptionGroup(R.string.desktop_display, Icons.Rounded.DesktopWindows) {
        var expanded by remember { mutableStateOf(false) }
        val selected = monitors.firstOrNull { it.id == display }
        val selectedLabel = when {
            display == "all" -> stringResource(R.string.desktop_all_displays)
            selected?.primary == true -> stringResource(R.string.desktop_primary_display, selected.name)
            else -> selected?.name ?: display
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedLabel, Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.desktop_all_displays)) },
                        onClick = { expanded = false; onSelect("all") }, leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) })
                    monitors.forEach { monitor ->
                        DropdownMenuItem(text = {
                            Text(if (monitor.primary) stringResource(R.string.desktop_primary_display, monitor.name) else monitor.name)
                        }, onClick = { expanded = false; onSelect(monitor.id) }, leadingIcon = { Icon(Icons.Rounded.Monitor, null) })
                    }
                }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.desktop_refresh_displays)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text(stringResource(R.string.desktop_zoom), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onZoomBy(0.5f) }, enabled = zoom > 1f) { Icon(Icons.Rounded.ZoomOut, stringResource(R.string.desktop_zoom_out)) }
            Text("${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(zoom)}×")
            IconButton(onClick = { onZoomBy(2f) }, enabled = zoom < 32f) { Icon(Icons.Rounded.ZoomIn, stringResource(R.string.desktop_zoom_in)) }
            OptionAction(R.string.desktop_fit, Icons.Rounded.FitScreen, action = onFit)
        }
    }
}

@Composable
internal fun DesktopVideoOptions(
    prefs: DesktopPreferences, change: (DesktopPreferences) -> Unit, width: Int?, height: Int?, receivedFps: Float?,
    captures: List<String>, capture: String, activeCapture: String?, codecs: List<String>, codec: String,
    resolution: DesktopResolution, supportsNative: Boolean, supportsBitrate: Boolean, autoKbps: Int,
) {
    OptionGroup(R.string.desktop_video_options, Icons.Rounded.Videocam) {
        Choices(R.string.desktop_refresh_rate, DesktopPreferences.frameRates, prefs.fps, { "$it FPS" }) { change(prefs.copy(fps = it)) }
        Detail(if (receivedFps == null) stringResource(R.string.desktop_fps_warmup) else stringResource(R.string.desktop_received_fps, receivedFps))
        Choices(R.string.desktop_bitrate_target, DesktopPreferences.bitrateOptions, prefs.bitrateMbps, {
            when (it) {
                0 -> if (supportsBitrate && prefs.bitrateMbps == 0) stringResource(R.string.desktop_auto_bitrate, autoKbps / 1000f)
                    else stringResource(R.string.desktop_automatic)
                -1 -> stringResource(R.string.desktop_bitrate_unlimited)
                else -> "$it Mbps"
            }
        }, enabled = supportsBitrate) { change(prefs.copy(bitrateMbps = it)) }
        Detail(stringResource(if (supportsBitrate) R.string.desktop_bitrate_hint else R.string.desktop_upgrade_options))
        OptionSwitch(R.string.desktop_debug_overlay, Icons.Rounded.Speed, prefs.debugOverlay) { change(prefs.copy(debugOverlay = it)) }
        Detail(stringResource(R.string.desktop_debug_hint))
        Text(stringResource(R.string.desktop_graphs), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to R.string.desktop_graph_fps, 2 to R.string.desktop_graph_network, 4 to R.string.desktop_graph_timing).forEach { (bit, label) ->
                FilterChip(selected = (prefs.graphMask and bit) != 0,
                    onClick = { change(prefs.copy(graphMask = prefs.graphMask xor bit)) }, enabled = prefs.debugOverlay,
                    label = { Text(stringResource(label)) })
            }
        }
        HorizontalDivider()
        Choices(R.string.desktop_quality, DesktopResolution.entries, resolution, { stringResource(when (it) {
            DesktopResolution.Standard -> R.string.desktop_quality_standard
            DesktopResolution.High -> R.string.desktop_quality_high
            DesktopResolution.Native -> R.string.desktop_quality_native
        }) }, available = { it != DesktopResolution.Native || supportsNative }) { change(prefs.copy(resolution = it)) }
        Detail(stringResource(when (resolution) {
            DesktopResolution.Standard -> R.string.desktop_dpi_standard
            DesktopResolution.High -> R.string.desktop_dpi_high
            DesktopResolution.Native -> R.string.desktop_dpi_native
        }))
        if (!supportsNative) Detail(stringResource(R.string.desktop_native_unavailable))
        if (width != null && height != null) Detail(stringResource(R.string.desktop_frame_dimensions, width, height))
        HorizontalDivider()
        Choices(R.string.desktop_capture_method, captures, capture, { if (it == "auto") stringResource(R.string.desktop_automatic) else it.uppercase() }) {
            change(prefs.copy(capture = it))
        }
        Detail(stringResource(when (capture) {
            "dxgi" -> R.string.desktop_capture_dxgi
            "gdi" -> R.string.desktop_capture_gdi
            else -> R.string.desktop_capture_auto
        }))
        if (capture == "auto" && activeCapture != null) Detail(stringResource(R.string.desktop_active_capture, activeCapture.uppercase()))
        HorizontalDivider()
        Choices(R.string.desktop_codec, codecs, codec, { it.uppercase() }) { change(prefs.copy(codec = it)) }
        Detail(stringResource(if (codec == "png") R.string.desktop_codec_png else R.string.desktop_codec_jpeg))
    }
}
