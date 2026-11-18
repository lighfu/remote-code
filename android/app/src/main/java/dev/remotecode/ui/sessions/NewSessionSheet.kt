package dev.remotecode.ui.sessions

import dev.remotecode.R

import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedModeLabel
import dev.remotecode.i18n.localizedModeDescription
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.DirsInfo
import dev.remotecode.data.Providers
import dev.remotecode.data.SessionInfo
import dev.remotecode.ui.common.ModelPicker
import dev.remotecode.ui.common.modelLabel
import dev.remotecode.ui.common.relativeTime
import dev.remotecode.ui.common.shortPath
import kotlinx.coroutines.launch

private data class FolderChoice(val path: String, val name: String, val sub: String, val git: Boolean, val favorite: Boolean)

private fun baseName(path: String) = path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }

private fun parentOf(path: String): String {
    val p = path.replace('\\', '/').trimEnd('/')
    return if (p.contains('/')) p.substringBeforeLast('/') else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(provider: String, onDismiss: () -> Unit, onCreated: (SessionInfo) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val meta by repo.meta.collectAsState()
    val settings by repo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by rememberSaveable { mutableStateOf(provider) }
    var cwd by rememberSaveable { mutableStateOf(settings?.lastCwd ?: "") }
    var mode by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var effort by rememberSaveable { mutableStateOf<String?>(null) }
    var fastMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var text by dev.remotecode.ui.common.rememberDraft("new-session")
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    var dirs by remember { mutableStateOf<DirsInfo?>(null) }
    var showAll by remember { mutableStateOf(false) }

    suspend fun reloadDirs() {
        runCatching { repo.api.dirs() }.onSuccess { d ->
            dirs = d
            if (cwd.isBlank()) cwd = d.favorites.firstOrNull()?.path ?: d.recent.firstOrNull()?.path ?: d.home
        }
    }
    LaunchedEffect(Unit) { reloadDirs() }

    val favorites = dirs?.favorites.orEmpty().map { FolderChoice(it.path, it.name, shortPath(parentOf(it.path), 3), it.git, true) }
    val favKeys = favorites.map { it.path.lowercase() }.toSet()
    val recent = dirs?.recent.orEmpty()
        .filter { it.path.lowercase() !in favKeys }
        .map { r ->
            val who = r.providers.joinToString("/") { Providers.label(it) }
            FolderChoice(r.path, r.name, listOf(shortPath(parentOf(r.path), 3), who, relativeTime(r.lastUsed)).filter { it.isNotBlank() }.joinToString(" · "), r.git, false)
        }
    val choices = favorites + if (showAll) recent else recent.take(6)

    fun toggleFavorite(c: FolderChoice) {
        scope.launch {
            runCatching { repo.api.favorite(c.path, !c.favorite) }
            reloadDirs()
        }
    }

    val modes = meta?.providers?.get(selected)?.modes.orEmpty()
    val available = meta?.providers?.get(selected)?.available != false

    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.screen_new_session_sheet_001), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                Providers.all.forEachIndexed { i, p ->
                    ToggleButton(
                        checked = selected == p,
                        onCheckedChange = { selected = p; mode = ""; model = null; effort = null; fastMode = null },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            Providers.all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // 長い名前を一行に収めるため幅を広げる。
                        modifier = Modifier.weight(if (p == Providers.ANTIGRAVITY) 1.45f else 1f).semantics { role = Role.RadioButton },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) {
                        dev.remotecode.ui.common.BrandIcon(p, 18.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(Providers.label(p), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            if (!available) Text(stringResource(R.string.screen_new_session_sheet_002, Providers.label(selected)), color = MaterialTheme.colorScheme.error)

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                onClick = { picking = true },
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(MaterialShapes.Cookie4Sided.toShape()).background(MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onSecondary) }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.screen_new_session_sheet_003), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            if (cwd.isBlank()) appString(R.string.screen_new_session_sheet_004) else baseName(cwd),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (cwd.isNotBlank()) {
                            Text(cwd, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.screen_new_session_sheet_005)) }
                }
            }

            if (dirs == null) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else if (choices.isNotEmpty()) {
                Text(stringResource(R.string.screen_new_session_sheet_006), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    choices.forEachIndexed { i, c ->
                        val isSel = c.path.equals(cwd, ignoreCase = true)
                        SegmentedListItem(
                            onClick = { cwd = c.path },
                            shapes = ListItemDefaults.segmentedShapes(i, choices.size),
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            leadingContent = {
                                Icon(
                                    if (isSel) Icons.Rounded.CheckCircle else if (c.git) Icons.Rounded.AccountTree else Icons.Rounded.Folder,
                                    null,
                                    tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            supportingContent = { Text(c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { toggleFavorite(c) }) {
                                    Icon(
                                        if (c.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                        if (c.favorite) appString(R.string.screen_new_session_sheet_007) else appString(R.string.screen_new_session_sheet_008),
                                        tint = if (c.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        ) { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (!showAll && recent.size > 6) {
                    TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.screen_new_session_sheet_009, recent.size)) }
                }
            }

            val defaults = dev.remotecode.ui.common.pcDefaults(selected)
            val visibleModes = modes.filter { it.id.isNotBlank() }
            val shownMode = mode.ifBlank { defaults?.mode ?: "" }
            if (visibleModes.isNotEmpty()) {
                Text(stringResource(R.string.screen_new_session_sheet_010), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleModes.forEach { m ->
                        val v = dev.remotecode.ui.common.modeVisual(m.id)
                        FilterChip(
                            selected = shownMode == m.id,
                            onClick = { mode = m.id },
                            label = { Text(localizedModeLabel(m)) },
                            leadingIcon = { androidx.compose.material3.Icon(v.icon, null, tint = v.color, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                visibleModes.firstOrNull { it.id == shownMode }?.let {
                    Text(localizedModeDescription(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                }
            }

            FilledTonalButton(onClick = { pickingModel = true }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Memory, null, Modifier.size(18.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                if (fastMode == true) {
                    Icon(Icons.Rounded.Bolt, stringResource(R.string.screen_new_session_sheet_011), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(4.dp))
                }
                Text(stringResource(R.string.screen_new_session_sheet_012, modelLabel(model, effort, defaults)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.screen_new_session_sheet_013)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val s = repo.api.createSession(
                                selected, cwd.trim(), mode, text.trim(), emptyList(),
                                thinking = settings?.thinking ?: "full", model = model, effort = effort, fastMode = fastMode,
                            )
                            repo.settingsStore.rememberNewSession(selected, cwd.trim())
                            text = ""
                            onCreated(s)
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && cwd.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.screen_new_session_sheet_014, Providers.label(selected)))
            }
        }
    }

    if (picking) {
        DirectoryPicker(
            start = cwd,
            onDismiss = { picking = false },
            onPick = { cwd = it; picking = false; scope.launch { reloadDirs() } },
        )
    }
    if (pickingModel) {
        ModelPicker(selected, model, effort, onDismiss = { pickingModel = false }, fastMode = fastMode) { m, e, _, f ->
            if (f != null) fastMode = f
            model = m; effort = e; pickingModel = false
        }
    }
}
