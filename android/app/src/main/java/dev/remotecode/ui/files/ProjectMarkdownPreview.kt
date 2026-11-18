package dev.remotecode.ui.files

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnectionIdentity
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.projectLineStarts
import dev.remotecode.data.projectMarkdownAnchor
import dev.remotecode.data.projectMarkdownLocalImagePath
import dev.remotecode.data.resolveProjectMarkdownLink
import dev.remotecode.ui.chat.LinkSheet
import dev.remotecode.ui.chat.WrappingMarkdownTable
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private const val MAX_RENDERED_BLOCK_CHARS = 32 * 1024
private const val MAX_RENDERED_BLOCK_LINES = 240
private const val MAX_REMOTE_IMAGE_BYTES = 8 * 1024 * 1024
private val imageReads = Semaphore(3)

@Composable
internal fun ProjectMarkdownPreview(
    text: String,
    path: String,
    sessionId: String,
    modifier: Modifier = Modifier,
    expectedServer: ServerConfig? = null,
) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val route by repo.route.collectAsState()
    val connection by repo.connection.collectAsState()
    val current = remember(settings?.server, route, connection) { repo.api.server?.connectionIdentity() }
    val source = expectedServer ?: remember(path, sessionId) { repo.api.server }
    val sourceIdentity = source?.connectionIdentity()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var openedLink by remember(path, sessionId) { mutableStateOf<String?>(null) }
    var linkError by remember(path, sessionId) { mutableStateOf<String?>(null) }
    var anchors by remember(path, text) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val starts = remember(text) { projectLineStarts(text) }
    fun open(raw: String) {
        if (sourceIdentity == null || repo.api.server?.connectionIdentity() != sourceIdentity) {
            linkError = appString(R.string.screen_project_markdown_preview_001)
            return
        }
        if (raw.startsWith('#') && !Regex("^#L\\d+(?:-L?\\d+)?$").matches(raw)) {
            val item = anchors[projectMarkdownAnchor(raw)]
            if (item != null) scope.launch { list.animateScrollToItem(item) }
            else linkError = appString(R.string.screen_project_markdown_preview_002)
            return
        }
        val resolved = resolveProjectMarkdownLink(path, raw)
        if (resolved == null) linkError = appString(R.string.screen_project_markdown_preview_003)
        else { linkError = null; openedLink = resolved }
    }
    val uriHandler = object : UriHandler { override fun openUri(uri: String) = open(uri) }
    val images = remember(path, sessionId, sourceIdentity) {
        ProjectMarkdownImages(path, sessionId, source) { raw -> open(raw) }
    }
    val t = MaterialTheme.typography
    val state = rememberMarkdownState(content = text, retainState = false, immediate = false)
    LaunchedEffect(current, sourceIdentity) { if (current != sourceIdentity) openedLink = null }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Column(modifier.fillMaxSize()) {
            linkError?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = t.bodySmall) }
            Markdown(
                markdownState = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                imageTransformer = images,
                typography = markdownTypography(
                    h1 = t.headlineSmall, h2 = t.titleLarge, h3 = t.titleMedium,
                    h4 = t.titleSmall, h5 = t.titleSmall, h6 = t.titleSmall,
                    text = t.bodyMedium, paragraph = t.bodyMedium, code = t.bodySmall.copy(fontFamily = Mono),
                ),
                components = markdownComponents(table = { model -> WrappingMarkdownTable(model.content, model.node, t.bodyMedium) }),
                success = { parsed, components, contentModifier ->
                    val blocks = remember(parsed) {
                        parsed.node.children.filter { node ->
                            (node.startOffset until node.endOffset).any { !parsed.content[it].isWhitespace() }
                        }
                    }
                    val headingTargets = remember(parsed, blocks) {
                        val duplicates = mutableMapOf<String, Int>()
                        buildMap {
                            blocks.forEachIndexed { index, block ->
                                if (block.endOffset - block.startOffset > MAX_RENDERED_BLOCK_CHARS) return@forEachIndexed
                                val content = parsed.content.substring(block.startOffset, block.endOffset)
                                val first = content.lineSequence().firstOrNull().orEmpty().trim()
                                val heading = when {
                                    Regex("^#{1,6}\\s+").containsMatchIn(first) -> first.replace(Regex("^#{1,6}\\s+"), "").trimEnd('#').trim()
                                    content.lineSequence().drop(1).firstOrNull()?.trim()?.matches(Regex("(?:=+|-+)")) == true -> first
                                    else -> null
                                }
                                if (heading != null) {
                                    val slug = projectMarkdownAnchor(heading)
                                    val duplicate = duplicates.getOrDefault(slug, 0)
                                    duplicates[slug] = duplicate + 1
                                    put(if (duplicate == 0) slug else "$slug-$duplicate", index)
                                }
                            }
                        }
                    }
                    SideEffect { if (anchors != headingTargets) anchors = headingTargets }
                    LazyColumn(state = list, modifier = contentModifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (blocks.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.screen_project_markdown_preview_004), style = t.bodyMedium) } }
                        itemsIndexed(blocks, key = { _, node -> node.startOffset }) { _, node ->
                            val large = remember(node, parsed) {
                                node.endOffset - node.startOffset > MAX_RENDERED_BLOCK_CHARS ||
                                    (node.startOffset until node.endOffset).count { parsed.content[it] == '\n' } > MAX_RENDERED_BLOCK_LINES
                            }
                            if (large) {
                                val line = starts.binarySearch(node.startOffset).let { if (it >= 0) it + 1 else -it - 1 }
                                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().clickable { open("#L$line") }) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.screen_project_markdown_preview_005, node.endOffset - node.startOffset), style = t.labelLarge)
                                        Text(stringResource(R.string.screen_project_markdown_preview_006), style = t.bodySmall)
                                    }
                                }
                            } else MarkdownElement(node, components, parsed.content, includeSpacer = false)
                        }
                    }
                },
            )
        }
    }
    if (openedLink != null && current == sourceIdentity) LinkSheet(openedLink!!, sessionId) { openedLink = null }
}

