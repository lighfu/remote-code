package dev.remotecode.ui.desktop

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.*
import dev.remotecode.i18n.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

private data class VisibleFrame(val image: DesktopFrame, val capturedAt: Long) {
    fun fresh() = SystemClock.elapsedRealtime() - capturedAt < FrameLifetimeMs
}

private data class DesktopInputNotice(val message: String, val rejected: RejectedDesktopFrame? = null)

private const val FrameLifetimeMs = 5_000L
private const val AllDisplays = "all"

internal fun layoutNeedsMonitorRefresh(previous: String?, frame: String, monitorLayout: String?): Boolean =
    previous != frame && if (monitorLayout != null) monitorLayout != frame else previous != null

private fun temporaryFrameFailure(error: Exception): Boolean =
    error.cause is IOException || error is SocketException || error is SocketTimeoutException || error is EOFException ||
        (error is ApiException && (error.code in listOf(408, 425, 429) || error.code in 500..504 && error.code != 501))

private fun desktopFailureLog(error: Exception): String {
    val status = when (error) {
        is DesktopStreamHttpError -> error.status
        is ApiException -> error.code.takeIf { it != 0 }
        else -> null
    }
    if (status == 429) return appString(R.string.desktop_log_busy)
    val detail = status?.let { "HTTP $it" } ?: (error.cause ?: error).javaClass.simpleName
    return appString(R.string.desktop_log_failure, detail)
}

