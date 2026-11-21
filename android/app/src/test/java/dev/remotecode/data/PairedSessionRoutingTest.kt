package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class PairedSessionRoutingTest {
    @Test fun requestsFromAnOldHostDoNotReachTheNewHost() = runBlocking {
        val oldHost = ServerConfig("https://old.example", "old-token", "Old PC")
        val newHost = ServerConfig("https://new.example", "new-token", "New PC")
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requested.add(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        val api = Api(client, strings = testAppStrings).apply { server = newHost }

        suspend fun assertServerChanged(call: suspend () -> Unit) {
            val failure = runCatching { call() }.exceptionOrNull() as? ApiException
            assertEquals(409, failure?.code)
        }

        assertServerChanged { api.history(Providers.CLAUDE, expectedServer = oldHost) }
        assertServerChanged { api.import(Providers.CLAUDE, HistoryItem("external", "Existing session"), expectedServer = oldHost) }
        assertServerChanged { api.respond("chat", "approval", choice = "allow", expectedServer = oldHost) }
        assertServerChanged { api.delete("chat", expectedServer = oldHost) }
        api.respond("chat", "approval", choice = "allow", expectedServer = newHost)

        assertEquals(listOf("https://new.example/api/sessions/chat/respond"), requested)
    }
}
