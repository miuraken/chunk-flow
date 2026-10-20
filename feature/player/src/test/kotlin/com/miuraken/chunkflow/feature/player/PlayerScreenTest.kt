package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerScreenTest {

    @Test
    fun `詳細文字起こし事前チェック_モデル未ダウンロード時はエラー警告とモデルをダウンロードボタンを返す`() {
        val ko = LanguageTag("ko")
        val state = DetailedTranscriptionDialogState(
            chunkId = ChunkId(1L),
            chunkIndex = 0,
            engine = OnDeviceTranscriptionEngine.SHERPA_ONNX,
            languageOverride = ko,
            languageModelInfos = mapOf(
                ko to OnDeviceLanguageModelInfo(
                    language = ko,
                    mlKitStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                ),
            ),
        )

        val warning = resolveDetailedPrecheckWarning(state)
        assertNotNull(warning)
        assertEquals(DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED, warning.issue)
        assertEquals(
            "選択中の言語（ko）とエンジンの音声認識モデルがダウンロードされていません。このままでは文字起こしに失敗します。",
            warning.message,
        )
        assertEquals("モデルをダウンロード", warning.actionLabel)
        assertTrue(warning.isErrorStyle)
    }

    @Test
    fun `詳細文字起こし事前チェック_SpeechRecognizerでマイク権限がない時はマイク権限警告と許可ボタンを返す`() {
        val en = LanguageTag("en")
        val state = DetailedTranscriptionDialogState(
            chunkId = ChunkId(1L),
            chunkIndex = 0,
            engine = OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            languageOverride = en,
            hasRecordAudioPermission = false,
            languageModelInfos = mapOf(
                en to OnDeviceLanguageModelInfo(
                    language = en,
                    mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
                ),
            ),
        )

        val warning = resolveDetailedPrecheckWarning(state)
        assertNotNull(warning)
        assertEquals(DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED, warning.issue)
        assertEquals(
            "SpeechRecognizer の使用にはマイク権限（RECORD_AUDIO）が必要です",
            warning.message,
        )
        assertEquals("マイク権限を許可する", warning.actionLabel)
        assertTrue(warning.isErrorStyle)

        // マイク権限が付与されていれば警告なし
        assertNull(resolveDetailedPrecheckWarning(state, hasRecordAudioPermission = true))
    }

    @Test
    fun `詳細文字起こし事前チェック_モデルダウンロード中とエンジン非対応時はそれぞれ対応する警告を返す`() {
        val ko = LanguageTag("ko")
        val state = DetailedTranscriptionDialogState(
            chunkId = ChunkId(1L),
            chunkIndex = 0,
            engine = OnDeviceTranscriptionEngine.SHERPA_ONNX,
            languageOverride = ko,
            languageModelInfos = mapOf(
                ko to OnDeviceLanguageModelInfo(
                    language = ko,
                    mlKitStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = OnDeviceLanguageModelStatus.DOWNLOADING,
                ),
            ),
        )

        val downloadingWarning = resolveDetailedPrecheckWarning(
            dialogState = state,
            selectedLanguage = ko,
            selectedEngine = OnDeviceTranscriptionEngine.SHERPA_ONNX,
        )
        assertNotNull(downloadingWarning)
        assertEquals(DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING, downloadingWarning.issue)
        assertEquals(
            "現在モデルをダウンロード中です。完了するまでお待ちいただくか、別のエンジンを選択してください。",
            downloadingWarning.message,
        )
        assertNull(downloadingWarning.actionLabel)
        assertFalse(downloadingWarning.isErrorStyle)

        val unsupportedWarning = resolveDetailedPrecheckWarning(
            dialogState = state,
            selectedLanguage = ko,
            selectedEngine = OnDeviceTranscriptionEngine.ML_KIT,
        )
        assertNotNull(unsupportedWarning)
        assertEquals(DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED, unsupportedWarning.issue)
        assertEquals(
            "このエンジンは選択した言語に対応していません。別のエンジンを選択してください。",
            unsupportedWarning.message,
        )
        assertNull(unsupportedWarning.actionLabel)
        assertTrue(unsupportedWarning.isErrorStyle)
    }

    @Test
    fun `進捗ツールチップのモデル問題抽出とフォーマット_未導入と失敗と非対応のみを抽出し案内文を生成する`() {
        val ko = LanguageTag("ko")
        val ja = LanguageTag("ja")
        val zh = LanguageTag("zh")
        val issues = mapOf(
            ko to OnDeviceRecognitionModelState.NOT_INSTALLED,
            ja to OnDeviceRecognitionModelState.DOWNLOADING,
            zh to OnDeviceRecognitionModelState.UNSUPPORTED,
        )

        val unprepared = extractUnpreparedModelIssueLanguages(issues)
        assertEquals(listOf(ko, zh), unprepared)
        assertEquals(
            "設定の確認: ko の音声認識モデルが未準備です（設定からダウンロードまたはエンジン変更が必要です）",
            formatProgressModelIssueWarning(ko),
        )
    }

    @Test
    fun `プレイヤー画面の設定不備状態_同一言語ペアと全チャンク空結果完了の判定が機能する`() {
        val c1 = ChunkUi(id = ChunkId(1L), startMs = 0L, endMs = 1_000L, text = null, language = null)
        val c2 = ChunkUi(id = ChunkId(2L), startMs = 1_000L, endMs = 2_000L, text = null, language = null)

        val uiState = PlayerUiState(
            languagePair = LanguagePair(target = LanguageTag("ja"), base = LanguageTag("ja")),
            chunks = listOf(c1, c2),
            lookaheadStatus = LookaheadTranscriptionStatus(
                enabled = true,
                completedCount = 2,
                totalTargetCount = 2,
                skippedEmptyChunkIds = setOf(c1.id, c2.id),
            ),
        )

        assertTrue(uiState.hasSameTargetAndBaseLanguage)
        assertTrue(uiState.isAllTranscriptionEmptyAfterCompletion)
        assertFalse(uiState.isTranscriptionRunning)
    }

    @Test
    fun `文字起こし進捗ペーンのバックグラウンドチェックボックス_ラベルとchecked状態がLookaheadTranscriptionStatusを反映する`() {
        val disabledState = resolveTranscriptionProgressCheckbox(
            LookaheadTranscriptionStatus(enabled = true, backgroundEnabled = false),
        )
        assertEquals("バックグラウンドで文字起こしを行う", disabledState.label)
        assertFalse(disabledState.checked)

        val enabledState = resolveTranscriptionProgressCheckbox(
            LookaheadTranscriptionStatus(enabled = true, backgroundEnabled = true),
        )
        assertEquals("バックグラウンドで文字起こしを行う", enabledState.label)
        assertTrue(enabledState.checked)
    }

    @Test
    fun `SpeechRecognizerバックグラウンド警告メッセージ_sherpa対応言語がある場合は推奨文を含み非対応言語のみなら省く`() {
        val withSherpa = formatBackgroundSpeechRecognizerWarningMessage(
            BackgroundSpeechRecognizerWarningState(
                speechRecognizerLanguages = listOf(LanguageTag("ko"), LanguageTag("fr")),
                sherpaSupportedLanguages = listOf(LanguageTag("ko")),
            ),
        )
        assertTrue(withSherpa.contains("韓国語 (ko)・フランス語 (fr)では標準の音声認識（SpeechRecognizer）が使用されるため、バックグラウンドでは処理が進まない可能性があります。"))
        assertTrue(withSherpa.contains("韓国語 (ko)ではオフラインモデルの sherpa-onnx が利用可能です。sherpa-onnx の導入を検討してください。"))

        val withoutSherpa = formatBackgroundSpeechRecognizerWarningMessage(
            BackgroundSpeechRecognizerWarningState(
                speechRecognizerLanguages = listOf(LanguageTag("fr")),
                sherpaSupportedLanguages = emptyList(),
            ),
        )
        assertTrue(withoutSherpa.contains("フランス語 (fr)では標準の音声認識（SpeechRecognizer）が使用されるため、バックグラウンドでは処理が進まない可能性があります。"))
        assertFalse(withoutSherpa.contains("sherpa-onnx"))
    }

    @Test
    fun `文字起こし進捗ペーンの集計_バッチの開始位置に関わらずファイル全体の文字起こし済みチャンク数と全チャンク数を算出する`() {
        // 例: 全10チャンクのうち、すでに6チャンクが文字起こし済みで、残り4チャンクに対して新たに先読みを開始した直後（lookaheadStatus.completedCount = 0, totalTargetCount = 4）
        val status = LookaheadTranscriptionStatus(
            enabled = true,
            inFlightChunkId = ChunkId(7L),
            inFlightStep = com.miuraken.chunkflow.core.model.TranscriptionStep.RECOGNIZING_PRIMARY,
            completedCount = 0,
            totalTargetCount = 4,
        )
        val summary = computeTranscriptionProgressSummary(
            lookaheadStatus = status,
            transcribedChunkCount = 6,
            totalChunkCount = 10,
        )
        assertEquals(6, summary.completedCount)
        assertEquals(10, summary.totalCount)
        assertEquals(60, summary.progressPercent)
        assertEquals(0.675f, summary.overallProgress, 0.001f)
    }

    @Test
    fun `言語判定のみ完了状態_skippedEmptyChunkIdsが空なら全チャンク空結果警告にならず言語フィルタが有効になる`() {
        val c1 = ChunkUi(id = ChunkId(1L), startMs = 0L, endMs = 1_000L, text = null, language = LanguageTag("ja"))
        val c2 = ChunkUi(id = ChunkId(2L), startMs = 1_000L, endMs = 2_000L, text = null, language = LanguageTag("ko"))
        val c3 = ChunkUi(id = ChunkId(3L), startMs = 2_000L, endMs = 3_000L, text = null, language = LanguageTag("und"))

        val lidCompletedState = PlayerUiState(
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
            chunks = listOf(c1, c2, c3),
            lookaheadStatus = LookaheadTranscriptionStatus(
                enabled = false,
                completedCount = 3,
                totalTargetCount = 3,
                skippedEmptyChunkIds = emptySet(),
            ),
        )

        assertFalse(lidCompletedState.hasTranscript)
        assertTrue(lidCompletedState.hasChunkLanguage)
        assertFalse(lidCompletedState.isAllTranscriptionEmptyAfterCompletion)
        assertFalse(lidCompletedState.isTranscriptionRunning)
        assertTrue(lidCompletedState.hasTranscript || lidCompletedState.hasChunkLanguage)

        // und のみの場合は hasChunkLanguage が false になる
        val undOnlyState = lidCompletedState.copy(chunks = listOf(c3))
        assertFalse(undOnlyState.hasChunkLanguage)
    }

    @Test
    fun `詳細文字起こしエンジン選択制御_MLKit非対応または未確認時は無効化されsherpaはインストール済みのみ有効になる`() {
        val en = LanguageTag("en")
        val ko = LanguageTag("ko")
        val state = DetailedTranscriptionDialogState(
            chunkId = ChunkId(1L),
            chunkIndex = 0,
            languageOverride = en,
            languageModelInfos = mapOf(
                // en: ML Kit が CHECKING（非対応端末等）、SpeechRecognizer が INSTALLED、sherpa-onnx が NOT_INSTALLED
                en to OnDeviceLanguageModelInfo(
                    language = en,
                    mlKitStatus = OnDeviceLanguageModelStatus.CHECKING,
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                ),
                // ko: ML Kit が UNSUPPORTED、SpeechRecognizer が INSTALLED、sherpa-onnx が INSTALLED
                ko to OnDeviceLanguageModelInfo(
                    language = ko,
                    mlKitStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
                ),
            ),
        )

        // en: ML Kit は CHECKING なので無効、sherpa-onnx は NOT_INSTALLED なので無効、SpeechRecognizer のみ有効
        assertFalse(state.isEngineEnabled(en, OnDeviceTranscriptionEngine.ML_KIT))
        assertTrue(state.isEngineEnabled(en, OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER))
        assertFalse(state.isEngineEnabled(en, OnDeviceTranscriptionEngine.SHERPA_ONNX))
        assertEquals(
            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            state.selectDefaultEngineForLanguage(en),
        )

        // ko: ML Kit は UNSUPPORTED なので無効、SpeechRecognizer と sherpa-onnx（INSTALLED）は有効
        assertFalse(state.isEngineEnabled(ko, OnDeviceTranscriptionEngine.ML_KIT))
        assertTrue(state.isEngineEnabled(ko, OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER))
        assertTrue(state.isEngineEnabled(ko, OnDeviceTranscriptionEngine.SHERPA_ONNX))
        assertEquals(
            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            state.selectDefaultEngineForLanguage(ko),
        )
    }

    @Test
    fun `resolveAutoFollowScrollTargetIndex_再生中チャンクが上から3番目に配置されるスクロールインデックスを返す`() {
        assertEquals(0, resolveAutoFollowScrollTargetIndex(0))
        assertEquals(0, resolveAutoFollowScrollTargetIndex(1))
        assertEquals(0, resolveAutoFollowScrollTargetIndex(2))
        assertEquals(1, resolveAutoFollowScrollTargetIndex(3))
        assertEquals(8, resolveAutoFollowScrollTargetIndex(10))
    }

    @Test
    fun `shouldShowOverviewChunkBoundaryTicks_言語判定中は表示し言語判定終了後は非表示にする`() {
        val c1 = ChunkUi(id = ChunkId(1L), startMs = 0L, endMs = 1_000L, text = null, language = null)
        val c2 = ChunkUi(id = ChunkId(2L), startMs = 1_500L, endMs = 2_500L, text = null, language = null)

        // 言語判定前（language == null）は下部チャンク境界オーバーレイを表示する
        assertTrue(
            shouldShowOverviewChunkBoundaryTicks(
                featureStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                segmentationStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                lookaheadStatus = LookaheadTranscriptionStatus(),
                chunks = listOf(c1, c2),
            ),
        )

        // 言語判定中も下部チャンク境界オーバーレイを表示する
        assertTrue(
            shouldShowOverviewChunkBoundaryTicks(
                featureStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                segmentationStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                lookaheadStatus = LookaheadTranscriptionStatus(
                    inFlightChunkId = c2.id,
                    inFlightStep = com.miuraken.chunkflow.core.model.TranscriptionStep.DETECTING_LANGUAGE,
                ),
                chunks = listOf(c1.copy(language = LanguageTag("ja")), c2),
            ),
        )

        // 全チャンクの言語判定が終了した後は下部チャンク境界オーバーレイを非表示にする
        assertFalse(
            shouldShowOverviewChunkBoundaryTicks(
                featureStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                segmentationStatus = com.miuraken.chunkflow.core.model.StageStatus.Done,
                lookaheadStatus = LookaheadTranscriptionStatus(),
                chunks = listOf(
                    c1.copy(language = LanguageTag("ja")),
                    c2.copy(language = LanguageTag("ko")),
                ),
            ),
        )
    }

    @Test
    fun `resolveChunkIndexAtPosition_再生位置に対応するチャンクまたは直前チャンクのインデックスを返す`() {
        val chunks = listOf(
            ChunkUi(id = ChunkId(1L), startMs = 500L, endMs = 1_500L, text = null, language = null),
            ChunkUi(id = ChunkId(2L), startMs = 2_000L, endMs = 3_000L, text = null, language = null),
            ChunkUi(id = ChunkId(3L), startMs = 3_500L, endMs = 4_500L, text = null, language = null),
        )

        assertNull(resolveChunkIndexAtPosition(emptyList(), 1_000L))
        assertEquals(0, resolveChunkIndexAtPosition(chunks, 0L))
        assertEquals(0, resolveChunkIndexAtPosition(chunks, 1_000L))
        assertEquals(0, resolveChunkIndexAtPosition(chunks, 1_750L))
        assertEquals(1, resolveChunkIndexAtPosition(chunks, 2_000L))
        assertEquals(1, resolveChunkIndexAtPosition(chunks, 3_200L))
        assertEquals(2, resolveChunkIndexAtPosition(chunks, 4_000L))
        assertEquals(2, resolveChunkIndexAtPosition(chunks, 6_000L))
    }

    @Test
    fun `computeTranscriptListScrollbarMetrics_スクロール不可時はnullを返しスクロール位置に応じたつまみ比率とオフセット比率を算出する`() {
        // 全アイテムが画面内に収まっていて前後どちらにもスクロールできない場合は null（非表示）
        assertNull(
            computeTranscriptListScrollbarMetrics(
                totalItemsCount = 5,
                visibleItemsCount = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                totalVisibleItemsSizePx = 500,
                viewportHeightPx = 600,
                canScrollForward = false,
                canScrollBackward = false,
            ),
        )

        // 先頭位置（全 20 件、可視 5 件、各 100px = 合計 500px、ビューポート 500px、推定全長 2000px）
        val atTop = computeTranscriptListScrollbarMetrics(
            totalItemsCount = 20,
            visibleItemsCount = 5,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            totalVisibleItemsSizePx = 500,
            viewportHeightPx = 500,
            canScrollForward = true,
            canScrollBackward = false,
            minThumbFraction = 0.06f,
        )
        assertNotNull(atTop)
        assertEquals(0.25f, atTop.thumbFraction, 0.001f)
        assertEquals(0f, atTop.thumbOffsetFraction, 0.001f)

        // 中間位置（インデックス 7、オフセット 50px -> 推定スクロール量 750px / 最大スクロール量 1500px = 50%）
        val atMiddle = computeTranscriptListScrollbarMetrics(
            totalItemsCount = 20,
            visibleItemsCount = 5,
            firstVisibleItemIndex = 7,
            firstVisibleItemScrollOffset = 50,
            totalVisibleItemsSizePx = 500,
            viewportHeightPx = 500,
            canScrollForward = true,
            canScrollBackward = true,
            minThumbFraction = 0.06f,
        )
        assertNotNull(atMiddle)
        assertEquals(0.25f, atMiddle.thumbFraction, 0.001f)
        assertEquals(0.375f, atMiddle.thumbOffsetFraction, 0.001f)

        // 最下部到達時（canScrollForward = false）はオフセットが下端（1f - thumbFraction）に揃う
        val atBottom = computeTranscriptListScrollbarMetrics(
            totalItemsCount = 20,
            visibleItemsCount = 5,
            firstVisibleItemIndex = 15,
            firstVisibleItemScrollOffset = 0,
            totalVisibleItemsSizePx = 500,
            viewportHeightPx = 500,
            canScrollForward = false,
            canScrollBackward = true,
            minThumbFraction = 0.06f,
        )
        assertNotNull(atBottom)
        assertEquals(0.25f, atBottom.thumbFraction, 0.001f)
        assertEquals(0.75f, atBottom.thumbOffsetFraction, 0.001f)

        // アイテム数が非常に多い場合でも minThumbFraction 以上のつまみ高さ比率が維持される
        val manyItems = computeTranscriptListScrollbarMetrics(
            totalItemsCount = 500,
            visibleItemsCount = 5,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            totalVisibleItemsSizePx = 500,
            viewportHeightPx = 500,
            canScrollForward = true,
            canScrollBackward = false,
            minThumbFraction = 0.08f,
        )
        assertNotNull(manyItems)
        assertEquals(0.08f, manyItems.thumbFraction, 0.001f)
    }
}
