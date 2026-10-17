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
}
