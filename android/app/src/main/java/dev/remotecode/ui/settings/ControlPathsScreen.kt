package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import dev.remotecode.i18n.localizedControlPathText
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ApiException
import dev.remotecode.data.ConnState
import dev.remotecode.data.ControlMode
import dev.remotecode.data.ControlPathsSnapshot
import dev.remotecode.data.ControlProvider
import dev.remotecode.data.ControlRoute
import dev.remotecode.data.ControlStep
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.controlAvailabilityLabel
import dev.remotecode.data.resolveSelection
import dev.remotecode.data.routeLabel
import dev.remotecode.data.routesFor
import dev.remotecode.ui.common.BrandIcon
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

private data class ControlPurpose(val id: String, val label: String, val icon: ImageVector)

private val controlPurposes: List<ControlPurpose> get() = listOf(
    ControlPurpose("operate", appString(R.string.screen_control_paths_001), Icons.Rounded.Bolt),
    ControlPurpose("history", appString(R.string.screen_control_paths_002), Icons.Rounded.Description),
    ControlPurpose("usage", appString(R.string.screen_control_paths_003), Icons.Rounded.AccountTree),
    ControlPurpose("auth", appString(R.string.screen_control_paths_004), Icons.Rounded.Lock),
)

@Composable
fun ControlPathsScreen(onBack: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val connection by repo.connection.collectAsState()
    val route by repo.route.collectAsState()
    val meta by repo.meta.collectAsState()
    val identity = settings?.server?.connectionIdentity()
    val connected = connection == ConnState.Connected
    // 接続先の切替時に旧 PC の情報を破棄する。
    var snapshot by remember(identity) { mutableStateOf<ControlPathsSnapshot?>(null) }
    var loading by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var providerId by remember(identity) { mutableStateOf<String?>(null) }
    var modeId by remember(identity) { mutableStateOf<String?>(null) }
    var purposeId by remember(identity) { mutableStateOf("operate") }

    LaunchedEffect(identity, connected, refresh) {
        if (identity == null || !connected) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val value = repo.api.controlPaths()
            if (repo.settings.value?.server?.connectionIdentity() == identity) snapshot = value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = when {
                e is ApiException && e.code == 404 -> appString(R.string.screen_control_paths_005)
                e is ApiException && e.code == 401 -> appString(R.string.screen_control_paths_006)
                else -> appString(R.string.screen_control_paths_007)
            }
        } finally {
            loading = false
        }
    }

    val selection = snapshot?.resolveSelection(providerId, modeId)
    val provider = snapshot?.providers?.find { it.id == selection?.providerId }
    val mode = provider?.modes?.find { it.id == selection?.modeId }
    val routes = provider?.routesFor(selection?.modeId, purposeId).orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_control_paths_008)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_control_paths_009)) } },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = connected && !loading) {
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, stringResource(R.string.screen_control_paths_010))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ControlConnectionCard(
                    connection = connection,
                    route = route,
                    host = settings?.server?.name?.ifBlank { null } ?: meta?.host ?: "PC",
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.screen_control_paths_011),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.screen_control_paths_012),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot?.at?.takeIf { it > 0 }?.let { at ->
                        val time = remember(at) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) }
                        Text(stringResource(R.string.screen_control_paths_013, time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!connected) item {
                ControlNotice(
                    if (snapshot == null) appString(R.string.screen_control_paths_014)
                    else appString(R.string.screen_control_paths_015),
                    Icons.Rounded.WifiOff,
                )
            }
            error?.let { message -> item { ControlNotice(message, isError = true) } }
            if (loading && snapshot == null) item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.screen_control_paths_016), style = MaterialTheme.typography.bodyMedium)
                }
            }
            snapshot?.let { catalog ->
                if (catalog.providers.isEmpty()) item { ControlNotice(appString(R.string.screen_control_paths_017)) }
                else item {
                    ControlProviderTabs(catalog.providers, selection?.providerId) { selected ->
                        providerId = selected
                        modeId = null
                    }
                }
            }
            provider?.let { selectedProvider ->
                if (selectedProvider.modes.isNotEmpty()) item {
                    ControlModeSelector(selectedProvider.modes, mode) { modeId = it }
                }
                item {
                    ControlPurposeSelector(purposeId) { purposeId = it }
                }
                if (routes.isEmpty()) item {
                    ControlNotice(appString(R.string.screen_control_paths_018))
                }
                itemsIndexed(routes, key = { index, flow -> "${selectedProvider.id}/${selection?.modeId}/$purposeId/$index/${flow.id}" }) { _, flow ->
                    ControlRouteDiagram(flow)
                }
                if (selectedProvider.notes.isNotEmpty()) item {
                    ControlNotes(selectedProvider.notes, appString(R.string.screen_control_paths_019))
                }
                item {
                    Text(
                        stringResource(R.string.screen_control_paths_020),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ControlConnectionCard(connection: ConnState, route: String?, host: String) {
    val connected = connection == ConnState.Connected
    Card(colors = CardDefaults.cardColors(containerColor = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Smartphone, null, Modifier.size(24.dp))
                Text(stringResource(R.string.screen_control_paths_021), style = MaterialTheme.typography.titleSmall)
                Text("↔", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.Computer, null, Modifier.size(24.dp))
                Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            val status = when (connection) {
                ConnState.Connected -> appString(R.string.screen_control_paths_022) + route?.let { " · ${routeLabel(it)}" }.orEmpty()
                ConnState.Connecting -> appString(R.string.screen_control_paths_023)
                is ConnState.Failed -> appString(R.string.screen_control_paths_024)
                ConnState.Idle -> appString(R.string.screen_control_paths_025)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (connected) {
                val protocol = if (route?.startsWith("https://") == true) "HTTPS / WSS" else "HTTP / WebSocket"
                Text(stringResource(R.string.screen_control_paths_026, protocol), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ControlProviderTabs(providers: List<ControlProvider>, selectedId: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        providers.forEach { provider ->
            val isSelected = provider.id == selectedId
            Surface(
                onClick = { onSelect(provider.id) },
                modifier = Modifier.weight(1f).semantics { role = Role.Tab; selected = isSelected },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(horizontal = 5.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandIcon(provider.id, 26.dp)
                    Text(localizedControlPathText(provider.label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlModeSelector(modes: List<ControlMode>, selected: ControlMode?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.screen_control_paths_027), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            modes.forEach { mode ->
                FilterChip(
                    selected = mode.id == selected?.id,
                    onClick = { onSelect(mode.id) },
                    label = { Text(localizedControlPathText(mode.label)) },
                    leadingIcon = { Icon(if (mode.availability == "readOnly") Icons.Rounded.Description else Icons.Rounded.AccountTree, null, Modifier.size(18.dp)) },
                )
            }
        }
        selected?.let { mode ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ControlBadge(controlAvailabilityLabel(mode.availability))
                        ControlBadge(if (mode.activeSessions < 0) appString(R.string.screen_control_paths_028) else appString(R.string.screen_control_paths_029, mode.activeSessions))
                    }
                    if (mode.description.isNotBlank()) Text(localizedControlPathText(mode.description), style = MaterialTheme.typography.bodyMedium)
                    if (mode.activeSessions >= 0) Text(stringResource(R.string.screen_control_paths_030), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPurposeSelector(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        controlPurposes.forEach { purpose ->
            FilterChip(
                selected = selected == purpose.id,
                onClick = { onSelect(purpose.id) },
                label = { Text(purpose.label) },
                leadingIcon = { Icon(purpose.icon, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun ControlRouteDiagram(route: ControlRoute) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(localizedControlPathText(route.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (route.summary.isNotBlank()) Text(localizedControlPathText(route.summary), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            route.steps.forEachIndexed { index, step ->
                ControlStepNode(step, index + 1, route.steps.size)
                if (index < route.steps.lastIndex) {
                    Box(Modifier.width(60.dp).height(30.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            if (route.notes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ControlNotes(route.notes)
            }
        }
    }
}

@Composable
private fun ControlStepNode(step: ControlStep, number: Int, total: Int) {
    var expanded by remember(step) { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = appString(R.string.screen_control_paths_033, number, total, localizedControlPathText(step.label), if (expanded) appString(R.string.screen_control_paths_031) else appString(R.string.screen_control_paths_032))
        },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(controlStepIcon(step.kind), null, Modifier.size(21.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedControlPathText(step.label), style = MaterialTheme.typography.titleSmall)
                    step.protocol?.takeIf { it.isNotBlank() }?.let { protocol ->
                        ControlBadge(protocol)
                    }
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(
                    localizedControlPathText(step.description).ifBlank { appString(R.string.screen_control_paths_034) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlBadge(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ControlNotes(notes: List<String>, title: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        notes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(localizedControlPathText(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ControlNotice(message: String, icon: ImageVector = Icons.Rounded.Info, isError: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

private fun controlStepIcon(kind: String): ImageVector = when (kind) {
    "app" -> Icons.Rounded.Smartphone
    "host" -> Icons.Rounded.Computer
    "sdk" -> Icons.Rounded.Code
    "cli" -> Icons.Rounded.Terminal
    "desktop" -> Icons.Rounded.Computer
    "file" -> Icons.Rounded.Folder
    "cloud" -> Icons.Rounded.Cloud
    "auth" -> Icons.Rounded.Lock
    else -> Icons.Rounded.AccountTree
}
