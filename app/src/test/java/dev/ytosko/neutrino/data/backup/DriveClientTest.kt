package dev.ytosko.neutrino.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = DriveClient(OkHttpClient(), json, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun respond(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    @Test
    fun `finds the backup only in the app data folder`() = runTest {
        respond(200, """{"files":[{"id":"f1","name":"neutrino-backup.nbk","size":"1234","modifiedTime":"2026-09-25T03:12:00Z"}]}""")
        val file = client().findBackup("tok")
        assertEquals("f1", file?.id)
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("appDataFolder", request.url.queryParameter("spaces"))

        respond(200, """{"files":[]}""")
        assertNull(client().findBackup("tok"))
    }

    @Test
    fun `a new upload starts a resumable session in appDataFolder then sends the bytes`() = runTest {
        val sessionUrl = server.url("/session/abc").toString()
        respond(200, headers = mapOf("Location" to sessionUrl))
        respond(200, """{"id":"new-id","name":"neutrino-backup.nbk"}""")

        val bytes = byteArrayOf(1, 2, 3, 4)
        val result = client().upload("tok", bytes, existingId = null)

        assertEquals("new-id", result.id)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        assertTrue(start.body!!.utf8().contains("appDataFolder"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertArrayEquals(bytes, put.body!!.toByteArray())
    }

    @Test
    fun `replacing a backup patches the existing file without moving it`() = runTest {
        respond(200, headers = mapOf("Location" to server.url("/session/x").toString()))
        respond(200, """{"id":"f1"}""")
        client().upload("tok", byteArrayOf(9), existingId = "f1")
        val start = server.takeRequest()
        assertEquals("PATCH", start.method)
        assertTrue(start.url.encodedPath.endsWith("/files/f1"))
        assertTrue("parents can't be set on update", !start.body!!.utf8().contains("parents"))
    }

    @Test(expected = DriveAuthException::class)
    fun `an expired token is reported so a fresh one can be fetched`() = runTest {
        respond(401, """{"error":{"code":401}}""")
        client().findBackup("old")
    }
}
