package dev.remotecode.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GitModelsTest {
    @Test fun gitWritesDoNotAutomaticallyFollowRedirects() = runBlocking {
        var calls = 0
        val api = Api(strings = testAppStrings, client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("Temporary Redirect")
                .header("Location", "https://remote.example/another-write")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()).apply { server = ServerConfig("https://remote.example", "token", "PC") }
        try {
            api.gitAction("session", "commit", message = "A commit")
            fail("Redirect must be returned to the user")
        } catch (error: ApiException) {
            assertEquals(307, error.code)
        }
        assertEquals(1, calls)
    }

    @Test fun omittedFieldsAndUnknownServerFieldsRemainReadable() {
        val status = AppJson.decodeFromString(GitStatus.serializer(), """{"isRepo":true,"branch":"feature/日本語","changedFiles":2,"additions":4,"futureField":true}""")
        assertEquals("feature/日本語", status.branchLabel())
        assertEquals(2, status.changedFiles)
        assertEquals(4, status.additions)
        assertEquals(0, status.deletions)
        assertTrue(status.files.isEmpty())
        assertFalse(status.lineStatsPartial)
        assertEquals("HEAD abcdef12", GitStatus(detached = true, head = "abcdef12345678").branchLabel())
        assertEquals("コミット前", GitStatus().branchLabel { "コミット前" })
    }

    @Test fun indexAndWorktreeChangesForTheSamePathAreNotCollapsed() {
        val status = AppJson.decodeFromString(GitStatus.serializer(), """{"isRepo":true,"files":[{"path":"a #1.kt","originalPath":"old.kt","staged":true,"unstaged":true,"indexStatus":"R","worktreeStatus":"M","additions":5,"deletions":2}]}""")
        val file = status.files.single()
        assertTrue(file.staged)
        assertTrue(file.unstaged)
        assertEquals("old.kt", file.originalPath)
        assertEquals(5, file.additions)
    }

    @Test fun diffQueryPreservesPathsAndMutationsSerializeOnlyTheirOwnArguments() = runBlocking {
        val requests = mutableListOf<Pair<Request, String>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requests.add(request to buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Api(strings = testAppStrings, client = client).apply { server = ServerConfig("https://remote.example", "token", "PC") }
        val path = "a folder/日本語 #1&?.kt"
        api.gitDiff("session", path, staged = true)
        assertEquals(path, requests[0].first.url.queryParameter("path"))
        assertEquals("1", requests[0].first.url.queryParameter("staged"))
        api.gitAction("session", "stage", paths = listOf(path))
        assertEquals(path, AppJson.parseToJsonElement(requests[1].second).jsonObject.getValue("paths").jsonArray.single().jsonPrimitive.content)
        api.gitAction("session", "commit", message = "First line\n\nSecond line")
        val commit = AppJson.parseToJsonElement(requests[2].second).jsonObject
        assertEquals(setOf("message"), commit.keys)
        assertEquals("First line\n\nSecond line", commit.getValue("message").jsonPrimitive.content)
        api.gitAction("session", "push", remote = "origin", setUpstream = true)
        val push = AppJson.parseToJsonElement(requests[3].second).jsonObject
        assertEquals(setOf("remote", "setUpstream"), push.keys)
        assertEquals("origin", push.getValue("remote").jsonPrimitive.content)
    }
}