@Composable
internal fun DesktopScreen(onBack: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val identity by repo.activeServerIdentity.collectAsState()
    val pairedToken = remember { repo.settings.value?.server?.token ?: repo.api.server?.token }
    val target = repo.api.server?.takeIf { it.connectionIdentity() == identity && it.token == pairedToken }
    val desktop = repo.api.desktop
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val active = state == Lifecycle.State.RESUMED
    var display by rememberSaveable { mutableStateOf(AllDisplays) }
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(repo.settings.value?.desktop ?: DesktopPreferences()) }
    var full by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var surface by remember(identity, display) { mutableStateOf<DesktopSurface?>(null) }
    var zoom by remember(identity, display) { mutableFloatStateOf(1f) }
    var refresh by remember { mutableIntStateOf(0) }
    val log = remember(identity, display) { DesktopEventLog() }
    var displayRefresh by remember { mutableIntStateOf(0) }
    var capabilities by remember(identity) { mutableStateOf<DesktopDisplays?>(null) }
    var seenLayout by remember(identity, display) { mutableStateOf<String?>(null) }
    var awaitingLayout by remember(identity, display) { mutableStateOf<String?>(null) }
    val supportsNative = capabilities?.nativeResolution == true
    val resolution = if (!supportsNative && prefs.resolution == DesktopResolution.Native) DesktopResolution.High else prefs.resolution
    val control = prefs.control && capabilities != null && awaitingLayout == null &&
        (capabilities?.layout == null || capabilities?.layout == seenLayout)
    val displays = capabilities?.displays.orEmpty()
    val captures = remember(capabilities) { capabilities?.captureMethods.orEmpty().filter { it in setOf("auto", "gdi", "dxgi") }.ifEmpty { listOf("gdi") } }
    val codecs = remember(capabilities) { capabilities?.codecs.orEmpty().filter { it in setOf("jpeg", "png") }.ifEmpty { listOf("jpeg") } }
    val capture = prefs.capture.takeIf { it in captures } ?: captures.first()
    val codec = prefs.codec.takeIf { it in codecs } ?: codecs.first()
    val compression = prefs.compressionFor(codec, capabilities?.compressionControls == true)
    val touchpad = prefs.touchpad && capabilities?.touchpad == true
    var displayError by remember(identity) { mutableStateOf<String?>(null) }
    var frame by remember(identity, display, resolution, active, refresh, capture, codec) { mutableStateOf<VisibleFrame?>(null) }
    var frameError by remember(identity, display, resolution, active, refresh, capture, codec) { mutableStateOf<String?>(null) }
    val frameRate = remember(identity, display, resolution, capture, codec, active, refresh) { ReceivedFrameRate(SystemClock.elapsedRealtime()) }
    val drawnRate = remember(frameRate) { ReceivedFrameRate(SystemClock.elapsedRealtime()) }
    var measuredFps by remember(frameRate) { mutableStateOf<Float?>(null) }
    val telemetry = remember(frameRate) { DesktopStreamTelemetry(SystemClock.elapsedRealtime()) }
    val history = remember(frameRate) { mutableStateListOf<DesktopOverlaySample>() }
    val supportsBitrate = capabilities?.streamBitrate == true && capabilities?.frameStream == true
    val autoBitrate = remember(frameRate, prefs.bitrateMbps, target?.url, prefs.fps) {
        if (target != null && isPublicUrl(target.url)) DesktopAutoBitrate(32_000, 64_000)
        else DesktopAutoBitrate(64_000, 120_000)
    }
    var autoKbps by remember(autoBitrate) { mutableIntStateOf(autoBitrate.initialKbps) }
    val autoEnabled = supportsBitrate && prefs.bitrateMbps == 0
    val bitrateKbps = when {
        !supportsBitrate || prefs.bitrateMbps < 0 -> null
        autoEnabled -> autoKbps
        else -> prefs.bitrateMbps * 1000
    }
    var debugStats by remember(frameRate) { mutableStateOf<DesktopDebugStats?>(null) }
    var inputNotice by remember(identity, display, active) { mutableStateOf<DesktopInputNotice?>(null) }
    val inputError = inputNotice?.message
    var clipboardError by remember(identity, prefs.clipboard) { mutableStateOf<String?>(null) }
    var lastPoint by remember(identity, display) { mutableStateOf<Pair<String, DesktopPoint>?>(null) }
    var draft by remember(identity) { mutableStateOf("") }
    val inputLayout = seenLayout
    val commands = remember(identity, display, resolution, capture, codec, touchpad, active, control, refresh, inputLayout) { DesktopInputQueue() }
    var sendingText by remember(commands) { mutableStateOf(false) }
    DisposableEffect(commands) { onDispose { commands.close(); surface?.resetCursorPrediction() } }

    fun updatePreferences(next: DesktopPreferences) {
        prefs = next
        scope.launch { repo.settingsStore.setDesktop(next, pairedToken) }
    }

    LaunchedEffect(frameRate, active, autoBitrate, autoEnabled) {
        if (!active) return@LaunchedEffect
        while (true) {
            delay(1000L)
            val now = SystemClock.elapsedRealtime()
            val sample = telemetry.sample(now, drawnRate.fps(now))
            if (autoEnabled) {
                val next = autoBitrate.nextKbps(autoKbps, sample)
                if (next != autoKbps) {
                    log.add(appString(R.string.desktop_log_auto_target, next / 1000f))
                    autoKbps = next
                }
            }
            if (options || prefs.debugOverlay) measuredFps = frameRate.fps(now)
            if (prefs.debugOverlay) {
                val shown = frame?.image
                if (shown != null) {
                    debugStats = DesktopDebugStats(shown.image.width, shown.image.height, shown.captureMethod,
                        shown.codec, shown.headers.captureMs, shown.decodeMs, sample.fps)
                }
                while (history.isNotEmpty() && now - history.first().atMs > DesktopFpsWindowMs) history.removeAt(0)
                history.add(sample)
            } else if (history.isNotEmpty()) {
                history.clear()
                debugStats = null
            }
        }
    }

    LaunchedEffect(target, active, displayRefresh, display) {
        if (target == null || !active) return@LaunchedEffect
        val ticket = displayRefresh
        var retryMs = 1000L
        while (true) {
            log.add(appString(R.string.desktop_log_displays))
            try {
                val info = desktop.displayInfo(target)
                if (ticket != displayRefresh) break
                capabilities = info
                displayError = null
                if (awaitingLayout == null || info.layout == null || info.layout == awaitingLayout) {
                    awaitingLayout = null
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(desktopFailureLog(e))
                displayError = e.localizedMessage ?: appString(R.string.desktop_invalid_frame)
                if (awaitingLayout == null || !temporaryFrameFailure(e)) break
            }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(30_000L)
        }
    }

    val streaming = capabilities?.frameStream == true
    val conditional = capabilities?.conditionalFrames == true
    val separateCursor = capabilities?.cursorMetadata == true
    val regions = capabilities?.regionFrames == true
    val ready = capabilities != null || displayError != null
    LaunchedEffect(target, active, display, resolution, refresh, capture, codec, streaming, conditional, separateCursor, regions, ready,
        prefs.fps, bitrateKbps, autoBitrate, prefs.decodeColor, prefs.decodeLatest, compression) {
        if (target == null || !active || !ready) return@LaunchedEffect
        val decodeColor = prefs.decodeColor
        fun receive(image: DesktopFrame, validatedAt: Long) {
            if (seenLayout != image.layout) {
                if (layoutNeedsMonitorRefresh(seenLayout, image.layout, capabilities?.layout)) {
                    awaitingLayout = image.layout
                    displayRefresh++
                } else {
                    awaitingLayout = null
                }
                seenLayout = image.layout
                lastPoint = null
                surface?.resetCursorPrediction()
            }
            if (image.image !== frame?.image?.image) {
                frameRate.record(SystemClock.elapsedRealtime())
                telemetry.record(image.headers.captureMs, image.decodeMs)
            }
            val updated = VisibleFrame(image, validatedAt)
            frame = updated
            if (awaitingLayout == null && updated.fresh() &&
                inputNotice?.rejected?.supersededBy(image.layout, validatedAt, image.headers.frameAt) == true) {
                inputNotice = null
            }
            frameError = null
        }
        try {
            val retry = DesktopStreamRetry(streaming)
            var pollReady = false
            while (true) {
                if (retry.due(SystemClock.elapsedRealtime())) {
                    var streamReady = false
                    log.add(appString(R.string.desktop_log_connect, codec.uppercase()))
                    try {
                        autoBitrate.newStream()
                        desktop.stream(target, display, resolution, capture, codec, separateCursor, prefs.fps, regions,
                            bitrateKbps, decodeColor = decodeColor, decodeLatest = prefs.decodeLatest, compression = compression,
                            onPayloadBytes = { telemetry.addBytes(it.toLong()) })
                            .collect { (at, image) ->
                                if (!streamReady) {
                                    log.add(appString(R.string.desktop_log_ready, image.image.width, image.image.height))
                                    streamReady = true
                                    pollReady = false
                                }
                                if (autoEnabled) autoBitrate.received(SystemClock.elapsedRealtime(), image.headers.frameAt)
                                receive(image, at)
                                retry.connected()
                            }
                        log.add(appString(R.string.desktop_log_ended))
                        val now = SystemClock.elapsedRealtime()
                        retry.interrupted(now)
                        log.add(appString(R.string.desktop_log_retry, retry.waitSeconds(now)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: DesktopStreamUnsupported) {
                        retry.unsupported()
                        log.add(appString(R.string.desktop_log_fallback))
                    } catch (e: IOException) {
                        log.add(desktopFailureLog(e))
                        val now = SystemClock.elapsedRealtime()
                        retry.interrupted(now)
                        log.add(appString(R.string.desktop_log_retry, retry.waitSeconds(now)))
                    }
                }
                val started = SystemClock.elapsedRealtime()
                try {
                    val previous = frame?.image?.takeIf { conditional }
                    val image = desktop.frame(target, display, resolution, capture, codec, separateCursor, previous,
                        decodeColor = decodeColor, compression = compression)
                    telemetry.addBytes(image.receivedPayloadBytes)
                    receive(image, started)
                    if (!pollReady) {
                        log.add(appString(R.string.desktop_log_poll_ready))
                        pollReady = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!temporaryFrameFailure(e)) throw e
                    pollReady = false
                    log.add(desktopFailureLog(e))
                    log.add(appString(R.string.desktop_log_poll_wait))
                    if (frame?.fresh() != true) frame = null
                    frameError = if ((e as? ApiException)?.code == 429) appString(R.string.desktop_log_busy) else e.localizedMessage
                    delay(1_000L)
                    continue
                }
                delay(nextFrameDelay(started, SystemClock.elapsedRealtime(), prefs.fps))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add(desktopFailureLog(e))
            frame = null
            frameError = e.localizedMessage ?: appString(R.string.desktop_invalid_frame)
            if ((e as? ApiException)?.code == 409) displayRefresh++
        }
    }

    LaunchedEffect(commands, target) {
        if (target == null || !active || !control) return@LaunchedEffect
        while (true) {
            val cmd = commands.next() ?: break
            try {
                val shown = frame
                if (shown == null || !shown.fresh() || shown.image.layout != cmd.layout || awaitingLayout != null ||
                    SystemClock.elapsedRealtime() - cmd.capturedAt >= FrameLifetimeMs ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    throw DesktopFrameExpired(appString(R.string.desktop_input_wait_frame))
                }
                val revision = desktop.input(target, display, cmd.layout, cmd.input, cmd.frameAt)
                surface?.settleCursor(revision, commands.hasPending)
                inputNotice = null
                if (cmd.input.type in setOf("click", "doubleClick", "rightClick", "drag", "scroll")) {
                    (cmd.input.end ?: cmd.input.point)?.let { lastPoint = cmd.layout to it }
                }
                cmd.accepted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                surface?.settleCursor(null, commands.hasPending)
                val layoutChanged = (e as? ApiException)?.code == 409
                val rejected = if (e is DesktopFrameExpired || layoutChanged) {
                    RejectedDesktopFrame(cmd.layout, cmd.capturedAt, cmd.frameAt)
                } else null
                inputNotice = DesktopInputNotice(e.localizedMessage ?: appString(R.string.desktop_input_failed), rejected)
                if (layoutChanged) {
                    frame = null
                    refresh++
                    displayRefresh++
                    break
                }
            } finally {
                if (cmd.input.type == "text") sendingText = false
            }
        }
    }

    fun send(input: DesktopInput, accepted: () -> Unit = {}): Boolean {
        if (!control || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val shown = frame
        if (shown == null || !shown.fresh() || awaitingLayout != null || shown.image.layout != inputLayout) {
            inputNotice = DesktopInputNotice(appString(R.string.desktop_input_wait_frame),
                RejectedDesktopFrame(shown?.image?.layout, shown?.capturedAt ?: 0, shown?.image?.headers?.frameAt))
            return false
        }
        if (!commands.offer(DesktopCommand(input, shown.image.layout, shown.capturedAt, shown.image.headers.frameAt, accepted))) {
            inputNotice = DesktopInputNotice(appString(R.string.desktop_input_failed))
            return false
        }
        return true
    }

    fun scroll(delta: Int) {
        if (touchpad) {
            send(DesktopInput("cursorScroll", delta = delta))
            return
        }
        val point = lastPoint?.takeIf { it.first == frame?.image?.layout }?.second ?: defaultScrollPoint(display, displays)
        send(DesktopInput("scroll", point, delta = delta))
    }

    DesktopSystemBars(full && active)
    DesktopClipboardSync(api = desktop, target = target, enabled = prefs.clipboard && capabilities?.clipboard == true,
        active = active, onError = { clipboardError = it })
    val selected = displays.firstOrNull { it.id == display }
    val label = if (display == AllDisplays) stringResource(R.string.desktop_all_displays) else selected?.name ?: display
    val canControl = target != null && active && control && frame != null
    val showOptions: () -> Unit = { options = true; displayRefresh++ }
    Scaffold(
        contentWindowInsets = if (full) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!full) TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_terminals_007)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.desktop_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = { IconButton(onClick = showOptions) { Icon(Icons.Rounded.Tune, stringResource(R.string.desktop_options)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                val shown = frame
                val error = if (target == null) stringResource(R.string.core_server_changed) else frameError
                when {
                    shown != null -> key(identity, display, shown.image.layout) {
                        val description = stringResource(R.string.desktop_view)
                        val latestSend by rememberUpdatedState<(DesktopInput) -> Boolean> { control && send(it) }
                        AndroidView(factory = { context -> DesktopSurface(context).also { surface = it } }, modifier = Modifier.fillMaxSize(), update = {
                            it.contentDescription = description
                            it.pixelated = prefs.pixelated
                            it.touchpad = touchpad
                            it.pointerSpeed = prefs.pointerSpeed
                            it.onZoomChanged = { factor -> zoom = factor }
                            it.onFrameDrawn = { drawnRate.record(SystemClock.elapsedRealtime()) }
                            it.send = { input -> latestSend(input) }
                            it.setDisplays(display, displays)
                            it.update(shown.image.image, shown.image.headers.cursor)
                        }, onReset = null, onRelease = {
                            if (surface === it) surface = null
                        })
                    }
                    error != null -> Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = Color.White)
                        TextButton(onClick = {
                            log.add(appString(R.string.desktop_log_manual))
                            refresh++
                            displayRefresh++
                        }) { Text(stringResource(R.string.server_dependencies_retry)) }
                    }
                    else -> CircularProgressIndicator()
                }
                val stats = debugStats
                if (prefs.debugOverlay) {
                    DesktopConnectionLog(log, Modifier.align(Alignment.BottomStart)
                        .then(if (full) Modifier.safeDrawingPadding() else Modifier).padding(6.dp))
                }
                if (prefs.debugOverlay && shown != null && stats != null) {
                    DesktopDebugOverlay(stats, history, prefs.fps, prefs.graphMask,
                        Modifier.align(Alignment.TopStart).then(if (full) Modifier.safeDrawingPadding() else Modifier).padding(6.dp))
                }
                if (full) FilledTonalIconButton(onClick = showOptions, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(4.dp)) {
                    Icon(Icons.Rounded.Tune, stringResource(R.string.desktop_options))
                }
            }
            inputError?.let { message -> Text(message, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
            clipboardError?.let { message -> Text(message, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
            if (!full) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(enabled = canControl, onClick = { scroll(3) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.desktop_scroll_up))
                }
                IconButton(enabled = canControl, onClick = { scroll(-3) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.desktop_scroll_down))
                }
                IconButton(enabled = canControl, onClick = { keyboard = true }) {
                    Icon(Icons.Rounded.Keyboard, stringResource(R.string.desktop_keyboard))
                }
                IconButton(enabled = frame != null && zoom > 1f, onClick = { surface?.zoomBy(0.5f) }) {
                    Icon(Icons.Rounded.ZoomOut, stringResource(R.string.desktop_zoom_out))
                }
                IconButton(enabled = frame != null && zoom < 32f, onClick = { surface?.zoomBy(2f) }) {
                    Icon(Icons.Rounded.ZoomIn, stringResource(R.string.desktop_zoom_in))
                }
                IconButton(onClick = { full = true }) { Icon(Icons.Rounded.Fullscreen, stringResource(R.string.desktop_fullscreen)) }
            }
        }
    }

    if (options) DesktopOptionsSheet(onDismiss = { options = false }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionAction(R.string.desktop_fullscreen, if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen) {
                full = !full
                options = false
            }
            OptionAction(R.string.desktop_disconnect, Icons.Rounded.LinkOff) { options = false; onBack() }
        }
        DesktopDisplayOptions(display, displays, displayError,
            onSelect = { display = it; refresh++; inputNotice = null; options = false },
            onRefresh = { displayRefresh++ }, zoom = zoom, onZoomBy = { surface?.zoomBy(it) }, onFit = { surface?.fit(); options = false })
        DesktopInputOptions(prefs, supportsTouchpad = capabilities?.touchpad == true, touchpad = touchpad,
            supportsClipboard = capabilities?.clipboard == true, knownCapabilities = capabilities != null, clipboardError = clipboardError,
            change = ::updatePreferences, keyboardEnabled = canControl,
            onKeyboard = { keyboard = true; options = false }, onHelp = { help = true; options = false })
        if (full) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionAction(R.string.desktop_scroll_up, Icons.Rounded.KeyboardArrowUp, canControl) { scroll(3); options = false }
            OptionAction(R.string.desktop_scroll_down, Icons.Rounded.KeyboardArrowDown, canControl) { scroll(-3); options = false }
        }
        DesktopVideoOptions(prefs, ::updatePreferences, frame?.image?.image?.width, frame?.image?.image?.height, measuredFps,
            captures, capture, frame?.image?.captureMethod, codecs, codec, resolution, supportsNative, supportsBitrate, autoKbps,
            capabilities?.compressionControls)
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.desktop_help)) },
        text = { Text(stringResource(if (touchpad) R.string.desktop_gestures else R.string.desktop_direct_gestures)) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.core_close)) } })
    if (keyboard && control) {
        DesktopKeyboard(draft, { draft = it }, canControl, sendingText, inputError,
            onSend = {
                val submitted = draft
                sendingText = send(DesktopInput("text", text = submitted)) { if (draft == submitted) draft = "" }
            },
            onKey = { send(DesktopInput("key", key = it)) },
            onDismiss = { keyboard = false },
        )
    }
}

