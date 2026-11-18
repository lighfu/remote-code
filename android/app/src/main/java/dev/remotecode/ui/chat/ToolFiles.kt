package dev.remotecode.ui.chat

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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

@Composable
fun toolFileGesture(e: Entry, onClick: () -> Unit): Modifier {
    val files = remember(e.files, e.path, e.tool, e.title, e.detail) { toolFiles(e) }
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
fun EditPreview(e: Entry, maxLines: Int = Int.MAX_VALUE, wrapLines: Boolean = false) {
    val detail = e.detail ?: return
    val path = remember(e) { toolFiles(e).firstOrNull().orEmpty() }
    if (e.tool == "Write" || e.tool == "NotebookEdit") NumberedCodeBlock(detail, path, maxLines, wrapLines)
    else DiffBlock(detail, maxLines = maxLines, path = path, wrapLines = wrapLines)
}
