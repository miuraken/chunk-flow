package com.miuraken.chunkflow.core.drive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [HttpUrlConnectionDriveSyncClient] および [DefaultDriveAuthProvider] の単体テスト（§7.1、§8.1、§9.2）。
 */
class DriveSyncClientTest {

    private lateinit var server: HttpServer
    private lateinit var tempDir: File
    private lateinit var authProvider: DefaultDriveAuthProvider
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("chunkflow-drive-test").toFile()
        authProvider = DefaultDriveAuthProvider().apply {
            setAccessToken("test-oauth-token")
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val port = server.address.port
        baseUrl = "http://127.0.0.1:$port"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        tempDir.deleteRecursively()
    }

    @Test
    fun `listChunkFlowCourses_コース直下の音声とtranscription内のJSONを列挙する`() = runTest {
        server.createContext("/drive/v3/files") { exchange ->
            val query = queryParam(exchange, "q").orEmpty()
            val responseJson = when {
                query.contains("name = 'ChunkFlow'") -> """
                    {
                      "files": [
                        {
                          "id": "root-1",
                          "name": "ChunkFlow",
                          "mimeType": "application/vnd.google-apps.folder",
                          "modifiedTime": "2026-04-01T00:00:00Z"
                        }
                      ]
                    }
                """.trimIndent()

                query.contains("'root-1' in parents") -> """
                    {
                      "files": [
                        {
                          "id": "course-1",
                          "name": "HSK4",
                          "mimeType": "application/vnd.google-apps.folder",
                          "modifiedTime": "2026-04-01T01:00:00Z"
                        }
                      ]
                    }
                """.trimIndent()

                query.contains("'course-1' in parents") -> """
                    {
                      "files": [
                        {
                          "id": "audio-1",
                          "name": "lesson01.mp3",
                          "mimeType": "audio/mpeg",
                          "size": "1024",
                          "modifiedTime": "2026-04-02T12:00:00Z",
                          "sha256Checksum": "${"a".repeat(64)}"
                        },
                        {
                          "id": "trans-dir-1",
                          "name": "transcription",
                          "mimeType": "application/vnd.google-apps.folder",
                          "modifiedTime": "2026-04-02T12:00:00Z"
                        }
                      ]
                    }
                """.trimIndent()

                query.contains("'trans-dir-1' in parents") -> """
                    {
                      "files": [
                        {
                          "id": "json-1",
                          "name": "lesson01.chunkflow.json",
                          "mimeType": "application/json",
                          "size": "512",
                          "modifiedTime": "2026-04-03T08:30:00Z"
                        }
                      ]
                    }
                """.trimIndent()

                else -> """{"files": []}"""
            }
            sendJson(exchange, 200, responseJson)
        }

        val client = createClient()
        val result = client.listChunkFlowCourses()
        val success = assertIs<DriveListResult.Success>(result)
        assertEquals(1, success.courses.size)
        val course = success.courses.single()
        assertEquals("HSK4", course.courseName)
        assertEquals(1, course.audioFiles.size)
        assertEquals("audio-1", course.audioFiles.single().id)
        assertEquals("lesson01.mp3", course.audioFiles.single().name)
        assertEquals("a".repeat(64), course.audioFiles.single().sha256Checksum)
        assertEquals(1, course.transcriptFiles.size)
        assertEquals("json-1", course.transcriptFiles.single().id)
        assertEquals("lesson01.chunkflow.json", course.transcriptFiles.single().name)
    }

    @Test
    fun `listChunkFlowCourses_ChunkFlowフォルダが存在しない場合はFOLDER_NOT_FOUNDを返す`() = runTest {
        server.createContext("/drive/v3/files") { exchange ->
            sendJson(exchange, 200, """{"files": []}""")
        }

        val client = createClient()
        val result = client.listChunkFlowCourses()
        val failure = assertIs<DriveListResult.Failure>(result)
        assertEquals(DriveError.FOLDER_NOT_FOUND, failure.error)
    }

    @Test
    fun `listChunkFlowCourses_未認証またはHTTP401の場合はAUTHを返す`() = runTest {
        authProvider.setAccessToken(null)
        val client = createClient()
        val unauthResult = client.listChunkFlowCourses()
        assertEquals(DriveError.AUTH, assertIs<DriveListResult.Failure>(unauthResult).error)

        authProvider.setAccessToken("expired-token")
        server.createContext("/drive/v3/files") { exchange ->
            sendJson(exchange, 401, """{"error": {"code": 401}}""")
        }
        val http401Result = client.listChunkFlowCourses()
        assertEquals(DriveError.AUTH, assertIs<DriveListResult.Failure>(http401Result).error)
    }

    @Test
    fun `listChunkFlowCourses_不正なJSON応答の場合はINVALID_RESPONSEを返す`() = runTest {
        server.createContext("/drive/v3/files") { exchange ->
            sendJson(exchange, 200, "{broken json")
        }

        val client = createClient()
        val result = client.listChunkFlowCourses()
        val failure = assertIs<DriveListResult.Failure>(result)
        assertEquals(DriveError.INVALID_RESPONSE, failure.error)
    }

