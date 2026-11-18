package dev.remotecode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.remotecode.R
import dev.remotecode.data.Api
import dev.remotecode.data.ApiException
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.ServerDependencies
import dev.remotecode.data.ServerDependency
import dev.remotecode.data.connectionIdentity
import dev.remotecode.i18n.appString
import dev.remotecode.ui.common.BrandIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun dependencyError(error: Exception): String = when ((error as? ApiException)?.code) {
    404, 405 -> appString(R.string.server_dependencies_old_server)
    501 -> appString(R.string.server_dependencies_unavailable)
    else -> error.message ?: appString(R.string.server_dependencies_load_error)
}

@Composable
internal fun ServerDependenciesSection(api: Api, server: ServerConfig?, connected: Boolean, refreshAvailability: suspend (ServerConfig, Boolean) -> Unit) {
    val identity = server?.connectionIdentity()
    var expanded by remember { mutableStateOf(false) }
    var snapshot by remember(identity) { mutableStateOf<ServerDependencies?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var loading by remember(identity) { mutableStateOf(false) }
    var submitting by remember(identity) { mutableStateOf(false) }
    var refresh by remember(identity) { mutableIntStateOf(0) }
    var mutationRevision by remember(identity) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val haptics = dev.remotecode.ui.common.rememberHaptics()

    LaunchedEffect(expanded, connected, identity, refresh, lifecycle) {
        if (!expanded || !connected || server == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var availabilityRefreshed: String? = null
            while (currentCoroutineContext().isActive) {
                loading = true
                val revision = mutationRevision
                try {
                    val next = api.dependencies(server)
                    if (revision == mutationRevision) {
                        val completed = next.completedInstallation(snapshot)
                        snapshot = next
                        error = null
                        val availability = next.dependencies.joinToString { "${it.target}:${it.installed}:${it.status}:${it.path}" }
                        if (!next.installing && availability != availabilityRefreshed) {
                            try {
                                refreshAvailability(server, completed)
                                availabilityRefreshed = availability
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Retry metadata on the next status poll without losing install state.
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the last status and install details through transient failures.
                    if (revision == mutationRevision) {
                        error = dependencyError(e)
                        if ((e as? ApiException)?.code in setOf(401, 403, 404, 405, 501)) return@repeatOnLifecycle
                    }
                } finally {
                    loading = false
                }
                delay(if (snapshot?.installing == true) 1_500 else 15_000)
            }
        }
    }

    SegmentedListItem(
        onClick = { haptics.tick(); expanded = !expanded },
        shapes = ListItemDefaults.segmentedShapes(1, 2),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(Icons.Rounded.Download, null) },
        supportingContent = { Text(stringResource(R.string.server_dependencies_description)) },
        trailingContent = { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
    ) { Text(stringResource(R.string.server_dependencies_title)) }

    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.server_dependencies_hint), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { haptics.tick(); refresh++ }, enabled = connected && !loading && !submitting) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.server_dependencies_refresh))
                }
            }
            if (!connected || server == null) {
                Text(stringResource(R.string.server_dependencies_connect), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (snapshot?.supported == false) {
                Text(stringResource(R.string.server_dependencies_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            val rows = snapshot?.dependencies.orEmpty().associateBy { it.target }
            for ((target, name) in listOf("claude" to "Claude Code", "codex" to "Codex", "antigravity" to "Antigravity", "cloudflared" to "cloudflared")) {
                val dependency = rows[target] ?: ServerDependency(target, name)
                DependencyCard(
                    dependency = dependency,
                    enabled = connected && server != null && snapshot?.supported == true && snapshot?.installing == false && !submitting && rows.containsKey(target),
                    install = {
                        val expectedServer = server ?: return@DependencyCard
                        if (submitting || snapshot?.installing == true) return@DependencyCard
                        haptics.confirm()
                        submitting = true
                        mutationRevision++
                        scope.launch {
                            try {
                                val previous = snapshot
                                val next = api.installDependency(dependency.target, expectedServer)
                                snapshot = next
                                error = null
                                // A short install can finish before its accepted response is read.
                                if (!next.installing && next.completedInstallation(previous)) {
                                    try {
                                        refreshAvailability(expectedServer, true)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // The next status refresh retries metadata.
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = dependencyError(e)
                            } finally {
                                submitting = false
                                refresh++
                            }
                        }
                    },
                )
            }
            Text(stringResource(R.string.server_dependencies_login), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DependencyCard(dependency: ServerDependency, enabled: Boolean, install: () -> Unit) {
    val status = when {
        dependency.inProgress -> stringResource(R.string.server_dependencies_installing)
        dependency.failed -> stringResource(R.string.server_dependencies_failed)
        dependency.installed -> stringResource(R.string.server_dependencies_installed)
        dependency.status.equals("Not installed", ignoreCase = true) -> stringResource(R.string.server_dependencies_missing)
        else -> dependency.status.ifBlank { stringResource(R.string.server_dependencies_status_unknown) }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dependency.target == "cloudflared") Icon(Icons.Rounded.Cloud, null, Modifier.size(24.dp)) else BrandIcon(dependency.target, 24.dp)
                Text(dependency.name.ifBlank { dependency.target }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (dependency.inProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = if (dependency.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dependency.detail.isNotBlank()) Text(dependency.detail, style = MaterialTheme.typography.bodySmall)
                    if (dependency.path.isNotBlank()) Text(dependency.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = install, enabled = enabled && dependency.canInstall, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    dependency.inProgress -> stringResource(R.string.server_dependencies_installing)
                    dependency.failed -> stringResource(R.string.server_dependencies_retry)
                    dependency.installed -> stringResource(R.string.server_dependencies_installed)
                    else -> stringResource(R.string.server_dependencies_install)
                })
            }
        }
    }
}
