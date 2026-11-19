package dev.remotecode.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ConnState
import dev.remotecode.data.ServerConfig
import dev.remotecode.data.SessionDetailField
import dev.remotecode.data.connectionIdentity
import dev.remotecode.data.refreshOnChanges
import dev.remotecode.i18n.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

internal class LiveSessionDetail<T> {
    var detail by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun refresh() { requests.tryEmit(Unit) }
}

@Composable
internal fun <T> rememberLiveSessionDetail(
    sessionId: String,
    field: SessionDetailField,
    intervalMs: Long,
    cached: (suspend (ServerConfig) -> T?)? = null,
    read: suspend (ServerConfig) -> T,
): LiveSessionDetail<T> {
    val repo = RemoteCodeApp.instance.repository
    val server by repo.activeServerIdentity.collectAsState()
    val foreground by repo.appForeground.collectAsState()
    val connection by repo.connection.collectAsState()
    val cwd by remember(sessionId) {
        repo.sessions.map { sessions -> sessions.firstOrNull { it.id == sessionId }?.cwd }.distinctUntilChanged()
    }.collectAsState(initial = repo.session(sessionId)?.cwd)
    val state = remember(sessionId, field, server, cwd) { LiveSessionDetail<T>() }
    LaunchedEffect(state, foreground, connection is ConnState.Connected) {
        if (!foreground) return@LaunchedEffect
        val target = repo.api.server?.takeIf { it.connectionIdentity() == server }
        if (target == null) {
            state.error = appString(R.string.core_server_not_configured)
            state.loading = false
            return@LaunchedEffect
        }
        var firstRead = true
        merge(repo.detailChanges(sessionId, field), state.requests).refreshOnChanges({ intervalMs }) {
            state.loading = true
            try {
                if (firstRead && state.detail == null && cached != null) {
                    try { state.detail = cached(target) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The fresh read below also works without a saved breakdown. */ }
                }
                firstRead = false
                state.detail = read(target)
                state.error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.error = e.message ?: appString(R.string.chat_context_sheet_030)
            } finally {
                state.loading = false
            }
        }
    }
    return state
}