    @Test
    fun `downloadToFile_正常にダウンロードしSHA256を返す`() = runTest {
        val payload = "hello-chunkflow-audio-payload".toByteArray(Charsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        server.createContext("/drive/v3/files/audio-1") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val destFile = File(tempDir, "downloaded.mp3")
        val client = createClient()
        val result = client.downloadToFile("audio-1", destFile)
        val success = assertIs<DriveDownloadResult.Success>(result)
        assertEquals(payload.size.toLong(), success.bytesWritten)
        assertEquals(expectedSha, success.sha256Hex)
        assertTrue(destFile.exists())
        assertContentEquals(payload, destFile.readBytes())
    }

    @Test
    fun `downloadToFile_上限サイズを超えるストリームは中断しファイルを削除してTOO_LARGEを返す`() = runTest {
        val payload = ByteArray(4096) { 0x41 }
        server.createContext("/drive/v3/files/huge-audio") { exchange ->
            // チャンク転送（Content-Length 不明）でもストリーム実測で上限を強制することを確認
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { it.write(payload) }
        }

        val destFile = File(tempDir, "huge.mp3")
        val client = createClient()
        val result = client.downloadToFile(
            fileId = "huge-audio",
            destFile = destFile,
            maxSizeBytes = 1024L,
        )
        val failure = assertIs<DriveDownloadResult.Failure>(result)
        assertEquals(DriveError.TOO_LARGE, failure.error)
        assertFalse(destFile.exists())
    }

    @Test
    fun `downloadToFile_ストリーム途中切断時は不完全なファイルを削除しNETWORKを返す`() = runTest {
        server.createContext("/drive/v3/files/truncated-audio") { exchange ->
            // Content-Length 1000 に対して 100 バイトだけ送って切断する
            exchange.sendResponseHeaders(200, 1000L)
            try {
                exchange.responseBody.write(ByteArray(100) { 0x22 })
                exchange.responseBody.close()
            } catch (_: Exception) {
                // テスト用の途中切断
            }
        }

        val destFile = File(tempDir, "truncated.mp3")
        val client = createClient()
        val result = client.downloadToFile("truncated-audio", destFile)
        val failure = assertIs<DriveDownloadResult.Failure>(result)
        assertEquals(DriveError.NETWORK, failure.error)
        assertFalse(destFile.exists())
    }

    @Test
    fun `uploadTranscriptJson_transcriptionフォルダが無ければ作成し新規アップロードと既存更新ができる`() = runTest {
        var existingTranscriptId: String? = null
        var lastUploadedPayload: ByteArray = ByteArray(0)

        server.createContext("/drive/v3/files") { exchange ->
            if (exchange.requestMethod == "GET") {
                val query = queryParam(exchange, "q").orEmpty()
                val responseJson = when {
                    query.contains("name = 'ChunkFlow'") -> """
                        {
                          "files": [
                            {
                              "id": "root-1",
                              "name": "ChunkFlow",
                              "mimeType": "application/vnd.google-apps.folder"
                            }
                          ]
                        }
                    """.trimIndent()

                    query.contains("'root-1' in parents") && query.contains("name = 'CourseA'") -> """
                        {
                          "files": [
                            {
                              "id": "course-a",
                              "name": "CourseA",
                              "mimeType": "application/vnd.google-apps.folder"
                            }
                          ]
                        }
                    """.trimIndent()

                    query.contains("'course-a' in parents") && query.contains("name = 'transcription'") -> """
                        {"files": []}
                    """.trimIndent()

                    query.contains("'trans-created' in parents") && query.contains("name = 'ep1.chunkflow.json'") -> {
                        val id = existingTranscriptId
                        if (id == null) {
                            """{"files": []}"""
                        } else {
                            """
                                {
                                  "files": [
                                    {
                                      "id": "$id",
                                      "name": "ep1.chunkflow.json",
                                      "mimeType": "application/json",
                                      "modifiedTime": "2026-04-04T00:00:00Z"
                                    }
                                  ]
                                }
                            """.trimIndent()
                        }
                    }

                    else -> """{"files": []}"""
                }
                sendJson(exchange, 200, responseJson)
            } else if (exchange.requestMethod == "POST") {
                // transcription フォルダの作成
                exchange.requestBody.readBytes()
                sendJson(
                    exchange,
                    200,
                    """
                    {
                      "id": "trans-created",
                      "name": "transcription",
                      "mimeType": "application/vnd.google-apps.folder",
                      "modifiedTime": "2026-04-04T00:00:00Z"
                    }
                    """.trimIndent(),
                )
            }
        }

        server.createContext("/upload/drive/v3/files") { exchange ->
            val bodyBytes = exchange.requestBody.readBytes()
            lastUploadedPayload = bodyBytes
            val path = exchange.requestURI.path
            if (path.endsWith("/files")) {
                // 新規 multipart アップロード
                existingTranscriptId = "uploaded-json-1"
                sendJson(
                    exchange,
                    200,
                    """
                    {
                      "id": "uploaded-json-1",
                      "name": "ep1.chunkflow.json",
                      "mimeType": "application/json",
                      "size": "${bodyBytes.size}",
                      "modifiedTime": "2026-04-04T01:00:00Z"
                    }
                    """.trimIndent(),
                )
            } else {
                // 既存ファイルの更新（X-HTTP-Method-Override: PATCH）
                assertEquals("PATCH", exchange.requestHeaders.getFirst("X-HTTP-Method-Override"))
                sendJson(
                    exchange,
                    200,
                    """
                    {
                      "id": "uploaded-json-1",
                      "name": "ep1.chunkflow.json",
                      "mimeType": "application/json",
                      "size": "${bodyBytes.size}",
                      "modifiedTime": "2026-04-04T02:00:00Z"
                    }
                    """.trimIndent(),
                )
            }
        }

        val client = createClient()
        val firstBytes = """{"format":"chunkflow-transcript","version":1}""".toByteArray(Charsets.UTF_8)
        val createResult = client.uploadTranscriptJson("CourseA", "ep1.chunkflow.json", firstBytes)
        val created = assertIs<DriveUploadResult.Success>(createResult).file
        assertEquals("uploaded-json-1", created.id)
        assertTrue(lastUploadedPayload.decodeToString().contains("chunkflow-transcript"))

        val secondBytes = """{"format":"chunkflow-transcript","version":1,"updated":true}""".toByteArray(Charsets.UTF_8)
        val updateResult = client.uploadTranscriptJson("CourseA", "ep1.chunkflow.json", secondBytes)
        val updated = assertIs<DriveUploadResult.Success>(updateResult).file
        assertEquals("uploaded-json-1", updated.id)
        assertContentEquals(secondBytes, lastUploadedPayload)
    }

    private fun createClient(): HttpUrlConnectionDriveSyncClient = HttpUrlConnectionDriveSyncClient(
        authProvider = authProvider,
        apiBaseUrl = "$baseUrl/drive/v3",
        uploadBaseUrl = "$baseUrl/upload/drive/v3",
    )

    private fun queryParam(exchange: HttpExchange, key: String): String? {
        val rawQuery = exchange.requestURI.rawQuery ?: return null
        for (pair in rawQuery.split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && URLDecoder.decode(parts[0], Charsets.UTF_8.name()) == key) {
                return URLDecoder.decode(parts[1], Charsets.UTF_8.name())
            }
        }
        return null
    }

