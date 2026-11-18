package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
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

class ClientMessageIdTest {
    @Test fun sendPostsTheOptionalIdAlongsideTheImage() = runBlocking {
        val id = "ec15f555-962d-46a8-9da9-2237ab7cf7a2"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/api/sessions/chat/messages", request.url.encodedPath)
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = AppJson.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(id, body.getValue("clientMessageId").jsonPrimitive.content)
            assertEquals("image/png", body.getValue("images").jsonArray.single().jsonObject.getValue("mime").jsonPrimitive.content)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Api(client, strings = testAppStrings).apply { server = ServerConfig("https://remote.example", "token", "PC") }
        api.send("chat", "Read this", listOf(ImagePayload(mime = "image/png", data = "data")), clientMessageId = id)
        val receipt = AppJson.decodeFromString(Entry.serializer(), """{"id":"pc-item","kind":"user","clientMessageId":"$id"}""")
        assertEquals(id, receipt.clientMessageId)
        assertNull(AppJson.decodeFromString(Entry.serializer(), """{"id":"old","kind":"user"}""").clientMessageId)
    }
}
