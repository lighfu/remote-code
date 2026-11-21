package dev.remotecode.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.AgentWorkflowCounts
import dev.remotecode.data.AgentWorkflowRow
import dev.remotecode.data.AgentWorkflowState
import dev.remotecode.data.Entry
import dev.remotecode.data.agentWorkflow
import dev.remotecode.ui.common.relativeTime
import dev.remotecode.ui.theme.Extra

private const val WorkflowMaxIndent = 5
private val WorkflowLaneSpacing = 11.dp
private val WorkflowFirstLane = 4.dp
private val WorkflowCardGap = 6.dp
private val WorkflowRowGap = 4.dp
private val WorkflowHeaderHeight = 54.dp
private val CollapsedWorkflowBranches = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@Composable
internal fun ClaudeWorkflowSheet(
    entries: List<Entry>,
    onDismiss: () -> Unit,
    onJumpToEntry: (String) -> Unit,
    canJumpToEntry: (String) -> Boolean = { true },
    loading: Boolean = false,
    refreshError: Boolean = false,
    onOpenAgent: (Entry) -> Unit,
) {
    val workflow = remember(entries) { agentWorkflow(entries) }
    val listState = rememberLazyListState()
    var collapsed by rememberSaveable(stateSaver = CollapsedWorkflowBranches) { mutableStateOf(emptySet<String>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = workflow.groups
    val selectedResult = remember(entries, selected) {
        selected?.let { id ->
            entries.lastOrNull { it.parent == id && it.kind == "assistant" && !it.text.isNullOrBlank() }?.text
        }
    }

    ChatEntrySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.chat_workflow_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (groups.isNotEmpty()) WorkflowSummary(workflow.counts)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_close)) }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else if (groups.isEmpty()) {
                Text(
                    stringResource(if (refreshError) R.string.chat_workflow_unavailable else R.string.chat_workflow_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "workflow-turn/${group.id}") {
                            Column(
                                Modifier.fillMaxWidth().padding(start = WorkflowFirstLane + WorkflowCardGap, top = 12.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    group.prompt?.text?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.chat_workflow_earlier),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.semantics { heading() },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (groups.size > 1) WorkflowSummary(group.counts)
                            }
                        }
                        items(group.rows(collapsed), key = { "workflow-node/${it.entry.id}" }) { row ->
                            val id = row.entry.id
                            val showDetails = selected == id
                            WorkflowNode(
                                row = row,
                                isCollapsed = id in collapsed,
                                showDetails = showDetails,
                                result = if (showDetails) selectedResult else null,
                                onToggleBranch = {
                                    collapsed = if (id in collapsed) collapsed - id else collapsed + id
                                },
                                onToggleDetails = { selected = if (showDetails) null else id },
                                onOpenAgent = if (row.entry.tool == "Agent" || row.entry.tool == "Task") {
                                    { onOpenAgent(row.entry) }
                                } else null,
                                onJump = if (showDetails) {
                                    listOf(id, row.rootId).firstOrNull(canJumpToEntry)?.let { target -> { onJumpToEntry(target) } }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowSummary(counts: AgentWorkflowCounts) {
    Text(
        stringResource(
            R.string.chat_workflow_summary,
            counts.total,
            counts.active,
            counts.completed + counts.failed + counts.cancelled,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WorkflowNode(
    row: AgentWorkflowRow,
    isCollapsed: Boolean,
    showDetails: Boolean,
    result: String?,
    onToggleBranch: () -> Unit,
    onToggleDetails: () -> Unit,
    onOpenAgent: (() -> Unit)?,
    onJump: (() -> Unit)?,
) {
    val entry = row.entry
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val isPhase = entry.tool == "WorkflowPhase"
    val hasChildren = row.childCount > 0
    val showStatus = !isPhase || row.state != AgentWorkflowState.UNKNOWN
    val showChildCount = hasChildren && (isPhase || isCollapsed)
    val showDepth = row.depth > WorkflowMaxIndent
    val childCountLabel = if (hasChildren) stringResource(R.string.chat_workflow_children, row.childCount) else null
    val stateColor = when (row.state) {
        AgentWorkflowState.ACTIVE -> colors.primary
        AgentWorkflowState.COMPLETED -> Extra.colors.success
        AgentWorkflowState.FAILED -> colors.error
        AgentWorkflowState.CANCELLED, AgentWorkflowState.UNKNOWN -> colors.onSurfaceVariant
    }
    val (icon, fallbackTitle) = when (entry.tool) {
        "Workflow" -> Icons.Rounded.AccountTree to R.string.chat_workflow_title
        "WorkflowPhase" -> Icons.Rounded.Layers to R.string.chat_workflow_phase
        else -> Icons.Rounded.SmartToy to R.string.chat_subagent
    }
    val title = entry.title?.takeIf { it.isNotBlank() } ?: stringResource(fallbackTitle)
    val preview = (entry.progress?.takeIf { it.isNotBlank() } ?: entry.output?.takeIf { it.isNotBlank() })
        ?.takeUnless { it == title }
    Surface(
        Modifier.fillMaxWidth().workflowConnectors(row, hasChildren && !isCollapsed, colors.outline.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(14.dp),
        color = when {
            entry.tool == "Workflow" -> colors.surfaceContainerHighest
            hasChildren || isPhase || showDetails -> colors.surfaceContainerHigh
            else -> colors.surfaceContainer
        },
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_workflow_details), onClick = onToggleDetails)
                        .heightIn(min = WorkflowHeaderHeight)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = stateColor,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(title, style = typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val labels = listOfNotNull(
                            if (showStatus) stringResource(workflowStateLabel(row)) else null,
                            if (showChildCount) childCountLabel else null,
                            if (showDepth) stringResource(R.string.chat_workflow_depth, row.depth + 1) else null,
                            entry.activityAt?.takeIf { it > 0 }?.let { relativeTime(it) },
                        )
                        if (labels.isNotEmpty()) Text(
                            labels.joinToString(" · "),
                            style = typography.labelSmall,
                            color = if (showStatus) stateColor else colors.onSurfaceVariant,
                        )
                        if (!showDetails && preview != null) {
                            Text(
                                preview,
                                style = typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = if (entry.tool == "Workflow") 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        entry.agentUsage?.let { WorkflowUsageLine(it) }
                    }
                }
                if (onOpenAgent != null) {
                    IconButton(onClick = onOpenAgent) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.chat_workflow_open_conversation, title))
                    }
                }
                if (childCountLabel != null) {
                    IconButton(onClick = onToggleBranch, modifier = Modifier.semantics { stateDescription = childCountLabel }) {
                        Icon(
                            if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                            stringResource(if (isCollapsed) R.string.chat_workflow_expand_branch else R.string.chat_workflow_collapse_branch),
                        )
                    }
                }
            }
            if (showDetails) {
                HorizontalDivider(color = colors.outlineVariant)
                WorkflowNodeDetails(entry, result, onJump)
            }
        }
    }
}

private fun Modifier.workflowConnectors(row: AgentWorkflowRow, childrenVisible: Boolean, color: Color): Modifier {
    val nodeLane = row.depth.coerceAtMost(WorkflowMaxIndent)
    val inset = WorkflowFirstLane + WorkflowLaneSpacing * nodeLane + WorkflowCardGap
    return drawBehind {
        if (row.depth == 0 && row.childCount == 0) return@drawBehind
        val parentLane = (row.depth - 1).coerceAtMost(WorkflowMaxIndent)
        val anchor = (WorkflowHeaderHeight / 2).toPx()
        val stroke = 1.5.dp.toPx()
        fun fromStart(px: Float) = if (layoutDirection == LayoutDirection.Ltr) px else size.width - px
        fun laneX(lane: Int) = fromStart((WorkflowFirstLane + WorkflowLaneSpacing * lane).toPx())

        // Clamped depths can share a rail. Draw it once at its longest extent.
        val continuingLanes = row.continuingAncestorDepths().fold(0) { lanes, depth ->
            lanes or (1 shl (depth - 1).coerceAtMost(WorkflowMaxIndent))
        }
        for (lane in 0..nodeLane) {
            val continues = continuingLanes and (1 shl lane) != 0 || (lane == parentLane && row.hasNextSibling)
            val top = if (continues || lane == parentLane) 0f else anchor
            val bottom = if (continues || (lane == nodeLane && childrenVisible)) size.height else anchor
            if (top < bottom) drawLine(color, Offset(laneX(lane), top), Offset(laneX(lane), bottom), stroke)
        }
        val nodeX = laneX(nodeLane)
        val incomingX = if (parentLane >= 0) laneX(parentLane) else nodeX
        drawLine(color, Offset(incomingX, anchor), Offset(fromStart(inset.toPx()), anchor), stroke)
        drawCircle(color, 2.dp.toPx(), Offset(nodeX, anchor))
    }.padding(start = inset, bottom = WorkflowRowGap)
}

private fun workflowStateLabel(row: AgentWorkflowRow): Int = when (row.state) {
    AgentWorkflowState.ACTIVE -> when (row.entry.status) {
        "waiting", "pending", "queued", "awaiting_input", "awaiting_approval", "paused" -> R.string.chat_waiting
        else -> R.string.chat_working
    }
    AgentWorkflowState.COMPLETED -> R.string.chat_completed
    AgentWorkflowState.FAILED -> R.string.chat_failed
    AgentWorkflowState.CANCELLED -> R.string.chat_entry_extras_012
    AgentWorkflowState.UNKNOWN -> R.string.chat_workflow_status_unknown
}

@Composable
private fun WorkflowNodeDetails(entry: Entry, result: String?, onJump: (() -> Unit)?) {
    val progress = entry.progress?.takeIf { it.isNotBlank() }
    val detail = entry.detail?.takeIf { it.isNotBlank() }
    val output = entry.output?.takeIf { it.isNotBlank() } ?: result
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress != null && progress != output && progress != detail) {
            Text(stringResource(R.string.chat_workflow_latest_activity), style = MaterialTheme.typography.labelMedium)
            Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LabeledToolCode(R.string.chat_entry_extras_009, detail, maxHeight = 200, wrapLines = true)
        LabeledToolCode(R.string.chat_entry_extras_005, output, maxHeight = 280, wrapLines = true)
        if (progress == null && detail == null && output == null) {
            Text(
                stringResource(R.string.chat_entry_extras_013),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onJump != null) {
            FilledTonalButton(onClick = onJump) { Text(stringResource(R.string.chat_workflow_show_entry)) }
        }
    }
}
