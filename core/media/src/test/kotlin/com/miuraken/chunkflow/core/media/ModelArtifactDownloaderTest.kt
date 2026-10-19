package com.miuraken.chunkflow.core.media

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
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

class ModelArtifactDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        tempDir = Files.createTempDirectory("chunkflow-model-downloader-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        tempDir.deleteRecursively()
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
