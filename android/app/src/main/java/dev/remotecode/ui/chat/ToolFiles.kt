package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.remotecode.data.Entry

/** 構造化パスを優先し、旧履歴は見出しから補完する。 */
fun toolFiles(e: Entry): List<String> {
    e.files?.filter { it.isNotBlank() }?.distinct()?.takeIf { it.isNotEmpty() }?.let { return it }
    e.path?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
    if (e.tool == "fileChange") {
        val headings = e.detail.orEmpty().lineSequence().filter { it.startsWith("── ") }.map { fileChangePath(it.removePrefix("── ")) }.filter { it.isNotBlank() }.distinct().toList()
        if (headings.isNotEmpty()) return headings
    }
    if (e.tool !in EDIT_TOOLS && e.tool != "Read") return emptyList()
    val title = e.title?.takeIf { it.isNotBlank() } ?: return emptyList()
    if (e.tool == "fileChange") {
        if (isFileChangeSummary(title)) return emptyList()
        return listOf(fileChangePath(title)).filter { it.isNotBlank() }
    }
    return listOf(title)
}

/** Count repeated edits to a known file once. Old entries can carry only a count. */
internal fun changedFileCount(edits: List<Entry>): Int {
    val known = mutableSetOf<String>()
    var unnamed = 0
    for (edit in edits) {
        val paths = toolFiles(edit)
        for (path in paths) known.add(path.replace('\\', '/'))
        unnamed += editFileCount(edit, paths) - paths.size
    }
    return known.size + unnamed
}

internal fun editFileCount(edit: Entry, paths: List<String>): Int {
    val reported = if (edit.tool == "fileChange") changedFileSummaryCount(edit.title)?.toIntOrNull() else null
    return maxOf(paths.size, reported ?: 1)
}

@Composable
internal fun rememberToolFiles(e: Entry) = remember(e.files, e.path, e.tool, e.title, e.detail) { toolFiles(e) }

internal val Entry.hasToolDetail: Boolean
    get() = if (tool == "fileChange") !detail.isNullOrEmpty() else !detail.isNullOrBlank()

@Composable
fun toolFileGesture(files: List<String>, onClick: () -> Unit): Modifier {
    val uriHandler = LocalUriHandler.current
    var choosing by remember { mutableStateOf(false) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.chat_open_file)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                files.forEach { file ->
                    TextButton(onClick = { choosing = false; uriHandler.openUri(file) }, modifier = Modifier.fillMaxWidth()) { Text(file) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.chat_close)) } },
    )
    return Modifier.combinedClickable(
        onClick = onClick,
        onLongClickLabel = if (files.isNotEmpty()) appString(R.string.chat_tool_files_001) else null,
        onLongClick = if (files.isEmpty()) null else {
            {
                haptics.tick()
                if (files.size == 1) uriHandler.openUri(files.first()) else choosing = true
            }
        },
    )
}

@Composable
fun EditPreview(e: Entry, maxLines: Int = Int.MAX_VALUE, wrapLines: Boolean = false, files: List<String> = rememberToolFiles(e)) {
    val detail = e.detail.orEmpty()
    if (files.size > 1) {
        MultiFilePreview(e, files, detail, maxLines, wrapLines)
        return
    }
    if (!e.hasToolDetail) return
    val path = files.singleOrNull().orEmpty()
    val snapshot = remember(e.tool, e.title, e.files, detail) {
        e.tool == "fileChange" && isLegacyFileSnapshot(e.title, e.files, detail)
    }
    if (e.tool == "Write" || e.tool == "NotebookEdit" || snapshot) NumberedCodeBlock(detail, path, maxLines, wrapLines)
    else {
        val diff = remember(e.tool, e.title, e.files, detail) {
            if (e.tool == "fileChange") fileChangePreviewDiff(e.title, e.files, detail) else detail
        }
        DiffBlock(diff, maxLines = maxLines, path = path, wrapLines = wrapLines)
    }
}

@Composable
private fun MultiFilePreview(e: Entry, files: List<String>, detail: String, maxLines: Int, wrapLines: Boolean) {
    val uriHandler = LocalUriHandler.current
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    val sections = remember(files, detail) { fileChangeSections(files, detail) }
    var selectedFile by rememberSaveable(e.id) { mutableStateOf<String?>(files.first()) }
    val active = selectedFile?.let { if (it in files) it else files.first() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (file in files) {
            val path = file.replace('\\', '/')
            val patch = sections[file]
            val selectedFileRow = patch != null && file == active
            val openLabel = stringResource(R.string.tool_changes_open_file, file)
            val openFile = { uriHandler.openUri(file) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .semantics { if (patch != null) selected = selectedFileRow }
                        .combinedClickable(
                            onClickLabel = when {
                                selectedFileRow -> stringResource(R.string.chat_close)
                                patch != null -> stringResource(R.string.tool_changes_show_diff, file)
                                else -> openLabel
                            },
                            onLongClickLabel = openLabel,
                            onLongClick = { haptics.tick(); openFile() },
                            onClick = {
                                if (patch == null) openFile()
                                else selectedFile = if (selectedFileRow) null else file
                            },
                        ).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (patch != null) Icon(
                        if (selectedFileRow) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { directory ->
                            Text(directory, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = openFile) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, openLabel, Modifier.size(20.dp))
                }
            }
            if (selectedFileRow) key(file) {
                DiffBlock(patch.orEmpty(), maxLines = maxLines, path = file, wrapLines = wrapLines)
            }
        }
        if (sections.isEmpty() && e.hasToolDetail) {
            Text(stringResource(R.string.tool_changes_combined), style = MaterialTheme.typography.labelMedium)
            DiffBlock(detail, maxLines = maxLines, wrapLines = wrapLines)
        }
    }
}
