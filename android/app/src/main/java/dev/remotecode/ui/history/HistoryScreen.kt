package dev.remotecode.ui.history

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
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
import androidx.compose.runtime.key
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class HistorySort(val label: String) { NEWEST(appString(R.string.screen_history_001)), OLDEST(appString(R.string.screen_history_002)), NAME(appString(R.string.screen_history_003)) }

private const val AllHistory = "all"
private val historyFilters = listOf(AllHistory) + Providers.all

internal fun Set<String>.withProjectExpansion(projects: Set<String>, expanded: Boolean): Set<String> =
    if (expanded) this - projects else this + projects

/** Deliver each source as it finishes so a slow or unavailable CLI cannot hide the others. */
internal suspend fun loadHistorySources(
    providers: List<String>,
    fetch: suspend (String) -> List<HistoryItem>,
    onItems: (List<HistoryItem>) -> Unit,
    onError: (String, Exception) -> Unit,
) = coroutineScope {
    for (source in providers) launch {
        val rows = try {
            fetch(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(source, e)
            return@launch
        }
        onItems(rows.distinctBy { it.externalId }.map { it.copy(provider = source) })
    }
}

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
    var provider by rememberSaveable { mutableStateOf(prefs.getString("provider", null)?.takeIf { it in historyFilters } ?: Providers.CLAUDE) }
    var items by remember(provider, reloadKey) { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember(provider, reloadKey) { mutableStateOf(true) }
    var error by remember(provider, reloadKey) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<HistoryItem?>(null) }
    var importing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(runCatching { HistorySort.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(HistorySort.NEWEST)) }
    var grouped by remember { mutableStateOf(prefs.getBoolean("grouped", false)) }
    var liveOnly by remember { mutableStateOf(prefs.getBoolean("liveOnly", false)) }
    var collapsed by remember { mutableStateOf(prefs.getStringSet("collapsed", emptySet()).orEmpty().toSet()) }
    fun save() = prefs.edit().putString("sort", sort.name).putBoolean("grouped", grouped).putBoolean("liveOnly", liveOnly).apply()
    fun setExpanded(projects: Set<String>, expanded: Boolean) {
        haptics.toggle(expanded)
        collapsed = collapsed.withProjectExpansion(projects, expanded)
        prefs.edit().putStringSet("collapsed", collapsed).apply()
    }

    fun importHistory(h: HistoryItem) {
        importing = true
        scope.launch {
            val id = try {
                repo.api.import(h.provider, h).id
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
        loadHistorySources(
            if (provider == AllHistory) Providers.all else listOf(provider),
            fetch = repo.api::history,
            onItems = { items = items + it },
            onError = { source, e ->
                val message = e.localizedMessage ?: e.javaClass.simpleName
                error = listOfNotNull(error, "${Providers.label(source)}: $message").joinToString("\n")
            },
        )
        loading = false
    }

    val shown = remember(items, query, liveOnly, sort) {
        items.filter { query.isBlank() || it.title.contains(query, true) || it.cwd.contains(query, true) || it.preview.contains(query, true) }
            .filter { !liveOnly || it.live }
            .sortedBy(sort)
    }
    val groups = remember(shown, grouped, sort) {
        if (!grouped) return@remember emptyList()
        val g = shown.groupBy { projectOf(it.cwd) }.entries
        when (sort) {
            HistorySort.NEWEST -> g.sortedByDescending { e -> e.value.maxOf { it.updatedAt } }
            HistorySort.OLDEST -> g.sortedBy { e -> e.value.minOf { it.updatedAt } }
            HistorySort.NAME -> g.sortedBy { e -> e.key.substringAfterLast('/').lowercase() }
        }
    }
    val visibleProjects = remember(groups) { groups.mapTo(mutableSetOf()) { it.key } }

    run {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                historyFilters.forEachIndexed { i, p ->
                    ToggleButton(
                        checked = provider == p,
                        onCheckedChange = {
                            if (p != provider) {
                                haptics.tick()
                                provider = p
                                prefs.edit().putString("provider", p).apply()
                            }
                        },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            historyFilters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        if (p == AllHistory) Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
                        else dev.remotecode.ui.common.BrandIcon(p, 18.dp)
                        Spacer(Modifier.size(4.dp))
                        Text(if (p == AllHistory) stringResource(R.string.screen_net_log_046) else Providers.label(p), maxLines = 1)
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
            if (visibleProjects.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = { setExpanded(visibleProjects, true) },
                        enabled = visibleProjects.any { it in collapsed },
                    ) {
                        Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.history_expand_all))
                    }
                    TextButton(
                        onClick = { setExpanded(visibleProjects, false) },
                        enabled = visibleProjects.any { it !in collapsed },
                    ) {
                        Icon(Icons.Rounded.UnfoldLess, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.history_collapse_all))
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                val listState = key(provider) { rememberLazyListState() }
                // On return, measuring just the headers would reset a restored scroll position.
                val hasSavedScroll = remember(listState, reloadKey) {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
                val restoringScroll = loading && hasSavedScroll
                if (!restoringScroll) LazyColumn(
                    state = listState,
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
                                    setExpanded(setOf(project), !open)
                                }
                            }
                            if (open) {
                                itemsIndexed(list, key = { _, h -> h.listKey }) { i, h ->
                                    HistoryRow(h, i, list.size, showFolder = projectOf(h.cwd) != h.cwd.replace('\\', '/').trimEnd('/'), showProvider = provider == AllHistory) { open(h) }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(shown, key = { _, h -> h.listKey }) { i, h ->
                            HistoryRow(h, i, shown.size, showFolder = true, showProvider = provider == AllHistory) { open(h) }
                        }
                    }
                    if (!loading && shown.isEmpty() && error == null) {
                        item { Text(stringResource(R.string.screen_history_009), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (restoringScroll || (loading && items.isEmpty()) || importing) LoadingIndicator(Modifier.align(Alignment.Center))
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
private fun HistoryRow(h: HistoryItem, index: Int, count: Int, showFolder: Boolean, showProvider: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { ProviderBadge(h.provider, running = h.running, waiting = false, size = 40.dp) },
        overlineContent = {
            Text(
                (if (showProvider) "${Providers.label(h.provider)} · " else "") +
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
