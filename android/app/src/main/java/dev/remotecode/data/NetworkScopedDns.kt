package dev.remotecode.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal fun networkScopedDns(context: Context): Dns {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return Dns.SYSTEM
    return RecentDns(
        scope = current@{
            val network = manager.boundNetworkForProcess ?: manager.activeNetwork ?: return@current null
            manager.getLinkProperties(network)?.let { network to it }
        },
        resolve = { (network, _), host -> network.getAllByName(host).asList() },
        elapsedMs = SystemClock::elapsedRealtime,
    )
}

/** Pool misses during reconnect can resolve the same name on several OkHttp threads. */
internal class RecentDns<N : Any>(
    private val scope: () -> N?,
    private val resolve: (N, String) -> List<InetAddress>,
    private val elapsedMs: () -> Long,
) : Dns {
    private data class Key<N>(val network: N, val hostname: String)
    private data class Answer(val addresses: List<InetAddress>, val expiresAt: Long)

    private val pending = mutableMapOf<Key<N>, FutureTask<List<InetAddress>>>()
    private val answers = LinkedHashMap<Key<N>, Answer>(16, 0.75f, true)

    override fun lookup(hostname: String): List<InetAddress> {
        val network = scope() ?: return Dns.SYSTEM.lookup(hostname)
        val key = Key(network, hostname)
        val task = synchronized(pending) {
            val recent = answers[key]
            if (recent != null && elapsedMs() < recent.expiresAt) return recent.addresses
            pending.getOrPut(key) {
                FutureTask {
                    resolve(network, hostname).also { addresses ->
                        if (addresses.isNotEmpty()) synchronized(pending) {
                            answers[key] = Answer(addresses, elapsedMs() + 15_000)
                            if (answers.size > 16) answers.remove(answers.keys.first())
                        }
                    }
                }
            }
        }

        task.run() // Only the first caller executes the system lookup.
        try {
            return task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException(hostname).apply { initCause(e) }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            if (task.isDone) synchronized(pending) { pending.remove(key, task) }
        }
    }
}
