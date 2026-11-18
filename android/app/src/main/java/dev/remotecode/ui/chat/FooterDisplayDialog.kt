package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import dev.remotecode.ui.common.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.remotecode.data.ChatFooterDisplay
import dev.remotecode.data.ChatFooterItem
import dev.remotecode.ui.common.hapticArea
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FooterDisplayDialog(initial: ChatFooterDisplay, saving: Boolean, onDismiss: () -> Unit, onApply: (ChatFooterDisplay) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val bounds = remember { mutableStateMapOf<ChatFooterItem, Rect>() }
    var dragging by remember { mutableStateOf<ChatFooterItem?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var waitingForLayout by remember { mutableStateOf(false) }

    fun finishDrag() { dragging = null; waitingForLayout = false }
    fun drag(item: ChatFooterItem, delta: Float) {
        if (dragging != item) return
        dragCenter += delta
        // 古い座標で連続移動しないよう、再配置を待つ。
        if (waitingForLayout) return
        val order = draft.orderedItems
        val from = order.indexOf(item)
        val to = when {
            delta > 0 -> order.indices.lastOrNull { it > from && (bounds[order[it]]?.center?.y ?: Float.POSITIVE_INFINITY) < dragCenter }
            delta < 0 -> order.indices.firstOrNull { it < from && (bounds[order[it]]?.center?.y ?: Float.NEGATIVE_INFINITY) > dragCenter }
            else -> null
        }
        if (to != null) {
            waitingForLayout = true
            draft = draft.move(item, to)
        }
    }

    AlertDialog(
        modifier = hapticArea(),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Visibility, null) },
        title = { Text(stringResource(R.string.chat_chat_screen_054)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_footer_display_dialog_014), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chat_footer_display_dialog_013), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = draft.copy(context = true, cache = true, usage = true, stats = true, git = true) }, enabled = !saving && dragging == null) { Text(stringResource(R.string.chat_footer_display_dialog_012)) }
                    TextButton(onClick = { draft = draft.copy(context = false, cache = false, usage = false, stats = false, git = false) }, enabled = !saving && dragging == null) { Text(stringResource(R.string.chat_chat_screen_053)) }
                }
                draft.orderedItems.forEachIndexed { index, item ->
                    key(item) {
                        val option = footerOption(item)
                        Box(Modifier.zIndex(if (dragging == item) 1f else 0f).onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            val next = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
                            val previous = bounds.put(item, next)
                            if (dragging == item && previous != null && previous.top != next.top) waitingForLayout = false
                        }) {
                            FooterDisplayOption(
                                item = item, title = option.title, detail = option.detail, icon = option.icon,
                                checked = draft.isVisible(item), enabled = !saving,
                                canMoveUp = index > 0, canMoveDown = index < draft.orderedItems.lastIndex,
                                dragging = dragging == item,
                                modifier = Modifier.offset {
                                    IntOffset(0, if (dragging == item) (dragCenter - (bounds[item]?.center?.y ?: dragCenter)).roundToInt() else 0)
                                },
                                onChange = { draft = draft.withVisibility(item, it) },
                                onMove = { delta -> draft = draft.move(item, draft.orderedItems.indexOf(item) + delta) },
                                onDragStart = { bounds[item]?.let { dragging = item; dragCenter = it.center.y; waitingForLayout = false } },
                                onDrag = { drag(item, it) },
                                onDragEnd = ::finishDrag,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }, enabled = !saving && dragging == null) { Text(if (saving) stringResource(R.string.chat_footer_display_dialog_011) else stringResource(R.string.chat_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

private data class FooterOption(val title: String, val detail: String, val icon: ImageVector)

private fun footerOption(item: ChatFooterItem): FooterOption = when (item) {
    ChatFooterItem.CONTEXT -> FooterOption(appString(R.string.chat_context), appString(R.string.chat_footer_display_dialog_010), Icons.Rounded.DataUsage)
    ChatFooterItem.CACHE -> FooterOption(appString(R.string.chat_cache_sheet_020), appString(R.string.chat_footer_display_dialog_009), Icons.Rounded.Schedule)
    ChatFooterItem.USAGE -> FooterOption(appString(R.string.chat_footer_display_dialog_008), appString(R.string.chat_footer_display_dialog_007), Icons.Rounded.Speed)
    ChatFooterItem.STATS -> FooterOption(appString(R.string.chat_footer_display_dialog_006), appString(R.string.chat_footer_display_dialog_005), Icons.AutoMirrored.Rounded.ReceiptLong)
    ChatFooterItem.GIT -> FooterOption("Git", appString(R.string.chat_footer_display_dialog_004), Icons.Rounded.AccountTree)
}

@Composable
private fun FooterDisplayOption(
    item: ChatFooterItem, title: String, detail: String, icon: ImageVector, checked: Boolean, enabled: Boolean,
    canMoveUp: Boolean, canMoveDown: Boolean, dragging: Boolean, modifier: Modifier,
    onChange: (Boolean) -> Unit, onMove: (Int) -> Unit,
    onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit,
) {
    var moveMenu by remember { mutableStateOf(false) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onDragEnd)
    Row(
        modifier.fillMaxWidth()
            .background(if (dragging) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            IconButton(
                onClick = { moveMenu = true }, enabled = enabled,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = appString(R.string.chat_footer_display_dialog_003, title)
                    customActions = buildList {
                        if (enabled && canMoveUp) add(CustomAccessibilityAction(appString(R.string.chat_footer_display_dialog_002)) { onMove(-1); true })
                        if (enabled && canMoveDown) add(CustomAccessibilityAction(appString(R.string.chat_footer_display_dialog_001)) { onMove(1); true })
                    }
                }.pointerInput(item, enabled) {
                    if (enabled) detectDragGestures(
                        onDragStart = { start() },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, amount -> change.consume(); move(amount.y) }
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.DragHandle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_footer_display_dialog_002)) }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, enabled = enabled && canMoveUp, onClick = { moveMenu = false; onMove(-1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_footer_display_dialog_001)) }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, enabled = enabled && canMoveDown, onClick = { moveMenu = false; onMove(1) })
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
