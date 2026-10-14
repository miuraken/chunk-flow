package com.miuraken.chunkflow.core.model

/**
 * 発話の単位（チャンク）。
 *
 * [startMs] / [endMs] は前後のパディングを含む再生範囲。`startMs < endMs` でなければならない。
 *
 * @property id チャンクの識別子。
 * @property startMs 再生範囲の開始（ミリ秒）。
 * @property endMs 再生範囲の終了（ミリ秒）。
 * @property origin チャンクの出どころ。
 */
data class Chunk(
    val id: ChunkId,
    val startMs: Long,
    val endMs: Long,
    val origin: ChunkOrigin,
) {
    init {
        require(startMs < endMs)
    }

    /** 再生範囲の長さ（ミリ秒）。 */
    val durationMs: Long get() = endMs - startMs
}

/** チャンクの出どころ。 */
enum class ChunkOrigin {
    /** 端末内の無音検出（§7.3.3）で作ったチャンク。 */
    AUTO,

    /** 利用者が手動で編集したチャンク（M4）。 */
    MANUAL,

    /** 文字起こしファイルから取り込んだチャンク。 */
    IMPORTED,
}

/**
 * 端末内の自動分割のパラメータ（§7.3.3）。
 *
 * @property minSilenceMs これ以上続く無音を区切りとみなす。
 * @property minSpeechMs これより短い発話は「短い発話」として扱う（§7.3.3 の手順 5）。
 * @property mergeGapMs 短い発話を隣のチャンクへ統合するときの最大の間隔（[minSilenceMs] 以上）。
 * @property maxChunkMs これより長いチャンクは、最もエネルギーの低い谷で再分割する。
 * @property prePaddingMs 発話の前に含める余白（子音の欠けを防ぐ）。
 * @property postPaddingMs 発話の後に含める余白。
 * @property thresholdRatio ノイズフロアと発話レベルの間のどこに閾値を置くか（0..1）。
 */
data class SegmentationParams(
    val minSilenceMs: Int = 500,
    val minSpeechMs: Int = 250,
    val mergeGapMs: Int = 1_000,
    val maxChunkMs: Int = 15_000,
    val prePaddingMs: Int = 150,
    val postPaddingMs: Int = 200,
    val thresholdRatio: Float = 0.3f,
)
