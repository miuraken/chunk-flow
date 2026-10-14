package com.miuraken.chunkflow.core.testing.fixture

import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.WordTiming
import java.util.Locale

/**
 * フィクスチャの 1 行（セグメント）。
 *
 * @property startMs 開始（ミリ秒）。
 * @property endMs 終了（ミリ秒）。
 * @property lang 言語タグ。`null` は判定できなかった行。
 * @property text 本文。
 * @property variants 別の字体のテキスト（キーは `"Hans"` / `"Hant"`）。
 * @property words 単語単位の時刻。
 */
data class SampleLine(
    val startMs: Long,
    val endMs: Long,
    val lang: String?,
    val text: String,
    val variants: Map<String, String> = emptyMap(),
    val words: List<WordTiming> = emptyList(),
)

/** フィクスチャを組み立てる補助。値はすべて決定的（乱数や現在時刻を使わない）。 */
object FixtureBuilder {
    /** フィクスチャの取り込み時刻（2026-10-14T00:00:00Z）。 */
    const val IMPORTED_AT_EPOCH_MS: Long = 1_791_936_000_000L

    /** フィクスチャの文字起こしの出どころ。 */
    val ENGINE: EngineId = EngineId("large-v3-turbo")

    /** 解析がすべて完了した状態。 */
    val ANALYSIS_DONE: Map<AnalysisStage, StageStatus> = mapOf(
        AnalysisStage.FEATURES to StageStatus.Done,
        AnalysisStage.SEGMENTATION to StageStatus.Done,
    )

    /** [index] から決まる UUID 形式の [MediaId]。 */
    fun mediaId(index: Int): MediaId = MediaId(String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", index))

    /** [index] から決まる 64 桁の 16 進数（SHA-256 の形をしたダミー）。 */
    fun sha256(index: Int): String = String.format(Locale.ROOT, "%064x", index.toLong() + 0xC0FFEE)

    /** 教材を作る。 */
    fun audioItem(
        index: Int,
        title: String,
        target: String,
        base: String?,
        durationMs: Long,
        course: String? = null,
        mimeType: String = "audio/mpeg",
    ): AudioItem = AudioItem(
        id = mediaId(index),
        title = title,
        durationMs = durationMs,
        mimeType = mimeType,
        sizeBytes = durationMs * 16, // 128kbps 相当
        contentSha256 = sha256(index),
        course = course,
        languagePair = LanguagePair(LanguageTag(target), base?.let(::LanguageTag)),
        importedAtEpochMs = IMPORTED_AT_EPOCH_MS + index * 60_000L,
    )

    /**
     * [lines] から教材 1 件分のデータを作る。
     *
     * @param withTranscripts `false` なら文字起こしを付けない（端末内の自動分割のチャンクだけの教材）。
     */
    fun material(
        key: String,
        item: AudioItem,
        lines: List<SampleLine>,
        withTranscripts: Boolean = true,
        analysis: Map<AnalysisStage, StageStatus> = ANALYSIS_DONE,
        withWaveform: Boolean = true,
        chunkIdBase: Long = item.id.value.takeLast(4).toLong() * 1_000,
    ): SampleMaterial {
        val origin = if (withTranscripts) ChunkOrigin.IMPORTED else ChunkOrigin.AUTO
        val chunks = lines.mapIndexed { i, line -> Chunk(ChunkId(chunkIdBase + i), line.startMs, line.endMs, origin) }
        val transcripts = if (withTranscripts) {
            lines.mapIndexed { i, line ->
                val id = chunks[i].id
                id to Transcript(
                    chunkId = id,
                    text = line.text,
                    textVariants = line.variants,
                    language = line.lang?.let(::LanguageTag),
                    languageConfidence = if (line.lang == null) null else 0.97f,
                    words = line.words,
                    engine = ENGINE,
                )
            }.toMap()
        } else {
            emptyMap()
        }
        return SampleMaterial(
            key = key,
            item = item,
            chunks = chunks,
            transcripts = transcripts,
            waveform = if (withWaveform) SampleWaveforms.forChunks(item.durationMs, chunks) else null,
            analysis = analysis,
        )
    }
}
