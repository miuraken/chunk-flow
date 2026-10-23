package com.miuraken.chunkflow.core.media

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Google の API が返すエラーの JSON（`{"error": {"code", "status", "message", "details": [{"reason"}]}}`）を解析した結果。
 *
 * 本文はオブジェクトのことも、要素 1 つの配列（Vertex AI など）のこともある（[parseGoogleApiError]）。
 * [message] は外部の説明文なのでログに出さない（[toString] にも含めない）。利用者に見せるときは
 * [sanitizeProviderMessage] で API キーを伏せ、短くしてから使う。
 *
 * @property code `error.code`（HTTP のステータスコードと同じ値。無ければ `null`）。
 * @property status `error.status`（`NOT_FOUND`、`RESOURCE_EXHAUSTED` など。英大文字・数字・`_` 以外を含むものは捨てる）。
 * @property message `error.message`（無ければ `null`）。
 * @property reasons `error.details[].reason`（`API_KEY_INVALID`、`API_KEY_SERVICE_BLOCKED` など。出てきた順で重複なし。
 *   英大文字・数字・`_` 以外を含むものは捨てる）。
 */
internal data class GoogleApiError(
    val code: Int? = null,
    val status: String? = null,
    val message: String? = null,
    val reasons: List<String> = emptyList(),
) {
    /** 最初の理由（`error.details[].reason`）。無ければ `null`。 */
    val primaryReason: String?
        get() = reasons.firstOrNull()

    /** ログに出してよい項目だけを含める（外部の説明文 [message] は含めない）。 */
    override fun toString(): String = "GoogleApiError(code=$code, status=$status, reasons=$reasons)"
}

/** Google のエラーの status: リソースの上限に達した（クォータ・前払いクレジットなど）。 */
internal const val GOOGLE_STATUS_RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED"

/** Google のエラーの status: 要求の内容が正しくない。 */
internal const val GOOGLE_STATUS_INVALID_ARGUMENT = "INVALID_ARGUMENT"

/** Google のエラーの reason: キーの「API の制限」で、この API の呼び出しが許可されていない。 */
internal const val GOOGLE_REASON_API_KEY_SERVICE_BLOCKED = "API_KEY_SERVICE_BLOCKED"

/** 外部の JSON の入れ子の深さの上限（org.json の解析は再帰するので、深すぎる JSON は解析しない）。 */
internal const val MAX_EXTERNAL_JSON_DEPTH = 32

/** 利用者に見せる外部の説明文の最大文字数。 */
internal const val MAX_PROVIDER_MESSAGE_CHARS = 200

/** 伏せた API キーの代わりに入れる文字列。 */
internal const val REDACTED_API_KEY = "***"

/** 読む `error.details` の最大件数。 */
private const val MAX_ERROR_DETAILS = 16

/** この長さに満たないキーは本文から置き換えない（短い文字列を置き換えると説明文が壊れるため）。 */
private const val MIN_API_KEY_LENGTH_TO_REDACT = 8

/** status と reason として受け付ける形（英大文字・数字・`_`。ログに出すので、それ以外の文字を含むものは捨てる）。 */
private val ERROR_TOKEN_PATTERN = Regex("[A-Z0-9_]{1,64}")

/** API キーらしい文字列（Google の `AIza…` / `AQ.…`、Groq の `gsk_…`、OpenAI の `sk-…`）。 */
private val API_KEY_LIKE_PATTERN = Regex(
    "\\b(?:AIza[0-9A-Za-z_-]{30,}|AQ\\.[0-9A-Za-z_.-]{20,}|gsk_[0-9A-Za-z]{20,}|sk-[0-9A-Za-z_-]{20,})",
)

/**
 * エラーの本文 [body] を [GoogleApiError] に解析する（純粋関数）。
 *
 * 本文はオブジェクト（`{"error": …}`）でも、要素 1 つの配列（`[{"error": …}]`）でもよい。
 * 空・大きすぎる（[CloudHttpClient.MAX_ERROR_BODY_BYTES] 文字を超える）・入れ子が深すぎる・JSON として壊れている・
 * `error` オブジェクトが無い本文は `null` を返す。
 */
