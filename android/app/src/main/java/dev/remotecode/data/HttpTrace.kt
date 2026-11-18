package dev.remotecode.data

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol

internal class HttpTrace(private val now: () -> Long = System::nanoTime) : EventListener() {
    private val created = now()
    private var dispatched: Long? = null
    private var callStarted: Long? = null
    private var dnsStarted: Long? = null
    private var connectStarted: Long? = null
    private var tlsStarted: Long? = null
    private var acquired: Long? = null
    private var connected = false
    private var dns = 0L
    private var connect = 0L
    private var tls = 0L
    private var hasDns = false
    private var hasConnect = false
    private var hasTls = false

    @Synchronized fun dispatched() { dispatched = now() }
    @Synchronized override fun callStart(call: Call) { callStarted = now() }
    @Synchronized override fun dnsStart(call: Call, domainName: String) { hasDns = true; dnsStarted = now() }
    @Synchronized override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dnsStarted?.let { dns += now() - it }; dnsStarted = null }
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { hasConnect = true; connectStarted = now() }
    @Synchronized override fun secureConnectStart(call: Call) { hasTls = true; tlsStarted = now() }
    @Synchronized override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsStarted?.let { tls += now() - it }; tlsStarted = null }
    @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { finishConnect() }
    @Synchronized override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { finishConnect() }
    @Synchronized override fun connectionAcquired(call: Call, connection: Connection) { connected = true; acquired = now() }

    private fun finishConnect() { connectStarted?.let { connect += now() - it }; connectStarted = null }

    @Synchronized fun snapshot(): HttpTraceSnapshot = HttpTraceSnapshot(
        queueMs = dispatched?.let { ((it - created) / 1_000_000).coerceAtLeast(0) },
        dnsMs = if (hasDns) dns / 1_000_000 else null,
        connectMs = if (hasConnect) connect / 1_000_000 else null,
        tlsMs = if (hasTls) tls / 1_000_000 else null,
        connectionWaitMs = if (connected) ((acquired!! - (callStarted ?: created) - dns - connect) / 1_000_000).coerceAtLeast(0) else null,
        reusedConnection = if (connected) !hasConnect else null,
    )
}

internal data class HttpTraceSnapshot(
    val queueMs: Long?, val dnsMs: Long?, val connectMs: Long?, val tlsMs: Long?,
    val connectionWaitMs: Long?, val reusedConnection: Boolean?,
)

internal val HttpTraceFactory = EventListener.Factory { call -> call.request().tag(HttpTrace::class.java) ?: EventListener.NONE }
