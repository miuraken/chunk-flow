package com.miuraken.chunkflow.core.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 音声認識モデル等の外部アーティファクト取得結果（F6.2、§8.1）。
 */
sealed interface ModelArtifactDownloadResult {
    /**
     * ダウンロードおよび整合性検証に成功した結果。
     *
     * @property bytesWritten 書き込まれたバイト数。
     * @property sha256Hex 書き込まれたファイルの SHA-256（小文字 16 進文字列）。
     */
    data class Success(
        val bytesWritten: Long,
        val sha256Hex: String,
    ) : ModelArtifactDownloadResult

    /**
     * ダウンロードまたは検証に失敗した結果。
     */
    data class Failure(
        val reason: Reason,
    ) : ModelArtifactDownloadResult {
        enum class Reason {
            /** 許可されていないスキームまたはホスト。 */
            DISALLOWED_URL,

            /** HTTP ステータスエラー（404 / 500 等）。 */
            HTTP_ERROR,

            /** サイズ上限超過または期待サイズ不一致。 */
            TOO_LARGE,

            /** SHA-256 ハッシュ不一致。 */
            CHECKSUM_MISMATCH,

            /** 通信エラー・タイムアウト・I/O エラー。 */
            NETWORK,
        }
    }
}

/**
 * モデル成果物 1 ファイルのダウンロード・検証仕様（Stage 2、F6.2）。
 */
data class ModelArtifactDownloadSpec(
    val url: String,
    val expectedSizeBytes: Long? = null,
    val expectedSha256Hex: String? = null,
    val maxSizeBytes: Long = ModelArtifactDownloader.DEFAULT_MAX_MODEL_FILE_BYTES,
)

/**
 * Hugging Face / GitHub Releases 等からローカル音声認識モデル（`sherpa-onnx` 等）のファイルを
 * HTTPS で安全にダウンロード・検証するインターフェース（AGENTS.md §6、§8.1）。
 */
interface ModelArtifactDownloader {
    /**
     * [url] からファイルをダウンロードし、サイズと SHA-256 を検証したうえで [destFile] に配置する。
     *
     * @param url 取得元 URL（HTTPS のみ許可。単体テスト用に `http://127.0.0.1` / `http://localhost` を許可）。
     * @param destFile 保存先ファイル。検証完了までは同一ディレクトリの `.part` 一時ファイルに書き込む。
     * @param expectedSizeBytes 期待する正確なバイトサイズ（`null` の場合は [maxSizeBytes] 以下であることのみ検証）。
     * @param expectedSha256Hex 期待する SHA-256 ハッシュ（64 文字 16 進数、`null` の場合は検証しない）。
     * @param maxSizeBytes 許容する最大バイトサイズ（既定 300 MB）。
     */
    suspend fun downloadVerifiedFile(
        url: String,
        destFile: File,
        expectedSizeBytes: Long? = null,
        expectedSha256Hex: String? = null,
        maxSizeBytes: Long = DEFAULT_MAX_MODEL_FILE_BYTES,
    ): ModelArtifactDownloadResult

    /**
     * [spec] に従って [destinationFile] へダウンロード・検証を行い、成功時は `true` を返す。
     */
    suspend fun downloadToFile(
        spec: ModelArtifactDownloadSpec,
        destinationFile: File,
    ): Boolean = downloadVerifiedFile(
        url = spec.url,
        destFile = destinationFile,
        expectedSizeBytes = spec.expectedSizeBytes,
        expectedSha256Hex = spec.expectedSha256Hex,
        maxSizeBytes = spec.maxSizeBytes,
    ) is ModelArtifactDownloadResult.Success

    companion object {
        /** 1 ファイルあたりの最大ダウンロード上限（300 MB）。 */
        const val DEFAULT_MAX_MODEL_FILE_BYTES: Long = 300L * 1024L * 1024L
    }
}

/**
 * [HttpURLConnection] を用いた [ModelArtifactDownloader] の本番実装（AGENTS.md §6、§8.1）。
 *
 * - リダイレクト（301 / 302 / 303 / 307 / 308）を最大 [MAX_REDIRECTS] 回まで追跡し、
 *   各ホップで HTTPS と許可ドメイン（Hugging Face / GitHub またはローカルテストサーバ）を検証する。
 * - ストリーミング中にサイズ上限と SHA-256 を計算し、検証通過後にのみ最終ファイルへアトミックに置換する。
 */
