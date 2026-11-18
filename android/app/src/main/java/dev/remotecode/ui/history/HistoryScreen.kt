package dev.remotecode.ui.history

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.HistoryItem
import dev.remotecode.data.Providers
import dev.remotecode.ui.common.ProviderBadge
import dev.remotecode.ui.common.relativeTime
import dev.remotecode.ui.common.shortPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class HistorySort(val label: String) { NEWEST(appString(R.string.screen_history_001)), OLDEST(appString(R.string.screen_history_002)), NAME(appString(R.string.screen_history_003)) }

/** Claude のワークツリーは元プロジェクトにまとめる。 */
private fun projectOf(cwd: String): String {
    val p = cwd.replace('\\', '/').trimEnd('/')
    val i = p.indexOf("/.claude/worktrees/")
    return if (i > 0) p.substring(0, i) else p
}

private fun List<HistoryItem>.sortedBy(sort: HistorySort): List<HistoryItem> = when (sort) {
    HistorySort.NEWEST -> sortedByDescending { it.updatedAt }
    HistorySort.OLDEST -> sortedBy { it.updatedAt }
    HistorySort.NAME -> sortedBy { it.title.lowercase() }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_history_004)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_history_005)) } },
            )
        },
    ) { pad -> HistoryContent(onOpen, Modifier.padding(pad)) }
}

@Composable
fun HistoryContent(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val repo = RemoteCodeApp.instance.repository
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("history_view", android.content.Context.MODE_PRIVATE) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    var provider by rememberSaveable { mutableStateOf(Providers.CLAUDE) }
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<HistoryItem?>(null) }
    var importing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(runCatching { HistorySort.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(HistorySort.NEWEST)) }
    var grouped by remember { mutableStateOf(prefs.getBoolean("grouped", false)) }
    var liveOnly by remember { mutableStateOf(prefs.getBoolean("liveOnly", false)) }
    val collapsed = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(prefs.getStringSet("collapsed", emptySet()).orEmpty()) } }
    fun save() = prefs.edit().putString("sort", sort.name).putBoolean("grouped", grouped).putBoolean("liveOnly", liveOnly).putStringSet("collapsed", collapsed.toSet()).apply()

    fun importHistory(h: HistoryItem) {
        importing = true
        scope.launch {
            val id = try {
                repo.api.import(provider, h).id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (h.sessionId == null) error = e.message
                h.sessionId
            } finally {
                importing = false
            }
            if (id != null) onOpen(id)
        }
    }

    /** PC で開いている履歴は取り込み済みでも再取得する。 */
    fun open(h: HistoryItem) {
        val existing = h.sessionId
        when {
            existing != null && h.live -> importHistory(h)
            existing != null -> onOpen(existing)
            else -> confirm = h
        }
    }

    LaunchedEffect(provider, reloadKey) {
        loading = true
        error = null
        items = emptyList()
        try {
            items = repo.api.history(provider).distinctBy { it.externalId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    val shown = items
        .filter { query.isBlank() || it.title.contains(query, true) || it.cwd.contains(query, true) || it.preview.contains(query, true) }
        .filter { !liveOnly || it.live }
        .sortedBy(sort)
    val groups = shown.groupBy { projectOf(it.cwd) }.entries.let { g ->
        when (sort) {
            HistorySort.NEWEST -> g.sortedByDescending { e -> e.value.maxOf { it.updatedAt } }
            HistorySort.OLDEST -> g.sortedBy { e -> e.value.minOf { it.updatedAt } }
            HistorySort.NAME -> g.sortedBy { e -> e.key.substringAfterLast('/').lowercase() }
        }
    }

    run {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                Providers.all.forEachIndexed { i, p ->
                    ToggleButton(
                        checked = provider == p,
                        onCheckedChange = { provider = p },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            Providers.all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // 長い名前を一行に収めるため幅を広げる。
                        modifier = Modifier.weight(if (p == Providers.ANTIGRAVITY) 1.45f else 1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) {
                        dev.remotecode.ui.common.BrandIcon(p, 18.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(Providers.label(p), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.screen_history_006)) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistorySort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { haptics.tick(); sort = s; save() },
                        label = { Text(s.label) },
                        leadingIcon = if (sort == s) ({ Icon(Icons.Rounded.SwapVert, null, Modifier.size(16.dp)) }) else null,
                    )
                }
                FilterChip(
                    selected = grouped,
                    onClick = { haptics.toggle(!grouped); grouped = !grouped; save() },
                    label = { Text(stringResource(R.string.screen_history_007)) },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = liveOnly,
                    onClick = { haptics.toggle(!liveOnly); liveOnly = !liveOnly; save() },
                    label = { Text(stringResource(R.string.screen_history_008)) },
                )
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    header?.invoke(this)
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                    if (grouped) {
                        groups.forEach { (project, list) ->
                            val open = project !in collapsed
                            item(key = "group:$project") {
                                ProjectHeader(project, list, open) {
                                    haptics.context()
                                    if (open) collapsed.add(project) else collapsed.remove(project)
                                    save()
                                }
                            }
                            if (open) {
                                itemsIndexed(list, key = { _, h -> h.externalId }) { i, h ->
                                    HistoryRow(h, provider, i, list.size, showFolder = projectOf(h.cwd) != h.cwd.replace('\\', '/').trimEnd('/')) { open(h) }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(shown, key = { _, h -> h.externalId }) { i, h ->
                            HistoryRow(h, provider, i, shown.size, showFolder = true) { open(h) }
                        }
                    }
                    if (!loading && shown.isEmpty() && error == null) {
                        item { Text(stringResource(R.string.screen_history_009), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (loading || importing) LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    confirm?.let { h ->
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { confirm = null },
            title = { Text(if (h.live) appString(R.string.screen_history_010) else appString(R.string.screen_history_011)) },
            text = {
                Text(
                    if (h.live) appString(R.string.screen_history_012, h.title)
                    else appString(R.string.screen_history_013, h.title),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    importHistory(h)
                }) { Text(stringResource(R.string.screen_history_014)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.screen_history_015)) } },
        )
    }
}

@Composable
private fun ProjectHeader(project: String, list: List<HistoryItem>, open: Boolean, onToggle: () -> Unit) {
    val live = list.count { it.live }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(project.substringAfterLast('/').ifBlank { project }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                shortPath(project) + appString(R.string.screen_history_016, list.size) + (if (live > 0) appString(R.string.screen_history_017, live) else "") + " · ${relativeTime(list.maxOf { it.updatedAt })}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) appString(R.string.screen_history_018) else appString(R.string.screen_history_019))
    }
}

@Composable
private fun HistoryRow(h: HistoryItem, provider: String, index: Int, count: Int, showFolder: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { ProviderBadge(provider, running = h.running, waiting = false, size = 40.dp) },
        overlineContent = {
            Text(
                (if (showFolder) "${shortPath(h.cwd)} · " else "") + relativeTime(h.updatedAt),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (h.sessionId != null) Text(stringResource(R.string.screen_history_020), color = MaterialTheme.colorScheme.primary)
            else if (h.preview.isNotBlank() && h.preview != h.title) Text(h.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) { Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}
