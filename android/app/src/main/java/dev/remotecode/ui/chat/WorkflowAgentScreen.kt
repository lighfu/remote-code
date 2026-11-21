package dev.remotecode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.Entry
import dev.remotecode.data.ToolWrapPreferences
import dev.remotecode.data.WorkflowAgentUsage
import dev.remotecode.data.connectionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WorkflowAgentScreen(
    sessionId: String,
    entryId: String,
    title: String,
    initiallyActive: Boolean,
    onBack: () -> Unit,
) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val connection by repo.connection.collectAsState()
    val route by repo.route.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visibleScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val configuredServer = settings?.server?.connectionIdentity()
    val openedServer = remember(sessionId, entryId) { configuredServer }
    val target = remember(configuredServer, connection, route) {
        repo.api.server?.takeIf { openedServer != null && openedServer == configuredServer && connection is ConnState.Connected }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var entries by remember(sessionId, entryId) { mutableStateOf<List<Entry>?>(null) }
    var before by remember(sessionId, entryId) { mutableStateOf<String?>(null) }
    var active by remember(sessionId, entryId) { mutableStateOf(initiallyActive) }
    var failure by remember(sessionId, entryId) { mutableStateOf(false) }
    var loadingOlder by remember(sessionId, entryId) { mutableStateOf(false) }
    var olderFailed by remember(sessionId, entryId) { mutableStateOf(false) }
    var refresh by remember(sessionId, entryId) { mutableIntStateOf(0) }
    var usage by remember(sessionId, entryId) { mutableStateOf<WorkflowAgentUsage?>(null) }
    var usageResolved by remember(sessionId, entryId) { mutableStateOf(false) }
    var linkTarget by remember(sessionId, entryId) { mutableStateOf<String?>(null) }
    val linkHandler = remember(sessionId, entryId) {
        object : UriHandler {
            override fun openUri(uri: String) { linkTarget = uri }
        }
    }

    LaunchedEffect(sessionId, entryId, target, refresh, visibleScreen) {
        if (!visibleScreen) return@LaunchedEffect
        failure = target == null
        if (target == null) return@LaunchedEffect
        do {
            val page = try {
                repo.api.claudeWorkflowAgentTimeline(sessionId, entryId, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
                return@LaunchedEffect
            }
            val previous = entries
            val following = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val newestIds = page.entries.mapTo(hashSetOf(), Entry::id)
            val firstOverlap = previous?.indexOfFirst { it.id in newestIds } ?: -1
            if (previous == null || previous.isEmpty() && page.entries.isNotEmpty() ||
                firstOverlap == -1 && page.entries.isNotEmpty() && page.before != null) {
                entries = page.entries
                before = page.before
            } else if (page.entries.isNotEmpty()) {
                entries = if (firstOverlap < 0) (previous + page.entries).distinctBy(Entry::id)
                    else previous.take(firstOverlap) + page.entries
            }
            if (following) listState.requestScrollToItem(0)
            active = page.active ?: active
            if (active) delay(5_000)
        } while (active)
    }

    LaunchedEffect(sessionId, entryId, target, refresh, active, visibleScreen) {
        if (target == null || !visibleScreen) return@LaunchedEffect
        do {
            try {
                usage = repo.api.claudeWorkflowAgentUsage(sessionId, entryId, target)
                usageResolved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@LaunchedEffect
            }
            if (active) delay(5_000)
        } while (active)
    }

    val display = Display(
        thinking = settings?.thinking ?: "full",
        toolDetail = settings?.toolDetail ?: true,
        toolWrap = settings?.toolWrap ?: ToolWrapPreferences(),
    )
    val visible = remember(entries, display.thinking) {
        entries.orEmpty().asReversed().filter {
            it.kind in WorkflowConversationKinds && it.isVisibleInChat(display.thinking)
        }
    }
    val cursor = before
    fun loadOlder() {
        if (cursor == null || target == null || loadingOlder) return
        loadingOlder = true
        olderFailed = false
        scope.launch {
            try {
                val page = repo.api.claudeWorkflowAgentTimeline(sessionId, entryId, target, cursor)
                if (before == cursor) {
                    val current = entries.orEmpty()
                    val currentIds = current.mapTo(hashSetOf(), Entry::id)
                    entries = page.entries.filter { it.id !in currentIds } + current
                    before = page.before
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                olderFailed = true
            } finally {
                loadingOlder = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title.ifBlank { stringResource(R.string.chat_subagent) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active) Text(
                            stringResource(R.string.chat_working),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.chat_back))
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = target != null) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_refresh))
                    }
                },
            )
        },
        bottomBar = {
            val currentUsage = usage
            if (currentUsage != null || usageResolved) {
                Surface {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (currentUsage == null) {
                                Text(
                                    stringResource(R.string.chat_workflow_usage_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                WorkflowUsageLine(currentUsage, detailed = true)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (visible.isEmpty() && cursor == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                if (entries == null && !failure) {
                    LoadingIndicator()
                } else {
                    val message = if (failure) R.string.chat_workflow_conversation_unavailable else R.string.chat_workflow_conversation_empty
                    Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (failure) {
                    item(key = "workflow-refresh-failed") {
                        Text(
                            stringResource(R.string.chat_workflow_conversation_unavailable),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(visible, key = Entry::id, contentType = Entry::kind) { entry ->
                    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
                        EntryView(entry, respond = { _, _, _ -> }, display = display)
                    }
                }
                if (cursor != null) {
                    item(key = "workflow-older-history") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (loadingOlder) {
                                LoadingIndicator()
                            } else {
                                TextButton(onClick = ::loadOlder, enabled = target != null) {
                                    Text(stringResource(if (olderFailed) R.string.chat_workflow_retry else R.string.chat_workflow_older))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    linkTarget?.takeIf { target != null }?.let { link ->
        LinkSheet(link, sessionId) { linkTarget = null }
    }
}

private val WorkflowConversationKinds = setOf("user", "assistant", "thinking", "tool")

@Composable
internal fun WorkflowUsageLine(usage: WorkflowAgentUsage, detailed: Boolean = false) {
    val prefix = if (usage.partial) "≥" else ""
    val parts = buildList {
        usage.contextTokens?.let { tokens ->
            add(stringResource(R.string.chat_workflow_context_tokens, shortTokens(tokens)))
        }
        add(stringResource(R.string.chat_workflow_input_tokens, prefix + shortTokens(usage.inputTokens)))
        add(stringResource(R.string.chat_workflow_output_tokens, prefix + shortTokens(usage.outputTokens)))
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Text(parts.joinToString(" · "), style = if (detailed) typography.bodySmall else typography.labelSmall, color = colors.onSurfaceVariant)
    if (detailed) {
        val cache = buildList {
            add(stringResource(R.string.chat_workflow_cache_read, prefix + shortTokens(usage.cachedInputTokens)))
            add(stringResource(R.string.chat_workflow_cache_write, prefix + shortTokens(usage.cacheWriteTokens)))
            add(stringResource(R.string.chat_workflow_usage_requests, prefix + usage.requestCount))
        }
        Text(cache.joinToString(" · "), style = typography.bodySmall, color = colors.onSurfaceVariant)
        if (usage.partial) {
            Text(stringResource(R.string.chat_workflow_usage_partial), style = typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