@Singleton
class HttpUrlConnectionModelArtifactDownloader(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : ModelArtifactDownloader {

    @Inject
    constructor() : this(
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
    )

    override suspend fun downloadVerifiedFile(
        url: String,
        destFile: File,
        expectedSizeBytes: Long?,
        expectedSha256Hex: String?,
        maxSizeBytes: Long,
    ): ModelArtifactDownloadResult {
        val effectiveMaxBytes = if (expectedSizeBytes != null && expectedSizeBytes > 0L) {
            minOf(maxSizeBytes, expectedSizeBytes)
        } else {
            maxSizeBytes
        }
        if (effectiveMaxBytes <= 0L) {
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.TOO_LARGE)
        }

        val parentDir = destFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.NETWORK)
        }
        val tempFile = File(parentDir, "${destFile.name}.part")

        var currentUrl = url.trim()
        var redirectCount = 0
        var connection: HttpURLConnection? = null

        try {
            while (redirectCount <= MAX_REDIRECTS) {
                currentCoroutineContext().ensureActive()
                if (!isAllowedDownloadUrl(currentUrl)) {
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.DISALLOWED_URL,
                    )
                }

                connection?.disconnect()
                val opened = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/octet-stream, */*")
                }
                connection = opened

                val status = opened.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        return ModelArtifactDownloadResult.Failure(
                            ModelArtifactDownloadResult.Failure.Reason.HTTP_ERROR,
                        )
                    }
                    val location = opened.getHeaderField("Location")?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return ModelArtifactDownloadResult.Failure(
                            ModelArtifactDownloadResult.Failure.Reason.HTTP_ERROR,
                        )
                    val resolvedUri = URI.create(currentUrl).resolve(location)
                    currentUrl = resolvedUri.toString()
                    redirectCount += 1
                    continue
                }

                if (status !in 200..299) {
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.HTTP_ERROR,
                    )
                }

                val contentLength = opened.contentLengthLong
                if (contentLength > effectiveMaxBytes) {
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.TOO_LARGE,
                    )
                }
                if (expectedSizeBytes != null && contentLength > 0L && contentLength != expectedSizeBytes) {
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.TOO_LARGE,
                    )
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                val buffer = ByteArray(STREAM_BUFFER_BYTES)

                opened.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            totalBytes += read
                            if (totalBytes > effectiveMaxBytes) {
                                output.close()
                                tempFile.delete()
                                return ModelArtifactDownloadResult.Failure(
                                    ModelArtifactDownloadResult.Failure.Reason.TOO_LARGE,
                                )
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }

                if (totalBytes <= 0L || (contentLength > 0L && totalBytes != contentLength)) {
                    tempFile.delete()
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.NETWORK,
                    )
                }
                if (expectedSizeBytes != null && totalBytes != expectedSizeBytes) {
                    tempFile.delete()
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.TOO_LARGE,
                    )
                }

                val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                val normalizedExpectedSha = expectedSha256Hex?.trim()?.lowercase(Locale.ROOT)
                if (!normalizedExpectedSha.isNullOrEmpty() && sha256Hex != normalizedExpectedSha) {
                    tempFile.delete()
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.CHECKSUM_MISMATCH,
                    )
                }

                if (destFile.exists() && !destFile.delete()) {
                    tempFile.delete()
                    return ModelArtifactDownloadResult.Failure(
                        ModelArtifactDownloadResult.Failure.Reason.NETWORK,
                    )
                }
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                return ModelArtifactDownloadResult.Success(
                    bytesWritten = totalBytes,
                    sha256Hex = sha256Hex,
                )
            }
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.HTTP_ERROR)
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (_: IOException) {
            tempFile.delete()
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.NETWORK)
        } catch (_: IllegalArgumentException) {
            tempFile.delete()
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.DISALLOWED_URL)
        } catch (_: SecurityException) {
            tempFile.delete()
            return ModelArtifactDownloadResult.Failure(ModelArtifactDownloadResult.Failure.Reason.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS: Int = 60_000
        private const val STREAM_BUFFER_BYTES: Int = 64 * 1024
        private const val MAX_REDIRECTS: Int = 5

        private val REDIRECT_STATUS_CODES: Set<Int> = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )

        private val ALLOWED_HTTPS_HOST_SUFFIXES: List<String> = listOf(
            "huggingface.co",
            ".huggingface.co",
            ".hf.co",
            "github.com",
            ".github.com",
            ".githubusercontent.com",
        )

        internal fun isAllowedDownloadUrl(urlString: String): Boolean {
            val uri = try {
                URI.create(urlString)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            if (scheme == "http" && (host == "127.0.0.1" || host == "localhost")) {
                return true
            }
            if (scheme != "https") return false
            return ALLOWED_HTTPS_HOST_SUFFIXES.any { suffix ->
                if (suffix.startsWith(".")) {
                    host.endsWith(suffix)
                } else {
                    host == suffix
                }
            }
        }
    }
}
