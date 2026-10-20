package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterial
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterialPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials

/**
 * プレビュー・スクリーンショットテスト向けに [SampleMaterial] から [PlayerUiState] を組み立てるヘルパー。
 */
internal fun SampleMaterial.toPlayerUiState(
    isPlaying: Boolean = true,
    speed: Float = 1.0f,
    activeChunkIndex: Int? = if (chunks.isNotEmpty()) 1.coerceAtMost(chunks.lastIndex) else null,
    practice: PracticeSettings = PracticeSettings(),
    blindMode: BlindMode = BlindMode.OFF,
    autoPauseEndsAtMs: Long? = null,
    error: PlaybackError? = null,
    lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
): PlayerUiState {
    val chunkUis = chunks.map { chunk ->
        val transcript = transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
            language = transcript?.language,
            isRepeatChecked = chunk.id in practice.repeatChunkIds,
            isSkipped = chunk.isSkipped,
            translatedText = transcript?.translatedText,
            translatedLanguage = transcript?.translatedLanguage,
            textVariants = transcript?.textVariants.orEmpty(),
            words = transcript?.words.orEmpty(),
            origin = chunk.origin,
        )
    }
    val origins = if (transcripts.isNotEmpty()) {
        setOf(ChunkOrigin.IMPORTED, ChunkOrigin.AUTO)
    } else if (chunks.isNotEmpty()) {
        setOf(ChunkOrigin.AUTO)
    } else {
        emptySet()
    }
    return PlayerUiState(
        mediaId = item.id,
        title = item.title,
        course = item.course,
        languagePair = item.languagePair,
        isPlaying = isPlaying,
        durationMs = item.durationMs,
        speed = speed,
        practice = practice,
        autoPauseEndsAtMs = autoPauseEndsAtMs,
        waveform = waveform,
        chunks = chunkUis,
        activeChunkIndex = activeChunkIndex,
        analysis = analysis,
        error = error,
        blindMode = blindMode,
        chunkSource = if (transcripts.isNotEmpty()) ChunkOrigin.IMPORTED else ChunkOrigin.AUTO,
        availableChunkOrigins = origins,
        lookaheadStatus = lookaheadStatus,
    )
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenPreview() {
    val material = SampleMaterials.jaKo
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(
                isPlaying = true,
                speed = 1.25f,
            ),
            positionProvider = FakePositionProvider(5_000L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenMaterialPreview(
    @PreviewParameter(SampleMaterialPreviewParameterProvider::class) material: SampleMaterial,
) {
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(isPlaying = false),
            positionProvider = FakePositionProvider(4_500L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenBlindAndAutoPausePreview() {
    val material = SampleMaterials.enYue
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(
                isPlaying = false,
                blindMode = BlindMode.HIDE_TARGET_ONLY,
                practice = PracticeSettings(
                    loop = LoopMode.SingleChunk(material.chunks[1].id, times = 3),
                    autoPause = AutoPause.Fixed(3_000L),
                ),
                autoPauseEndsAtMs = 3_000L,
            ),
            positionProvider = FakePositionProvider(4_500L),
            onEvent = {},
        )
    }
}

/** 文字起こしの実行中（チャンクリストの下部に進捗のカードが重なる状態）のプレビュー。 */
@ChunkFlowPreviews
@Composable
private fun PlayerScreenTranscriptionProgressPreview() {
    val material = SampleMaterials.jaKo
    val baseState = material.toPlayerUiState(isPlaying = true, activeChunkIndex = 0)
    // 先頭の 2 件は文字起こし済み、3 件目を文字起こし中、残りは待機中にする
    val chunks = baseState.chunks.mapIndexed { index, chunk ->
        if (index < 2) chunk else chunk.copy(text = null, language = null)
    }
    ChunkFlowTheme {
        PlayerScreen(
            state = baseState.copy(
                chunks = chunks,
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    inFlightChunkId = chunks.getOrNull(2)?.id,
                    queuedChunkIds = chunks.drop(3).map { it.id }.toSet(),
                    completedCount = 2,
                    totalTargetCount = chunks.size,
                    inFlightStep = TranscriptionStep.DETECTING_LANGUAGE,
                    inFlightDetail = "ko / ja",
                ),
            ),
            positionProvider = FakePositionProvider(5_000L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerFileSettingsSheetPreview() {
    ChunkFlowTheme {
        Surface {
            ResegmentSheetContent(
                initialParams = SegmentationParams(),
                useGlobalSegmentation = true,
                languagePair = LanguagePair(
                    target = LanguageTag("ko"),
                    base = LanguageTag("ja"),
                ),
                itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
                sttEngineByLanguage = emptyMap(),
                languageModelInfos = emptyMap(),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onChangeLanguages = {},
                onChangeTargetEngine = {},
                onChangeBaseEngine = {},
                transcriptOffsetMs = 0,
                onAdjustTranscriptOffset = {},
                onAutoAlignTranscriptOffset = {},
                onImportTranscript = {},
                onExportTranscript = {},
                onResetTranscripts = {},
                onOpenDiagnostics = {},
                onToggleUseGlobalSegmentation = {},
                onApply = {},
                onOpenGlobalSettings = {},
                onClose = {},
            )
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerFileSettingsTranscriptTabPreview() {
    ChunkFlowTheme {
        Surface {
            ResegmentSheetContent(
                initialParams = SegmentationParams(),
                useGlobalSegmentation = true,
                languagePair = LanguagePair(
                    target = LanguageTag("ko"),
                    base = LanguageTag("ja"),
                ),
                itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
                sttEngineByLanguage = emptyMap(),
                languageModelInfos = emptyMap(),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onChangeLanguages = {},
                onChangeTargetEngine = {},
                onChangeBaseEngine = {},
                transcriptOffsetMs = 0,
                onAdjustTranscriptOffset = {},
                onAutoAlignTranscriptOffset = {},
                onImportTranscript = {},
                onExportTranscript = {},
                onResetTranscripts = {},
                onOpenDiagnostics = {},
                onToggleUseGlobalSegmentation = {},
                onApply = {},
                onOpenGlobalSettings = {},
                onClose = {},
                initialTabIndex = 1,
            )
        }
    }
}

/** ファイル全体リピート（「🔁リピート」青バッジ）と「無音スキップ」ON、再生設定「⚙️」アイコンのプレビュー。 */
@ChunkFlowPreviews
@Composable
private fun PlayerScreenWholeFileRepeatPreview() {
    val material = SampleMaterials.jaKo
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(
                isPlaying = true,
                practice = PracticeSettings(
                    repeatWholeFile = true,
                    skipOriginalSilence = true,
                    autoPause = AutoPause.OriginalSilence(1.0f),
                    repeatChunkIds = setOf(material.chunks.first().id),
                ),
            ),
            positionProvider = FakePositionProvider(1_500L),
            onEvent = {},
        )
    }
}
