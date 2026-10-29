package dev.dsh.mobile.core.protocol.web

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class DownloadTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("dsh-ws").toFile()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun body(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun downloader() = FileDownloader(WorkspaceSandbox(workspace))

    @Test
    fun writesInsideWorkspaceWithBytesAndSha256() = runTest {
        val payload = body(4_096)
        val http = FakeTransport { request -> request.respondBytes(200, payload, "application/octet-stream") }
        val progress = mutableListOf<Pair<Long, Long>>()
        val outcome = downloader().download(
            DownloadRequest(url = "https://example.com/files/a.bin", dir = "downloads"),
            http,
        ) { written, total -> progress += written to total }

        assertEquals("downloads/a.bin", outcome.path)
        assertEquals(4_096L, outcome.bytes)
        assertEquals(sha256(payload), outcome.sha256)
        assertFalse(outcome.renamed)
        val saved = File(workspace, "downloads/a.bin")
        assertTrue(saved.exists())
        assertEquals(4_096L, saved.length())
        assertTrue(progress.isNotEmpty())
        assertEquals(outcome.bytes, progress.last().first)
        assertEquals(0, workspace.walk().count { it.name.endsWith(".part") })
    }

    @Test
    fun traversalOutsideWorkspaceIsRejected() = runTest {
        val http = FakeTransport { request -> request.respondBytes(200, body(10), "application/octet-stream") }
        for (dir in listOf("../escape", "..", "/abs", "C:/tmp", "a/../../b")) {
            val error = runCatching {
                downloader().download(DownloadRequest(url = "https://example.com/x.bin", dir = dir), http)
            }.exceptionOrNull()
            assertTrue("dir=" + dir, error is WebToolException)
            assertEquals("dir=" + dir, WebErrorCode.OutsideWorkspace, (error as WebToolException).code)
        }
        assertTrue(http.requests.isEmpty())
        assertEquals(0, workspace.listFiles()!!.size)
    }

    @Test
    fun filenameConflictGetsSuffixInsteadOfOverwriting() = runTest {
        val existing = File(workspace, "a.bin")
        existing.writeText("old")
        val payload = body(64)
        val http = FakeTransport { request -> request.respondBytes(200, payload, "application/octet-stream") }
        val outcome = downloader().download(
            DownloadRequest(url = "https://example.com/a.bin", fileName = "a.bin"),
            http,
        )
        assertEquals("a-1.bin", outcome.fileName)
        assertTrue(outcome.renamed)
        assertEquals("old", existing.readText())
        assertEquals(sha256(payload), outcome.sha256)
    }

    @Test
    fun overwriteReplacesExistingFile() = runTest {
        val existing = File(workspace, "a.bin")
        existing.writeText("old")
        val payload = body(256)
        val http = FakeTransport { request -> request.respondBytes(200, payload, "application/octet-stream") }
        val outcome = downloader().download(
            DownloadRequest(url = "https://example.com/a.bin", fileName = "a.bin", overwrite = true),
            http,
        )
        assertEquals("a.bin", outcome.fileName)
        assertFalse(outcome.renamed)
        assertTrue(payload.contentEquals(existing.readBytes()))
        assertEquals(sha256(payload), outcome.sha256)
        assertEquals(0, workspace.walk().count { it.name.endsWith(".part") })
    }

    @Test
    fun networkDropCleansUpPartialFile() = runTest {
        val http = FakeTransport { request ->
            request.sink!!.write(ByteArray(300), 300)
            throw WebToolException(WebErrorCode.Network, "连接被重置")
        }
        val error = runCatching {
            downloader().download(DownloadRequest(url = "https://example.com/a.bin"), http)
        }.exceptionOrNull()
        assertEquals(WebErrorCode.Network, (error as WebToolException).code)
        assertEquals(0, workspace.listFiles()!!.size)
    }

    @Test
    fun oversizedDownloadIsRejectedAndCleanedUp() = runTest {
        val http = FakeTransport { request -> request.respondBytes(200, body(5_000), "application/octet-stream") }
        val error = runCatching {
            downloader().download(
                DownloadRequest(url = "https://example.com/big.bin", maxBytes = 1_000),
                http,
            )
        }.exceptionOrNull()
        assertEquals(WebErrorCode.TooLarge, (error as WebToolException).code)
        assertEquals(0, workspace.listFiles()!!.size)
    }

    @Test
    fun non2xxLeavesNothingBehind() = runTest {
        val http = FakeTransport { request -> request.respondBytes(500, body(10), "text/plain") }
        val error = runCatching {
            downloader().download(DownloadRequest(url = "https://example.com/a.bin"), http)
        }.exceptionOrNull()
        assertEquals(WebErrorCode.HttpStatus, (error as WebToolException).code)
        assertEquals(0, workspace.listFiles()!!.size)
    }

    @Test
    fun contentDispositionProvidesFileName() = runTest {
        val payload = body(32)
        val http = FakeTransport { request ->
            request.respondBytes(
                200,
                payload,
                "application/octet-stream",
                mapOf("content-disposition" to "attachment; filename=\"report.csv\""),
            )
        }
        val outcome = downloader().download(
            DownloadRequest(url = "https://example.com/download?id=7"),
            http,
        )
        assertEquals("report.csv", outcome.fileName)
    }

    @Test
    fun downloadToolWithoutWorkspaceIsNotConfigured() = runTest {
        val toolkit = WebToolkit(http = FakeTransport { request -> request.respondBytes(200, body(4), "text/plain") })
        val error = runCatching {
            toolkit.download(buildJsonObject { put("url", "https://example.com/a.bin") })
        }.exceptionOrNull()
        assertEquals(WebErrorCode.NotConfigured, (error as WebToolException).code)
    }

    @Test
    fun downloadToolWritesAndReturnsJson() = runTest {
        val payload = body(128)
        val toolkit = WebToolkit(
            http = FakeTransport { request -> request.respondBytes(200, payload, "application/octet-stream") },
            workspace = workspace,
            settings = { WebSettings() },
        )
        val result = toolkit.download(
            buildJsonObject {
                put("url", "https://example.com/a.bin")
                put("dir", "out")
                put("name", "pkg.bin")
            },
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        assertEquals("out/pkg.bin", json["path"]!!.jsonPrimitive.content)
        assertEquals(sha256(payload), json["sha256"]!!.jsonPrimitive.content)
        assertEquals(128, json["bytes"]!!.jsonPrimitive.content.toInt())
        assertTrue(File(workspace, "out/pkg.bin").exists())
    }
}