@Composable
private fun DesktopKeyboard(value: String, edit: (String) -> Unit, enabled: Boolean, sending: Boolean, error: String?,
                            onSend: () -> Unit, onKey: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.desktop_keyboard), style = MaterialTheme.typography.titleLarge)
            val tooLong = value.codePointCount(0, value.length) > 2048
            OutlinedTextField(value, edit, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.desktop_text_hint)) },
                maxLines = 3, isError = tooLong)
            if (tooLong) Text(stringResource(R.string.desktop_text_limit), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSend, enabled = enabled && value.isNotEmpty() && !sending && !tooLong) { Text(stringResource(R.string.desktop_send_text)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("enter" to "Enter", "backspace" to "⌫", "tab" to "Tab", "escape" to "Esc", "delete" to "Del", "left" to "←", "up" to "↑",
                    "down" to "↓", "right" to "→", "altTab" to "Alt+Tab", "printScreen" to "PrtSc").forEach { (key, label) ->
                    OutlinedButton(onClick = { onKey(key) }, enabled = enabled) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun DesktopSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view, hidden) {
        val bars = WindowInsetsCompat.Type.systemBars()
        val controller = WindowCompat.getInsetsController(window, view)
        val oldBehavior = controller.systemBarsBehavior
        val visible = ViewCompat.getRootWindowInsets(view)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        }
        onDispose {
            if (hidden) {
                controller.systemBarsBehavior = oldBehavior
                for (type in listOf(WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars())) {
                    if (visible?.isVisible(type) != false) controller.show(type)
                }
            }
        }
    }
}