/** 参照形式・インライン画像の両方に適用される。 */
private class ProjectMarkdownImages(
    private val path: String,
    private val sessionId: String,
    private val server: ServerConfig?,
    private val onOpen: (String) -> Unit,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val resolved = remember(path, link) { resolveProjectMarkdownLink(path, link) } ?: return null
        val bitmap by produceState<Bitmap?>(null, resolved, server?.connectionIdentity()) {
            try { value = imageReads.withPermit { loadProjectMarkdownImage(resolved, sessionId, server) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
        val image = bitmap
        val painter = if (image != null) remember(image) { BitmapPainter(image.asImageBitmap()) } else rememberVectorPainter(Icons.Rounded.Image)
        return ImageData(
            painter = painter,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { onOpen(link) },
            contentDescription = appString(R.string.screen_project_markdown_preview_007),
            contentScale = ContentScale.Fit,
        )
    }
}

private suspend fun loadProjectMarkdownImage(link: String, sessionId: String, server: ServerConfig?): Bitmap? {
    val api = RemoteCodeApp.instance.repository.api
    val identity: ConnectionIdentity = server?.connectionIdentity() ?: return null
    if (api.server?.connectionIdentity() != identity) return null
    val bytes = if (link.startsWith("https://", true) || link.startsWith("http://", true)) {
        withContext(Dispatchers.IO) {
            val connection = URI(link).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000; connection.readTimeout = 8_000
            try {
                if (connection.contentLengthLong > MAX_REMOTE_IMAGE_BYTES) return@withContext null
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_REMOTE_IMAGE_BYTES) return@withContext null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    } else {
        val localPath = projectMarkdownLocalImagePath(link) ?: return null
        // 画像取得前にプロジェクト外のリンクを拒否する。
        api.projectEntry(sessionId, localPath, expectedServer = server)
        val info = api.fileInfo(localPath, sessionId, expectedServer = server)
        if (info.kind != "image" || info.mediaId == null || info.size > MAX_REMOTE_IMAGE_BYTES) return null
        api.media(info.mediaId, expectedServer = server)
    } ?: return null
    if (api.server?.connectionIdentity() != identity) return null
    return withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