    private fun sendJson(exchange: HttpExchange, status: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `HttpUrlConnectionModelArtifactDownloader_SHA256とサイズが一致するモデル成果物を取得し不一致時は破棄する`() = runTest {
        val payload = "onnx-model-test-payload".toByteArray(Charsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        server.createContext("/models/tiny-encoder.int8.onnx") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val downloader = HttpUrlConnectionModelArtifactDownloader()
        val destFile = File(tempDir, "tiny-encoder.int8.onnx")

        // 1. 正常系: サイズと SHA-256 が一致する場合は成功する
        val okResult = downloader.downloadVerifiedFile(
            url = "$baseUrl/models/tiny-encoder.int8.onnx",
            destFile = destFile,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256Hex = expectedSha,
        )
        assertIs<ModelArtifactDownloadResult.Success>(okResult)
        assertTrue(destFile.exists())
        assertContentEquals(payload, destFile.readBytes())

        // 2. 異常系: SHA-256 が一致しない場合は失敗し、壊れたファイルが残らない
        val badShaDest = File(tempDir, "bad-sha.onnx")
        val badShaResult = downloader.downloadVerifiedFile(
            url = "$baseUrl/models/tiny-encoder.int8.onnx",
            destFile = badShaDest,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256Hex = "0".repeat(64),
        )
        val badShaFailure = assertIs<ModelArtifactDownloadResult.Failure>(badShaResult)
        assertEquals(ModelArtifactDownloadResult.Failure.Reason.CHECKSUM_MISMATCH, badShaFailure.reason)
        assertFalse(badShaDest.exists())

        // 3. 異常系: 許可されていないホストや平文 HTTP (localhost 以外) は拒否する
        val untrustedDest = File(tempDir, "untrusted.onnx")
        val untrustedResult = downloader.downloadVerifiedFile(
            url = "https://evil.example.com/model.onnx",
            destFile = untrustedDest,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256Hex = expectedSha,
        )
        val untrustedFailure = assertIs<ModelArtifactDownloadResult.Failure>(untrustedResult)
        assertEquals(ModelArtifactDownloadResult.Failure.Reason.DISALLOWED_URL, untrustedFailure.reason)
        assertFalse(untrustedDest.exists())
    }
}
