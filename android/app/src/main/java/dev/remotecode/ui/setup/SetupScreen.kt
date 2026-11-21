package dev.remotecode.ui.setup

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.remotecode.MainActivity
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.CLEARTEXT_BLOCKED_MESSAGE
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.cleartextBlocked
import dev.remotecode.ui.common.openQrReader
import dev.remotecode.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SERVER_DOWNLOAD_URL = "https://github.com/lighfu/remote-code/releases/latest"

@Composable
fun SetupScreen(initial: ServerConfig?, adding: Boolean, canGoBack: Boolean, onBack: () -> Unit, onPair: suspend (ServerConfig) -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(initial?.url ?: "http://") }
    var token by rememberSaveable { mutableStateOf(initial?.token ?: "") }
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var accessId by rememberSaveable { mutableStateOf(initial?.accessId ?: "") }
    var accessSecret by rememberSaveable { mutableStateOf(initial?.accessSecret ?: "") }
    var showAccess by rememberSaveable { mutableStateOf(initial?.hasAccess == true) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun connect(cfg: ServerConfig) {
        if (cleartextBlocked(cfg.url)) {
            error = CLEARTEXT_BLOCKED_MESSAGE
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val meta = repo.api.check(cfg)
                onPair(cfg.copy(name = cfg.name.ifBlank { meta.host }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: appString(R.string.screen_setup_001)
            } finally {
                busy = false
            }
        }
    }

    fun pasteLink() {
        val clip = runCatching {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()
        val cfg = MainActivity.findPairLink(clip)?.let { MainActivity.parsePairUri(Uri.parse(it)) }
        if (cfg == null) {
            error = appString(R.string.screen_setup_002)
            return
        }
        url = cfg.url; token = cfg.token; name = cfg.name
        accessId = cfg.accessId; accessSecret = cfg.accessSecret; showAccess = cfg.hasAccess
        connect(cfg)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(if (adding) R.string.server_picker_add else R.string.screen_setup_003)) },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_setup_004)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(112.dp)
                    .clip(MaterialShapes.SoftBurst.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Computer, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            ServerDownloadCard()
            if (adding) Text(stringResource(R.string.server_picker_add_hint), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(
                onClick = {
                    error = null
                    if (openQrReader(context) == null) {
                        error = appString(R.string.screen_setup_005)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.screen_setup_006))
            }
            FilledTonalButton(
                onClick = ::pasteLink,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Rounded.ContentPaste, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.screen_setup_007))
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text(stringResource(R.string.screen_setup_008)) },
                placeholder = { Text("https://〜.trycloudflare.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.screen_setup_009)) },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showToken) appString(R.string.screen_setup_010) else appString(R.string.screen_setup_011))
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showAccess) {
                OutlinedTextField(
                    value = accessId,
                    onValueChange = { accessId = it.trim() },
                    label = { Text("CF-Access-Client-Id") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = accessSecret,
                    onValueChange = { accessSecret = it.trim() },
                    label = { Text("CF-Access-Client-Secret") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { connect(ServerConfig(url.trimEnd('/'), token, name, accessId.trim(), accessSecret.trim())) },
                enabled = !busy && url.length > 8 && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary) else Text(stringResource(R.string.screen_setup_012))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ServerDownloadCard() {
    val context = LocalContext.current

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, appString(R.string.screen_setup_013), Toast.LENGTH_SHORT).show() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Computer, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.screen_setup_014), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("Windows x64", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_DOWNLOAD_URL))) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.screen_setup_015))
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Remote Code（Windows）\n$SERVER_DOWNLOAD_URL")
                    open(Intent.createChooser(send, appString(R.string.screen_setup_016)))
                }) {
                    Icon(Icons.Rounded.Share, stringResource(R.string.screen_setup_016))
                }
            }
        }
    }
}
