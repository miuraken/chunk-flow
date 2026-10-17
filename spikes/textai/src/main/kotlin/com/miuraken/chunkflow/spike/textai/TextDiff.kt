package com.miuraken.chunkflow.spike.textai

/** 校正の前後の差分の 1 区間。 */
internal sealed interface DiffSpan {
    /** 区間の文字列。 */
    val text: String

    /** 前後で同じ部分。 */
    data class Same(override val text: String) : DiffSpan

    /** 元の文にだけある部分（案で消えた）。 */
    data class Removed(override val text: String) : DiffSpan

    /** 案にだけある部分（案で加わった）。 */
    data class Added(override val text: String) : DiffSpan
}

/**
 * 元の文と校正の案の、文字（コードポイント）単位の差分（最長共通部分列）。
 *
 * 表の大きさが文字数の積になるので、どちらかが [MAX_CODE_POINTS] を超えたら `null`（差分を出さない）を返す。
 */
internal object TextDiff {
    /** 差分を計算する最大の文字数（コードポイント）。 */
    const val MAX_CODE_POINTS: Int = 600

    fun diff(before: String, after: String): List<DiffSpan>? {
        val a = before.codePoints().toArray()
        val b = after.codePoints().toArray()
        if (a.size > MAX_CODE_POINTS || b.size > MAX_CODE_POINTS) return null

        // 共通の先頭と末尾を除いてから表を作る（校正の差分は一部だけのことが多い）
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < a.size - prefix &&
            suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            suffix++
        }
        val midA = a.copyOfRange(prefix, a.size - suffix)
        val midB = b.copyOfRange(prefix, b.size - suffix)
        val n = midA.size
        val m = midB.size
        // lcs[i][j] = midA[i..] と midB[j..] の最長共通部分列の長さ
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (midA[i] == midB[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val builder = SpanBuilder()
        for (k in 0 until prefix) builder.append(SpanKind.SAME, a[k])
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                midA[i] == midB[j] -> {
                    builder.append(SpanKind.SAME, midA[i])
                    i++
                    j++
                }

                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    builder.append(SpanKind.REMOVED, midA[i])
                    i++
                }

                else -> {
                    builder.append(SpanKind.ADDED, midB[j])
                    j++
                }
            }
        }
        while (i < n) builder.append(SpanKind.REMOVED, midA[i++])
        while (j < m) builder.append(SpanKind.ADDED, midB[j++])
        for (k in a.size - suffix until a.size) builder.append(SpanKind.SAME, a[k])
        return builder.build()
    }

    private enum class SpanKind { SAME, REMOVED, ADDED }

    /** 同じ種類の文字が続く間は 1 つの区間にまとめる。 */
    private class SpanBuilder {
        private val spans = mutableListOf<DiffSpan>()
        private val current = StringBuilder()
        private var currentKind: SpanKind? = null

        fun append(kind: SpanKind, codePoint: Int) {
            if (kind != currentKind) flush()
            currentKind = kind
            current.appendCodePoint(codePoint)
        }

        fun build(): List<DiffSpan> {
            flush()
            return spans.toList()
        }

        private fun flush() {
            val kind = currentKind ?: return
            if (current.isEmpty()) return
            val text = current.toString()
            spans += when (kind) {
                SpanKind.SAME -> DiffSpan.Same(text)
                SpanKind.REMOVED -> DiffSpan.Removed(text)
                SpanKind.ADDED -> DiffSpan.Added(text)
            }
            current.setLength(0)
        }
    }
}
