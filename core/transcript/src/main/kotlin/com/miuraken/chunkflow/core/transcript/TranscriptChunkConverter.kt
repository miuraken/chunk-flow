package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.WordTiming
import kotlin.math.max
import kotlin.math.min

/**
 * パディング適用後のチャンク再生範囲と、必要に応じ音声長で切り詰めた文字起こしセグメントの組（§7.4.6、§7.3.3 手順 7）。
 *
 * @property startMs パディングを含むチャンクの開始時刻（ミリ秒）。
 * @property endMs パディングを含むチャンクの終了時刻（ミリ秒）。
 * @property segment 音声長内に切り詰められた文字起こしセグメント。
 */
data class ConvertedTranscriptChunk(
    val startMs: Long,
    val endMs: Long,
    val segment: TranscriptSegment,
) {
    init {
        require(startMs < endMs) { "startMs ($startMs) must be less than endMs ($endMs)" }
    }
}

/**
 * 文字起こしのセグメント群（[TranscriptSegment]）を、前後のパディングを含むチャンク範囲へ変換するヘルパー（§7.4.6、§7.3.3 手順 7）。
 *
 * - `audioDurationMs` 以降に開始するセグメント（`startMs >= audioDurationMs`）は除外する。
 * - `endMs > audioDurationMs` のセグメントは `audioDurationMs` で切り詰める（含まれる単語の時刻も範囲内に切り詰める）。
 * - 前後のパディング（既定: 前 150ms、後 200ms）を付与する際、`[0, audioDurationMs]` の範囲および
 *   隣接するセグメント間の無音区間の中点を越えないように制限する。
 */
class TranscriptChunkConverter {

    /**
     * [segments] を [audioDurationMs] 内に切り詰め、前後のパディングを適用した一覧を返す。
     */
    fun convert(
        segments: List<TranscriptSegment>,
        audioDurationMs: Long,
        prePaddingMs: Int = DEFAULT_PRE_PADDING_MS,
        postPaddingMs: Int = DEFAULT_POST_PADDING_MS,
    ): List<ConvertedTranscriptChunk> = Companion.convert(
        segments = segments,
        audioDurationMs = audioDurationMs,
        prePaddingMs = prePaddingMs,
        postPaddingMs = postPaddingMs,
    )

    companion object {
        /** 発話の前に含める既定のパディング（ミリ秒。§6.1 `SegmentationParams`、§7.3.3 手順 7）。 */
        const val DEFAULT_PRE_PADDING_MS: Int = 150

        /** 発話の後に含める既定のパディング（ミリ秒。§6.1 `SegmentationParams`、§7.3.3 手順 7）。 */
        const val DEFAULT_POST_PADDING_MS: Int = 200

        /**
         * [segments] を [audioDurationMs] 内に切り詰め、前後のパディングを適用した一覧を返す（§7.4.6、§7.3.3 手順 7）。
         *
         * @param segments 開始時刻順・非重複の文字起こしセグメント一覧。
         * @param audioDurationMs 実際の教材音声の長さ（ミリ秒）。0 以下の場合は空リストを返す。
         * @param prePaddingMs 発話の前に付けるパディング（ミリ秒、0 以上）。
         * @param postPaddingMs 発話の後に付けるパディング（ミリ秒、0 以上）。
         */
        fun convert(
            segments: List<TranscriptSegment>,
            audioDurationMs: Long,
            prePaddingMs: Int = DEFAULT_PRE_PADDING_MS,
            postPaddingMs: Int = DEFAULT_POST_PADDING_MS,
        ): List<ConvertedTranscriptChunk> {
            if (audioDurationMs <= 0L || segments.isEmpty()) {
                return emptyList()
            }
            val safePrePadding = prePaddingMs.coerceAtLeast(0).toLong()
            val safePostPadding = postPaddingMs.coerceAtLeast(0).toLong()

            val truncatedSegments = ArrayList<TranscriptSegment>(segments.size)
            for (seg in segments) {
                if (seg.startMs >= audioDurationMs) continue
                val clampedStart = seg.startMs.coerceAtLeast(0L)
                val clampedEnd = seg.endMs.coerceAtMost(audioDurationMs)
                if (clampedStart >= clampedEnd) continue

                val adjustedSegment = if (clampedStart == seg.startMs && clampedEnd == seg.endMs) {
                    seg
                } else {
                    val clampedWords = seg.words.mapNotNull { word ->
                        if (word.startMs >= clampedEnd || word.endMs <= clampedStart) {
                            null
                        } else {
                            val ws = word.startMs.coerceAtLeast(clampedStart)
                            val we = word.endMs.coerceAtMost(clampedEnd)
                            if (ws < we) WordTiming(ws, we, word.text, word.confidence) else null
                        }
                    }
                    seg.copy(
                        startMs = clampedStart,
                        endMs = clampedEnd,
                        words = clampedWords,
                    )
                }
                truncatedSegments.add(adjustedSegment)
            }

            if (truncatedSegments.isEmpty()) {
                return emptyList()
            }

            val result = ArrayList<ConvertedTranscriptChunk>(truncatedSegments.size)
            for (i in truncatedSegments.indices) {
                val current = truncatedSegments[i]
                val desiredStart = (current.startMs - safePrePadding).coerceAtLeast(0L)
                val paddedStart = if (i > 0) {
                    val prev = truncatedSegments[i - 1]
                    val silenceMidpointPrev = (prev.endMs + current.startMs) / 2L
                    max(desiredStart, silenceMidpointPrev)
                } else {
                    desiredStart
                }

                val desiredEnd = (current.endMs + safePostPadding).coerceAtMost(audioDurationMs)
                val paddedEnd = if (i < truncatedSegments.lastIndex) {
                    val next = truncatedSegments[i + 1]
                    val silenceMidpointNext = (current.endMs + next.startMs) / 2L
                    min(desiredEnd, silenceMidpointNext)
                } else {
                    desiredEnd
                }

                val finalStart = min(paddedStart, current.startMs)
                val finalEnd = max(paddedEnd, current.endMs)
                result.add(
                    ConvertedTranscriptChunk(
                        startMs = finalStart,
                        endMs = finalEnd,
                        segment = current,
                    ),
                )
            }
            return result
        }
    }
}
