package dev.remotecode.ui.chat

import dev.remotecode.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.data.SessionPlan

@Composable
fun PlanPanel(plan: SessionPlan?, running: Boolean, sessionId: String) {
    val items = plan?.items.orEmpty()
    if (items.isEmpty()) return
    val done = items.count { it.status == "completed" }
    if (done == items.size && !running) return
    var open by rememberSaveable(sessionId) { mutableStateOf(false) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val current = items.firstOrNull { it.status == "in_progress" }
    Surface(
        onClick = { haptics.context(); open = !open },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    progress = { done.toFloat() / items.size },
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.chat_plan_panel_004, done, items.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Text(
                    current?.let { it.activeForm ?: it.content } ?: if (done == items.size) stringResource(R.string.chat_plan_panel_003) else stringResource(R.string.chat_plan_panel_002, items.firstOrNull { it.status == "pending" }?.content.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(if (open) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, if (open) stringResource(R.string.chat_close) else stringResource(R.string.chat_open), Modifier.size(20.dp))
            }
            if (open) {
                Column(
                    Modifier.padding(top = 8.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                when (item.status) {
                                    "completed" -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.chat_completed), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    "in_progress" -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                    else -> Icon(Icons.Rounded.RadioButtonUnchecked, stringResource(R.string.chat_plan_panel_001), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(
                                if (item.status == "in_progress") item.activeForm ?: item.content else item.content,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (item.status == "in_progress") FontWeight.SemiBold else null,
                                textDecoration = if (item.status == "completed") TextDecoration.LineThrough else null,
                                color = if (item.status == "completed") MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}
