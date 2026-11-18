package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class FastModeTest {
    private val sessionJson = """{"id":"session","provider":"codex","title":"Title","cwd":"C:/project"}"""

    private fun api(requests: MutableList<JsonObject>): Api = Api(
        strings = testAppStrings,
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requests.add(AppJson.parseToJsonElement(buffer.readUtf8()).jsonObject)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sessionJson.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    ).apply { server = ServerConfig("https://remote.example", "token", "PC") }

    @Test fun unrelatedEditsAndUntouchedModelSettingsDoNotOverwritePcFastMode() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val api = api(sent)
        api.patch("session", title = "Renamed")
        api.setModel("session", "gpt-model", "high")
        api.createSession("codex", "C:/project", "", "hello", emptyList())
        assertEquals(3, sent.size)
        sent.forEach { assertFalse(it.containsKey("fastMode")) }
    }

    @Test fun explicitOffIsSentAndModelOrFirstMessageCanIncludeFastInOneRequest() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val api = api(sent)
        api.patch("session", fastMode = false)
        api.setModel("session", "gpt-model", "high", fastMode = true)
        api.createSession("claude", "C:/project", "", "hello", emptyList(), fastMode = true)
        assertEquals(3, sent.size)
        assertFalse(sent[0].getValue("fastMode").jsonPrimitive.boolean)
        assertTrue(sent[1].getValue("fastMode").jsonPrimitive.boolean)
        assertTrue(sent[2].getValue("fastMode").jsonPrimitive.boolean)
        assertEquals("gpt-model", sent[1].getValue("model").jsonPrimitive.content)
        assertEquals("hello", sent[2].getValue("text").jsonPrimitive.content)
    }

    @Test fun olderServersRemainUnknownAndPcOffNeverShowsAnActiveLightningBadge() {
        val older = AppJson.decodeFromString(SessionInfo.serializer(), sessionJson)
        assertNull(older.fastMode)
        assertNull(older.fastModeState)
        assertFalse(older.fastModeActive)
        assertTrue(older.copy(fastMode = true).fastModeActive)
        assertTrue(older.copy(fastMode = true, fastModeState = "cooldown").fastModeActive)
        assertFalse(older.copy(fastMode = true, fastModeState = "off").fastModeActive)
        val option = AppJson.decodeFromString(ModelOption.serializer(), """{"id":"slow-model","label":"Slow","supportsFastMode":false}""")
        assertEquals(false, option.supportsFastMode)
        assertNull(ModelOption("old-model", "Old").supportsFastMode)
    }
}