internal fun parseGoogleApiError(body: String?): GoogleApiError? {
    val trimmed = body?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.length > CloudHttpClient.MAX_ERROR_BODY_BYTES) return null
    if (exceedsJsonNestingDepth(trimmed, MAX_EXTERNAL_JSON_DEPTH)) return null
    return try {
        val root = when (trimmed.first()) {
            '{' -> JSONObject(trimmed)
            '[' -> JSONArray(trimmed).optJSONObject(0)
            else -> null
        } ?: return null
        val error = root.optJSONObject("error") ?: return null
        val reasons = LinkedHashSet<String>()
        val details = error.optJSONArray("details")
        if (details != null) {
            for (i in 0 until minOf(details.length(), MAX_ERROR_DETAILS)) {
                details.optJSONObject(i)?.optErrorToken("reason")?.let(reasons::add)
            }
        }
        GoogleApiError(
            code = (error.opt("code") as? Number)?.toInt(),
            status = error.optErrorToken("status"),
            message = (error.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            reasons = reasons.toList(),
        )
    } catch (_: JSONException) {
        null
    }
}

private fun JSONObject.optErrorToken(name: String): String? = (opt(name) as? String)?.trim()?.takeIf { ERROR_TOKEN_PATTERN.matches(it) }

/**
 * 外部の JSON の本文 [text] を [JSONObject] にする。
 *
 * @throws JSONException JSON として壊れているとき、オブジェクトでないとき、入れ子が [MAX_EXTERNAL_JSON_DEPTH] より深いとき。
 */
internal fun parseExternalJsonObject(text: String): JSONObject {
    if (exceedsJsonNestingDepth(text, MAX_EXTERNAL_JSON_DEPTH)) throw JSONException("JSON is nested too deeply")
    return JSONObject(text)
}

/**
 * JSON の文字列 [text] の入れ子（`{` と `[`）が [maxDepth] を超えるかどうか。文字列リテラルの中の括弧は数えない。
 */
internal fun exceedsJsonNestingDepth(text: String, maxDepth: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true

            '{', '[' -> {
                depth += 1
                if (depth > maxDepth) return true
            }

            '}', ']' -> depth -= 1
        }
    }
    return false
}

/**
 * [text] に含まれる API キーを伏せる（防御のため。純粋関数）。
 *
 * 利用者のキー [apiKey] そのものと、API キーらしい文字列の両方を [REDACTED_API_KEY] に置き換える。
 */
internal fun redactApiKeys(text: String, apiKey: String): String {
    val key = apiKey.trim()
    val withoutUserKey = if (key.length >= MIN_API_KEY_LENGTH_TO_REDACT) text.replace(key, REDACTED_API_KEY) else text
    return API_KEY_LIKE_PATTERN.replace(withoutUserKey, REDACTED_API_KEY)
}

/**
 * 外部の説明文 [message] を、利用者に見せられる形にする（純粋関数）。
 *
 * 改行・制御文字を空白 1 つにまとめ、書式制御文字（双方向の上書きなど）を捨て、API キーを伏せてから（[redactApiKeys]）、
 * [maxChars] 文字までに縮める（縮めたときは末尾に「…」を付ける）。伏せてから縮めるので、縮めた境界でキーの一部が残ることはない。
 */
internal fun sanitizeProviderMessage(
    message: String,
    apiKey: String,
    maxChars: Int = MAX_PROVIDER_MESSAGE_CHARS,
): String {
    val collapsed = buildString(message.length) {
        var pendingSpace = false
        for (c in message) {
            when {
                c.isWhitespace() || c.isISOControl() -> pendingSpace = isNotEmpty()

                Character.getType(c) == Character.FORMAT.toInt() -> Unit

                else -> {
                    if (pendingSpace) append(' ')
                    pendingSpace = false
                    append(c)
                }
            }
        }
    }
    val redacted = redactApiKeys(collapsed, apiKey)
    if (redacted.length <= maxChars) return redacted
    var end = maxChars.coerceAtLeast(0)
    if (end > 0 && Character.isHighSurrogate(redacted[end - 1])) end -= 1
    return redacted.substring(0, end).trimEnd() + "…"
}
