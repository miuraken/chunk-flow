package com.miuraken.chunkflow.core.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * クラウド文字起こし API の HTTP の応答。
 *
 * @property code HTTP のステータスコード。
 * @property body 応答の本文（UTF-8）。2xx のときは結果の本文（[CloudHttpClient.MAX_SUCCESS_BODY_BYTES] まで）、
 *   それ以外はエラーの本文（[CloudHttpClient.MAX_ERROR_BODY_BYTES] まで）。本文が無いときや読めないときは空文字。
 *   エラーの本文には外部の説明文が入るので、ログに出さない（AGENTS.md §10）。
 */
internal data class CloudHttpResponse(
    val code: Int,
    val body: String,
)

/**
 * クラウド文字起こし API に要求を送る境界（テストでは差し替える）。
 */
internal fun interface CloudHttpClient {
    /**
     * [url] に [body] を POST して、応答を返す。
     *
     * HTTP のエラー（2xx 以外）は例外にせず [CloudHttpResponse] で返す。通信そのものに失敗したときは
     * [IOException] などの例外を投げる（呼び出し側でまとめて扱う）。
     *
     * @param headers 要求ヘッダ。API キーを含むので、実装も呼び出し側もログに出さない。
     */
    fun post(url: String, headers: Map<String, String>, body: ByteArray): CloudHttpResponse

    companion object {
        /** エラーの本文を読む上限（16 KB）。Google のエラーの JSON はふつう 1〜2 KB。 */
        const val MAX_ERROR_BODY_BYTES: Int = 16 * 1024

        /** 成功の応答の本文を読む上限（4 MB）。文字起こしの結果はふつう数 KB。 */
        const val MAX_SUCCESS_BODY_BYTES: Int = 4 * 1024 * 1024
    }
}

/** [HttpURLConnection] で送る [CloudHttpClient] の実装（本番で使う）。 */
internal object HttpUrlConnectionCloudHttpClient : CloudHttpClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): CloudHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            doInput = true
            useCaches = false
            for ((name, value) in headers) {
                setRequestProperty(name, value)
            }
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            connection.outputStream.use { out ->
                out.write(body)
                out.flush()
            }
            val code = connection.responseCode
            val responseBody = if (code in 200..299) {
                connection.inputStream.use { readUtf8Bounded(it, CloudHttpClient.MAX_SUCCESS_BODY_BYTES) }
            } else {
                readErrorBody(connection)
            }
            CloudHttpResponse(code = code, body = responseBody)
        } finally {
            connection.disconnect()
        }
    }

    /** エラーの本文を上限付きで読む。本文が無い・読めないときは空文字にし、HTTP コードだけで扱う。 */
    private fun readErrorBody(connection: HttpURLConnection): String = try {
        connection.errorStream?.use { readUtf8Bounded(it, CloudHttpClient.MAX_ERROR_BODY_BYTES) }.orEmpty()
    } catch (_: IOException) {
        ""
    }
}

/**
 * [input] から最大 [maxBytes] バイトだけ読み、UTF-8 の文字列として返す（不正なバイト列は置換文字になる）。
 *
 * 上限を超える分は読まない（信頼できない応答でメモリを使い過ぎないため）。
 */
internal fun readUtf8Bounded(input: InputStream, maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must not be negative: $maxBytes" }
    val out = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_BYTES))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

private const val READ_BUFFER_BYTES = 8 * 1024
