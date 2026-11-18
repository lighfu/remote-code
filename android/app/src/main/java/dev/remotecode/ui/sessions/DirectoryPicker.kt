package dev.remotecode.ui.sessions

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.DirsInfo
import dev.remotecode.data.FsListing
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.launch

private fun crumbs(path: String): List<Pair<String, String>> {
    if (path.isBlank()) return emptyList()
    val win = path.length >= 2 && path[1] == ':'
    val sep = if (win) "\\" else "/"
    val parts = path.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
    val out = mutableListOf<Pair<String, String>>()
    var acc = if (win) "" else "/"
    parts.forEachIndexed { i, p ->
        acc = if (win) (if (i == 0) "$p\\" else acc.trimEnd('\\') + "\\" + p) else acc.trimEnd('/') + "/" + p
        out += p to acc
    }
    if (!win) out.add(0, "/" to "/")
    @Suppress("UNUSED_VARIABLE") val unused = sep
    return out
}

@Composable
fun DirectoryPicker(start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<FsListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<DirsInfo?>(null) }
    var typing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { repo.api.dirs() }.onSuccess { dirs = it } }
    LaunchedEffect(path) {
        loading = true
        error = null
        filter = ""
        runCatching { repo.api.fs(path) }
            .onSuccess { listing = it }
            .onFailure { e ->
                error = e.message
                if (listing == null && path.isNotEmpty()) path = ""
            }
        loading = false
    }
    val favKeys = dirs?.favorites.orEmpty().map { it.path.lowercase() }.toSet()
    fun toggleFav(p: String) {
        scope.launch {
            runCatching { repo.api.favorite(p, p.lowercase() !in favKeys) }
            runCatching { repo.api.dirs() }.onSuccess { dirs = it }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.screen_directory_picker_001)) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_directory_picker_002)) } },
                        actions = {
                            IconButton(onClick = { typing = true }) { Icon(Icons.Rounded.Edit, stringResource(R.string.screen_directory_picker_003)) }
                            val parent = listing?.parent
                            if (parent != null) IconButton(onClick = { path = parent }) { Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.screen_directory_picker_004)) }
                        },
                    )
                },
                bottomBar = {
                    val current = listing?.path.orEmpty()
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { creating = true }, enabled = current.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.screen_directory_picker_005))
                        }
                        Button(
                            onClick = { onPick(current) },
                            enabled = current.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text(if (current.isEmpty()) appString(R.string.screen_directory_picker_006) else appString(R.string.screen_directory_picker_007), maxLines = 1) }
                    }
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    val cs = crumbs(listing?.path.orEmpty())
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        item {
                            TextButton(onClick = { path = "" }) { Text("PC") }
                        }
                        itemsIndexed(cs) { i, (name, p) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { path = p }, enabled = i < cs.lastIndex) { Text(name, fontFamily = Mono, maxLines = 1) }
                            }
                        }
                    }
                    val quick = (dirs?.favorites.orEmpty().map { it.name to it.path } + if (listing?.path.isNullOrEmpty()) dirs?.roots.orEmpty().map { it.name to it.path } else emptyList())
                    if (quick.isNotEmpty()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quick) { (name, p) ->
                                if (p.lowercase() in favKeys) {
                                    AssistChip(onClick = { path = p }, label = { Text(name, maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) })
                                } else {
                                    SuggestionChip(onClick = { path = p }, label = { Text(name, maxLines = 1) })
                                }
                            }
                        }
                    }
                    val all = listing?.dirs.orEmpty()
                    if (all.size > 10) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            placeholder = { Text(stringResource(R.string.screen_directory_picker_008)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        val shown = all.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                            items(shown, key = { it.path }) { d ->
                                val fav = d.path.lowercase() in favKeys
                                ListItem(
                                    onClick = { path = d.path },
                                    leadingContent = {
                                        Icon(if (d.git) Icons.Rounded.AccountTree else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    supportingContent = if (d.git) ({ Text(stringResource(R.string.screen_directory_picker_009)) }) else null,
                                    trailingContent = {
                                        if (listing?.path.orEmpty().isNotEmpty()) {
                                            IconButton(onClick = { toggleFav(d.path) }) {
                                                Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (fav) appString(R.string.screen_directory_picker_010) else appString(R.string.screen_directory_picker_011),
                                                    tint = if (fav) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                ) { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if (!loading && shown.isEmpty() && error == null) {
                                item {
                                    Text(
                                        if (filter.isBlank()) appString(R.string.screen_directory_picker_012) else appString(R.string.screen_directory_picker_013),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    )
                                }
                            }
                        }
                        if (loading) LoadingIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }

    if (typing) {
        var input by remember { mutableStateOf(listing?.path.orEmpty()) }
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { typing = false },
            title = { Text(stringResource(R.string.screen_directory_picker_003)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { typing = false; path = input.trim() }),
                )
            },
            confirmButton = { Button(onClick = { typing = false; path = input.trim() }) { Text(stringResource(R.string.screen_directory_picker_014)) } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text(stringResource(R.string.screen_directory_picker_015)) } },
        )
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(modifier = dev.remotecode.ui.common.hapticArea(), 
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.screen_directory_picker_016)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.screen_directory_picker_017)) })
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { repo.api.mkdir(listing?.path.orEmpty(), name.trim()) }
                            .onSuccess { creating = false; path = it }
                            .onFailure { err = it.message }
                    }
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.screen_directory_picker_018)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.screen_directory_picker_015)) } },
        )
    }
}
