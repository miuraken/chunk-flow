package com.miuraken.chunkflow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineRecommendationChange
import com.miuraken.chunkflow.core.model.SttEngineSelection
import com.miuraken.chunkflow.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 端末で認識可能な言語選択肢のモデル。
 *
 * @property tag BCP 47 言語タグ（小文字 ISO コード）。
 * @property displayName 現在のロケールでの表示名（例: `日本語 (ja)`）。
 */
data class DeviceLanguageOption(
    val tag: LanguageTag,
    val displayName: String,
)

/**
 * 未ダウンロードのローカル ONNX モデルを選択した際に表示するモーダルダイアログの状態。
 *
 * @property language ダウンロード対象の言語タグ。
 * @property engine 対象のエンジン（既定は [SttEngine.SHERPA_ONNX]）。
 * @property sizeMb モデルの目安サイズ（MB、既定は 98）。
 * @property isDownloading モーダルダイアログ内でダウンロード実行中かどうか。
 * @property isFailed ダウンロードに失敗したかどうか。
 */
data class SherpaDownloadDialogState(
    val language: LanguageTag,
    val engine: SttEngine = SttEngine.SHERPA_ONNX,
    val sizeMb: Int = DEFAULT_SHERPA_MODEL_SIZE_MB,
    val isDownloading: Boolean = false,
    val isFailed: Boolean = false,
) {
    companion object {
        /** `sherpa-onnx` Whisper Tiny モデルの既定目安サイズ（MB）。 */
        const val DEFAULT_SHERPA_MODEL_SIZE_MB: Int = 98

        /** バイトサイズから表示用の MB サイズを算出する。 */
        fun resolveSizeMb(sizeBytes: Long): Int = ((sizeBytes / (1024L * 1024L)).toInt()).takeIf { it > 0 } ?: DEFAULT_SHERPA_MODEL_SIZE_MB
    }
}

/**
 * 設定画面の UI 状態（F9.1、F3.2、F3.3、F5.2、F6.3）。
 *
 * @property settings 現在のユーザー設定。
 * @property languageModels 言語ごとの端末内音声認識モデルの状態一覧（「文字起こし設定」タブ用）。
 * @property downloadableModels ダウンロード可能なローカル ONNX モデル一式の状態（「モデルのダウンロード」セクション用）。
 * @property sherpaDownloadDialog 未ダウンロードの `sherpa-onnx` を選択した際のダウンロードモーダルダイアログ状態。
 */
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val languageModels: List<OnDeviceLanguageModelInfo> = defaultLanguageModelInfos(),
    val downloadableModels: List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> = defaultDownloadableModelStates(),
    val sherpaDownloadDialog: SherpaDownloadDialogState? = null,
) {
    /** 既定の言語ペア。 */
    val defaultLanguagePair: LanguagePair
        get() = settings.defaultLanguagePair

    /** 中国語（`zh`）の表示字体。 */
    val chineseScript: ChineseScript
        get() = settings.chineseScript

    /** 広東語（`yue`）の表示字体。 */
    val cantoneseScript: ChineseScript
        get() = settings.cantoneseScript

    /** 学習言語のチャンクの下にローマ字・ピンイン・IAST 等の読み方ガイドを表示するかどうか。 */
    val showTargetRomanization: Boolean
        get() = settings.showTargetRomanization

    /** 中国語（`zh`）が学習言語または母語に選択されており、簡体字/繁体字設定を表示すべきかどうか。 */
    val showChineseScriptOption: Boolean
        get() = PhoneticGuideKind.isMandarinChinese(defaultLanguagePair.target) ||
            PhoneticGuideKind.isMandarinChinese(defaultLanguagePair.base)

    /** 広東語（`yue`）が学習言語または母語に選択されており、簡体字/繁体字設定を表示すべきかどうか。 */
    val showCantoneseScriptOption: Boolean
        get() = PhoneticGuideKind.isCantonese(defaultLanguagePair.target) ||
            PhoneticGuideKind.isCantonese(defaultLanguagePair.base)

    /** 学習言語に対する読み方ガイドの種別（ラテン文字言語の場合は `null`）。 */
    val targetPhoneticGuideKind: PhoneticGuideKind?
        get() = PhoneticGuideKind.forLanguage(defaultLanguagePair.target)

    /** 既定の再生速度（0.5..3.0、1.0x の場合は既定の指定速度 1.5x を返す）。 */
    val defaultSpeed: Float
        get() = if (kotlin.math.abs(settings.defaultSpeed - 1.0f) < 0.01f) 1.5f else settings.defaultSpeed

    /** ベース言語のチャンクだけを別の速度（[baseLanguageSpeed]）で再生するかどうか。 */
    val baseLanguageSpeedEnabled: Boolean
        get() = settings.baseLanguageSpeedEnabled

    /** ベース言語のチャンクの再生速度（0.5..3.0）。[baseLanguageSpeedEnabled] が `false` のときも値は保つ。 */
    val baseLanguageSpeed: Float
        get() = settings.baseLanguageSpeed

    /** 巻き戻しミリ秒数。 */
    val seekBackMs: Long
        get() = settings.seekBackMs

    /** 早送りミリ秒数。 */
    val seekForwardMs: Long
        get() = settings.seekForwardMs

    /** 既定の自動ポーズ設定。 */
    val defaultAutoPause: AutoPause
        get() = settings.defaultAutoPause

    /** 既定の自動ポーズ対象言語範囲。 */
    val defaultAutoPauseScope: AutoPauseScope
        get() = settings.defaultAutoPauseScope

    /** チャンク間の元音声の無音（ギャップ）をスキップするかどうか。 */
    val skipOriginalSilence: Boolean
        get() = settings.skipOriginalSilence

    /** 端末内の先読み簡易文字起こしを既定で有効にするかどうか（常に `true`）。 */
    val lookaheadTranscriptionEnabled: Boolean
        get() = settings.lookaheadTranscriptionEnabled

    /** ファイルを開いたら文字起こしを自動で始めるかどうか（既定は `true`）。 */
    val autoStartTranscriptionOnOpen: Boolean
        get() = settings.autoStartTranscriptionOnOpen

    /** バックグラウンドでの文字起こしを有効にするかどうか（既定は `true`）。 */
    val backgroundTranscriptionEnabled: Boolean
        get() = settings.backgroundTranscriptionEnabled

    /** 端末内文字起こしで使用する認識ライブラリ（エンジン）。 */
    val transcriptionEngine: OnDeviceTranscriptionEngine
        get() = settings.transcriptionEngine

    /** 端末内の自動チャンク分割の既定パラメータ。 */
    val defaultSegmentationParams: SegmentationParams
        get() = settings.defaultSegmentationParams

    /** 言語ごとの文字起こしエンジン。 */
    val sttEngineByLanguage: Map<LanguageTag, SttEngine>
        get() = settings.sttEngineByLanguage

    /**
     * ユーザーが「自動」以外に設定している言語のうち、アプリのアップデートで言語認識エンジンの状況が更新された言語一覧。
     */
    val sttRecommendationChanges: List<SttEngineRecommendationChange>
        get() = SttEngineSelection.detectUpdatedLanguageRecommendations(
            sttEngineByLanguage = settings.sttEngineByLanguage,
            acknowledgedRevisions = settings.acknowledgedSttRecommendationRevisions,
            modelInfoByLanguage = languageModels.associateBy { it.language },
        )

    /** OpenAI 互換 API キー。 */
    val openAiApiKey: String
        get() = settings.openAiApiKey

    /** Groq 互換 API キー。 */
    val groqApiKey: String
        get() = settings.groqApiKey

    /** Gemini API キー。 */
    val geminiApiKey: String
        get() = settings.geminiApiKey

    /** 選択肢として有効な言語のセット（既定は日本語・英語）。 */
    val enabledLanguages: Set<LanguageTag>
        get() = settings.enabledLanguages

    /**
     * 設定画面の言語チップに表示する言語一覧。
     *
     * [enabledLanguages] に加え、現在選択中の [defaultLanguagePair] の学習言語・ベース言語も含め、
     * 日本語（`ja`）・英語（`en`）を先頭に並べる。
     */
    val selectableLanguages: List<LanguageTag>
        get() {
            val combined = buildSet {
                addAll(enabledLanguages)
                add(defaultLanguagePair.target)
                defaultLanguagePair.base?.let { add(it) }
            }
            val priority = listOf(LanguageTag("ja"), LanguageTag("en"))
            val head = priority.filter { it in combined }
            val tail = combined.filterNot { it in priority }.sortedBy { it.value }
            return head + tail
        }

    companion object {
        /** 巻き戻し秒数の選択肢（ミリ秒）。 */
        val SEEK_BACK_OPTIONS_MS: List<Long> = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

        /** 早送り秒数の選択肢（ミリ秒）。 */
        val SEEK_FORWARD_OPTIONS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L)

        /** 既定の再生速度のプリセット一覧（1.0x を除く）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

        /** 固定自動ポーズ秒数の既定値（ミリ秒: 1.0 秒）。 */
        const val DEFAULT_AUTO_PAUSE_FIXED_MS: Long = 1_000L

        /** 固定自動ポーズ秒数の最小値（ミリ秒: 0.0 秒）。 */
        const val MIN_AUTO_PAUSE_FIXED_MS: Long = 0L

        /** 固定自動ポーズ秒数の最大値（ミリ秒: 4.0 秒）。 */
        const val MAX_AUTO_PAUSE_FIXED_MS: Long = 4_000L

        /** 固定自動ポーズ秒数の刻み幅（ミリ秒: 0.5 秒）。 */
        const val AUTO_PAUSE_FIXED_STEP_MS: Long = 500L

        /** チャンク長比例の自動ポーズ倍率の既定値（1.0 倍）。 */
        const val DEFAULT_AUTO_PAUSE_PROPORTIONAL_FACTOR: Float = 1.0f

        /** チャンク長比例の自動ポーズ倍率の最小値（0.5 倍）。 */
        const val MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR: Float = 0.5f

        /** チャンク長比例の自動ポーズ倍率の最大値（3.0 倍）。 */
        const val MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR: Float = 3.0f

        /** チャンク長比例の自動ポーズ倍率の刻み幅（0.5 倍）。 */
        const val AUTO_PAUSE_PROPORTIONAL_STEP: Float = 0.5f

        /** 元の無音部分に比例する自動ポーズ倍率の既定値（1.0 倍）。 */
        const val DEFAULT_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR: Float = 1.0f

        /** 元の無音部分に比例する自動ポーズ倍率の最小値（0.25 倍）。 */
        const val MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR: Float = 0.25f

        /** 元の無音部分に比例する自動ポーズ倍率の最大値（2.0 倍）。 */
        const val MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR: Float = 2.0f

        /** 元の無音部分に比例する自動ポーズ倍率の刻み幅（0.25 倍）。 */
        const val AUTO_PAUSE_ORIGINAL_SILENCE_STEP: Float = 0.25f

        /** 既定の自動ポーズの選択肢。 */
        val AUTO_PAUSE_OPTIONS: List<AutoPause> = listOf(
            AutoPause.Off,
            AutoPause.Fixed(DEFAULT_AUTO_PAUSE_FIXED_MS),
            AutoPause.Proportional(factor = DEFAULT_AUTO_PAUSE_PROPORTIONAL_FACTOR),
            AutoPause.OriginalSilence(factor = DEFAULT_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR),
        )

        /** 既定の自動ポーズ対象言語範囲の選択肢。 */
        val AUTO_PAUSE_SCOPE_OPTIONS: List<AutoPauseScope> = listOf(
            AutoPauseScope.ALL_CHUNKS,
            AutoPauseScope.AFTER_TARGET_LANGUAGE,
            AutoPauseScope.AFTER_BASE_LANGUAGE,
        )

        /** 端末内文字起こしライブラリの選択肢。 */
        val TRANSCRIPTION_ENGINE_OPTIONS: List<OnDeviceTranscriptionEngine> = listOf(
            OnDeviceTranscriptionEngine.AUTO,
            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            OnDeviceTranscriptionEngine.SHERPA_ONNX,
            OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE,
            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE,
            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL,
            OnDeviceTranscriptionEngine.MOONSHINE_TINY,
            OnDeviceTranscriptionEngine.MOONSHINE_BASE,
            OnDeviceTranscriptionEngine.OPENAI_API,
            OnDeviceTranscriptionEngine.GROQ_API,
            OnDeviceTranscriptionEngine.GEMINI_API,
            OnDeviceTranscriptionEngine.ML_KIT,
        )

        /** 全対応言語の一覧（`SpeechRecognizer` / `sherpa-onnx` Whisper / `ML Kit`）。 */
        val SUPPORTED_LANGUAGES: List<LanguageTag> =
            com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.MODEL_MANAGEMENT_LANGUAGE_TAGS

        /** 音声認識モデル管理一覧の初期値。 */
        fun defaultLanguageModelInfos(): List<OnDeviceLanguageModelInfo> = SUPPORTED_LANGUAGES.map { OnDeviceLanguageModelInfo(language = it) }

        /** ダウンロード可能モデル一覧の初期値。 */
        fun defaultDownloadableModelStates(): List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> = com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.map {
            com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = it)
        }

        /**
         * 端末（[Locale.getAvailableLocales] および [com.miuraken.chunkflow.core.model.SupportedLanguageCatalog]）で
         * 認識可能な言語一覧を返す。
         *
         * 日本語（`ja`）と英語（`en`）を先頭に配置し、残りは表示名・コード順でソートする。
         */
        fun availableDeviceLanguages(
            languageModels: List<OnDeviceLanguageModelInfo> = emptyList(),
            displayLocale: Locale = Locale.getDefault(),
        ): List<DeviceLanguageOption> {
            val rawCodes = buildSet {
                addAll(SUPPORTED_LANGUAGES.map { it.value })
                for (model in languageModels) {
                    val code = model.language.value.trim()
                    if (code.isNotEmpty() && code != "und") {
                        add(code)
                    }
                }
                for (locale in Locale.getAvailableLocales()) {
                    val normalized = when (val lang = locale.language.lowercase(Locale.ROOT)) {
                        "in" -> "id"
                        "iw" -> "he"
                        "ji" -> "yi"
                        else -> lang
                    }
                    if (normalized.length in 2..3 &&
                        normalized.all { it in 'a'..'z' } &&
                        normalized != "und"
                    ) {
                        add(normalized)
                    }
                }
            }
            val isJapaneseLocale = displayLocale.language.equals("ja", ignoreCase = true)
            val optionsByCode = rawCodes.associateWith { code ->
                val catalogEntry = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.findByCode(code)
                val tagLocale = Locale.forLanguageTag(code)
                val localeName = tagLocale.getDisplayLanguage(displayLocale)
                    .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
                val catalogName = if (isJapaneseLocale) catalogEntry?.japaneseName else catalogEntry?.englishName
                val lower = code.lowercase(Locale.ROOT)
                val name = catalogName ?: localeName ?: lower
                DeviceLanguageOption(
                    tag = LanguageTag(code),
                    displayName = "$name ($lower)",
                )
            }
            val priorityCodes = listOf("ja", "en")
            val head = priorityCodes.mapNotNull { optionsByCode[it] }
            val tail = optionsByCode
                .filterKeys { it !in priorityCodes }
                .values
                .sortedWith(compareBy<DeviceLanguageOption> { it.displayName }.thenBy { it.tag.value })
            return head + tail
        }
    }
}

/** 設定画面のイベント。 */
sealed interface SettingsEvent {
    /** 前の画面に戻る。 */
    data object Back : SettingsEvent

    /** 巻き戻しのミリ秒数を変更する。 */
    data class SetSeekBackMs(val seekBackMs: Long) : SettingsEvent

    /** 早送りのミリ秒数を変更する。 */
    data class SetSeekForwardMs(val seekForwardMs: Long) : SettingsEvent

    /** 既定の再生速度を変更する。 */
    data class SetDefaultSpeed(val speed: Float) : SettingsEvent

    /** 「ベース言語のみ別の速度で再生する」の ON / OFF を変更する。 */
    data class SetBaseLanguageSpeedEnabled(val enabled: Boolean) : SettingsEvent

    /** ベース言語のチャンクの再生速度を変更する。 */
    data class SetBaseLanguageSpeed(val speed: Float) : SettingsEvent

    /** 既定の自動ポーズを変更する。 */
    data class SetDefaultAutoPause(val autoPause: AutoPause) : SettingsEvent

    /** 既定の自動ポーズ対象言語範囲を変更する。 */
    data class SetDefaultAutoPauseScope(val scope: AutoPauseScope) : SettingsEvent

    /** チャンク間の元音声の無音スキップ ON / OFF を変更する。 */
    data class SetSkipOriginalSilence(val enabled: Boolean) : SettingsEvent

    /** 自動チャンク分割の既定パラメータを変更する。 */
    data class SetDefaultSegmentationParams(val params: SegmentationParams) : SettingsEvent

    /** 自動チャンク分割の既定パラメータを初期値に戻す。 */
    data object ResetDefaultSegmentationParams : SettingsEvent

    /** 既定の学習言語（target）を変更する。 */
    data class SetTargetLanguage(val language: LanguageTag) : SettingsEvent

    /** 既定の母語・解説の言語（base、単一言語は `null`）を変更する。 */
    data class SetBaseLanguage(val language: LanguageTag?) : SettingsEvent

    /** 選択肢として有効な言語の ON / OFF を切り替える。 */
    data class ToggleEnabledLanguage(val language: LanguageTag) : SettingsEvent

    /** 選択肢として有効な言語のセットを一括更新する。 */
    data class SetEnabledLanguages(val languages: Set<LanguageTag>) : SettingsEvent

    /** 中国語（`zh`）の表示字体を変更する。 */
    data class SetChineseScript(val script: ChineseScript) : SettingsEvent

    /** 広東語（`yue`）の表示字体を変更する。 */
    data class SetCantoneseScript(val script: ChineseScript) : SettingsEvent

    /** 学習言語の読み方ガイド（ローマ字 / IAST / Pinyin 等）の表示 ON / OFF を切り替える。 */
    data class SetShowTargetRomanization(val enabled: Boolean) : SettingsEvent

    /** 端末内の先読み簡易文字起こしの ON / OFF を切り替える。 */
    data class SetLookaheadTranscriptionEnabled(val enabled: Boolean) : SettingsEvent

    /** ファイルを開いたら文字起こしを自動で始めるかどうかの ON / OFF を切り替える。 */
    data class SetAutoStartTranscriptionOnOpen(val enabled: Boolean) : SettingsEvent

    /** バックグラウンドでの文字起こしの ON / OFF を切り替える。 */
    data class SetBackgroundTranscriptionEnabled(val enabled: Boolean) : SettingsEvent

    /** 端末内文字起こしで使用する認識ライブラリ（エンジン）を変更する。 */
    data class SetTranscriptionEngine(val engine: OnDeviceTranscriptionEngine) : SettingsEvent

    /** 指定した言語の文字起こしエンジンを変更する（null は自動）。未ダウンロードの `sherpa-onnx` を選んだ場合はダウンロード確認ダイアログを開く。 */
    data class SetSttEngineForLanguage(val language: LanguageTag, val engine: SttEngine?) : SettingsEvent

    /** モーダルダイアログで `sherpa-onnx` モデルのダウンロードを確定・開始し、完了後に `sherpa-onnx` に切り替える。 */
    data object ConfirmSherpaModelDownload : SettingsEvent

    /** `sherpa-onnx` モデルのダウンロード確認ダイアログを閉じる。 */
    data object DismissSherpaModelDownloadDialog : SettingsEvent

    /** 指定したエンジンと言語のモデルのダウンロード（またはOSへの取得要求）を行う。 */
    data class RequestSttModelDownload(val language: LanguageTag, val engine: SttEngine) : SettingsEvent

    /** 指定した言語の `sherpa-onnx` ローカルモデルを削除する（Stage 2）。 */
    data class DeleteSherpaModel(val language: LanguageTag) : SettingsEvent

    /** 指定した言語に対するローカルモデル [model]（Moonshine などの言語別モデル）を削除する。 */
    data class DeleteLanguageSttModel(
        val language: LanguageTag,
        val model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ) : SettingsEvent

    /** ダウンロード可能なローカルモデル [model] をグローバルにダウンロードする。 */
    data class DownloadSttModel(val model: com.miuraken.chunkflow.core.model.DownloadableSttModel) : SettingsEvent

    /** ダウンロード可能なローカルモデル [model] のファイルを削除（パージ）する。 */
    data class PurgeSttModel(val model: com.miuraken.chunkflow.core.model.DownloadableSttModel) : SettingsEvent

    /** OpenAI 互換 API キーを保存する。 */
    data class SetOpenAiApiKey(val apiKey: String) : SettingsEvent

    /** Groq 互換 API キーを保存する。 */
    data class SetGroqApiKey(val apiKey: String) : SettingsEvent

    /** Gemini API キーを保存する。 */
    data class SetGeminiApiKey(val apiKey: String) : SettingsEvent

    /** 言語ごとの端末内音声認識モデルの状態を再取得する。 */
    data object RefreshRecognitionModels : SettingsEvent

    /** 言語認識エンジンの推奨構成変更通知をすべて「自動（おすすめ）」に切り替えて確認済みにする。 */
    data object ApplyRecommendedAutoForUpdatedLanguages : SettingsEvent

    /** 言語認識エンジンの推奨構成変更通知を、現在の設定を維持したまま確認済みにする。 */
    data object AcknowledgeSttRecommendationChanges : SettingsEvent
}

/**
 * 設定画面の ViewModel（F9.1、F3.2、F3.3、F5.2、F6.3）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val analysisRepository: AnalysisRepository,
) : ViewModel() {

    constructor(
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        userSettingsRepository = userSettingsRepository,
        analysisRepository = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository(),
    )

    private val languageModelsState = MutableStateFlow(SettingsUiState.defaultLanguageModelInfos())
    private val downloadableModelsState = MutableStateFlow(SettingsUiState.defaultDownloadableModelStates())
    private val sherpaDownloadDialogState = MutableStateFlow<SherpaDownloadDialogState?>(null)

    /** 設定画面の UI 状態。 */
    val uiState: StateFlow<SettingsUiState> = combine(
        userSettingsRepository.settings,
        languageModelsState,
        downloadableModelsState,
        sherpaDownloadDialogState,
    ) { userSettings, languageModels, downloadableModels, sherpaDownloadDialog ->
        SettingsUiState(
            settings = userSettings,
            languageModels = languageModels,
            downloadableModels = downloadableModels,
            sherpaDownloadDialog = sherpaDownloadDialog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    init {
        viewModelScope.launch {
            refreshDownloadableModels()
            refreshActiveLanguageModelsFirst()
            userSettingsRepository.settings.collect { settings ->
                val activeTags = buildActiveModelManagementLanguages(settings)
                val currentByLang = languageModelsState.value.associateBy { it.language }
                val unqueried = activeTags.filter { tag ->
                    val info = currentByLang[tag]?.enrichedWithCatalog(tag)
                    info == null ||
                        info.status == OnDeviceLanguageModelStatus.CHECKING ||
                        info.mlKitStatus == OnDeviceLanguageModelStatus.CHECKING
                }
                if (unqueried.isNotEmpty()) {
                    queryAndMergeLanguageModels(unqueried)
                }
            }
        }
    }

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetSeekBackMs -> {
                if (event.seekBackMs <= 0L) return
                viewModelScope.launch {
                    userSettingsRepository.setSeekBackMs(event.seekBackMs)
                }
            }

            is SettingsEvent.SetSeekForwardMs -> {
                if (event.seekForwardMs <= 0L) return
                viewModelScope.launch {
                    userSettingsRepository.setSeekForwardMs(event.seekForwardMs)
                }
            }

            is SettingsEvent.SetDefaultSpeed -> {
                val rounded = roundSpeedToSliderStep(event.speed)
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSpeed(rounded)
                }
            }

            is SettingsEvent.SetBaseLanguageSpeedEnabled -> {
                viewModelScope.launch {
                    userSettingsRepository.setBaseLanguageSpeedEnabled(event.enabled)
                }
            }

            is SettingsEvent.SetBaseLanguageSpeed -> {
                val rounded = roundSpeedToSliderStep(event.speed)
                viewModelScope.launch {
                    userSettingsRepository.setBaseLanguageSpeed(rounded)
                }
            }

            is SettingsEvent.SetDefaultAutoPause -> {
                val sanitized = when (val ap = event.autoPause) {
                    AutoPause.Off -> AutoPause.Off

                    is AutoPause.Fixed -> {
                        val clamped = ap.pauseMs.coerceIn(
                            SettingsUiState.MIN_AUTO_PAUSE_FIXED_MS,
                            SettingsUiState.MAX_AUTO_PAUSE_FIXED_MS,
                        )
                        val steps = (clamped.toDouble() / SettingsUiState.AUTO_PAUSE_FIXED_STEP_MS).roundToInt()
                        AutoPause.Fixed(steps * SettingsUiState.AUTO_PAUSE_FIXED_STEP_MS)
                    }

                    is AutoPause.Proportional -> {
                        val clamped = ap.factor.coerceIn(
                            SettingsUiState.MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                            SettingsUiState.MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                        )
                        val steps = (clamped / SettingsUiState.AUTO_PAUSE_PROPORTIONAL_STEP).roundToInt()
                        AutoPause.Proportional(
                            factor = steps * SettingsUiState.AUTO_PAUSE_PROPORTIONAL_STEP,
                            minMs = ap.minMs,
                        )
                    }

                    is AutoPause.OriginalSilence -> {
                        val clamped = ap.factor.coerceIn(
                            SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                            SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                        )
                        val steps = (clamped / SettingsUiState.AUTO_PAUSE_ORIGINAL_SILENCE_STEP).roundToInt()
                        AutoPause.OriginalSilence(
                            factor = (steps * SettingsUiState.AUTO_PAUSE_ORIGINAL_SILENCE_STEP).coerceIn(
                                SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                                SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                            ),
                        )
                    }
                }
                viewModelScope.launch {
                    userSettingsRepository.setDefaultAutoPause(sanitized)
                }
            }

            is SettingsEvent.SetDefaultAutoPauseScope -> {
                viewModelScope.launch {
                    userSettingsRepository.setDefaultAutoPauseScope(event.scope)
                }
            }

            is SettingsEvent.SetSkipOriginalSilence -> {
                viewModelScope.launch {
                    userSettingsRepository.setSkipOriginalSilence(event.enabled)
                }
            }

            is SettingsEvent.SetDefaultSegmentationParams -> {
                val minSilence = event.params.minSilenceMs.coerceIn(100, 1_500)
                val sanitized = event.params.copy(
                    minSilenceMs = minSilence,
                    mergeGapMs = event.params.mergeGapMs.coerceAtLeast(minSilence),
                    prePaddingMs = event.params.prePaddingMs.coerceIn(0, 500),
                    postPaddingMs = event.params.postPaddingMs.coerceIn(0, 500),
                )
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSegmentationParams(sanitized)
                }
            }

            SettingsEvent.ResetDefaultSegmentationParams -> {
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSegmentationParams(SegmentationParams())
                }
            }

            is SettingsEvent.SetTargetLanguage -> {
                if (event.language.value.isBlank()) return
                val currentPair = uiState.value.defaultLanguagePair
                viewModelScope.launch {
                    userSettingsRepository.setDefaultLanguagePair(
                        currentPair.copy(target = event.language),
                    )
                }
            }

            is SettingsEvent.SetBaseLanguage -> {
                if (event.language != null && event.language.value.isBlank()) return
                val currentPair = uiState.value.defaultLanguagePair
                viewModelScope.launch {
                    userSettingsRepository.setDefaultLanguagePair(
                        currentPair.copy(base = event.language),
                    )
                }
            }

            is SettingsEvent.ToggleEnabledLanguage -> {
                toggleEnabledLanguage(event.language)
            }

            is SettingsEvent.SetEnabledLanguages -> {
                updateEnabledLanguages(event.languages)
            }

            is SettingsEvent.SetChineseScript -> {
                viewModelScope.launch {
                    userSettingsRepository.setChineseScript(event.script)
                }
            }

            is SettingsEvent.SetCantoneseScript -> {
                viewModelScope.launch {
                    userSettingsRepository.setCantoneseScript(event.script)
                }
            }

            is SettingsEvent.SetShowTargetRomanization -> {
                viewModelScope.launch {
                    userSettingsRepository.setShowTargetRomanization(event.enabled)
                }
            }

            is SettingsEvent.SetLookaheadTranscriptionEnabled -> {
                viewModelScope.launch {
                    userSettingsRepository.setLookaheadTranscriptionEnabled(event.enabled)
                }
            }

            is SettingsEvent.SetAutoStartTranscriptionOnOpen -> {
                viewModelScope.launch {
                    userSettingsRepository.setAutoStartTranscriptionOnOpen(event.enabled)
                }
            }

            is SettingsEvent.SetBackgroundTranscriptionEnabled -> {
                viewModelScope.launch {
                    userSettingsRepository.setBackgroundTranscriptionEnabled(event.enabled)
                }
            }

            is SettingsEvent.SetTranscriptionEngine -> {
                viewModelScope.launch {
                    userSettingsRepository.setTranscriptionEngine(event.engine)
                }
            }

            is SettingsEvent.SetSttEngineForLanguage -> {
                val mgmtCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                    event.language.value,
                )
                val rawInfo = languageModelsState.value.firstOrNull {
                    it.language == event.language ||
                        com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                            it.language.value,
                        ) == mgmtCode
                }
                val modelInfo = (rawInfo ?: OnDeviceLanguageModelInfo(language = event.language))
                    .enrichedWithCatalog(event.language)
                val targetEngine = event.engine
                val downloadableModel = targetEngine?.downloadableModel
                if (targetEngine != null && downloadableModel != null) {
                    val dlStatus = modelInfo.statusOf(targetEngine)
                    if (dlStatus != OnDeviceLanguageModelStatus.INSTALLED &&
                        dlStatus != OnDeviceLanguageModelStatus.UNSUPPORTED &&
                        dlStatus != OnDeviceLanguageModelStatus.CHECKING
                    ) {
                        val sizeMb = if (targetEngine == SttEngine.SHERPA_ONNX && modelInfo.sherpaModelSizeBytes > 0L) {
                            SherpaDownloadDialogState.resolveSizeMb(modelInfo.sherpaModelSizeBytes)
                        } else {
                            downloadableModel.approxSizeMbForLanguage(event.language).takeIf { it > 0 }
                                ?: SherpaDownloadDialogState.resolveSizeMb(modelInfo.sherpaModelSizeBytes)
                        }
                        sherpaDownloadDialogState.value = SherpaDownloadDialogState(
                            language = event.language,
                            engine = targetEngine,
                            sizeMb = sizeMb,
                            isDownloading = false,
                            isFailed = false,
                        )
                        return
                    }
                }
                if (!isSttEngineSelectableInSettings(modelInfo, event.engine)) {
                    return
                }
                val needsOsOrMlKitDownload = when (targetEngine) {
                    SttEngine.ML_KIT ->
                        modelInfo.mlKitStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                            modelInfo.mlKitStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED

                    SttEngine.SPEECH_RECOGNIZER ->
                        modelInfo.status == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                            modelInfo.status == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED

                    else -> false
                }
                viewModelScope.launch {
                    userSettingsRepository.setSttEngineForLanguage(event.language, event.engine)
                    if (needsOsOrMlKitDownload && targetEngine != null) {
                        onEvent(SettingsEvent.RequestSttModelDownload(event.language, targetEngine))
                    }
                }
            }

            SettingsEvent.ConfirmSherpaModelDownload -> {
                val dialog = sherpaDownloadDialogState.value ?: return
                if (dialog.isDownloading) return
                val targetEngine = dialog.engine
                sherpaDownloadDialogState.value = dialog.copy(
                    isDownloading = true,
                    isFailed = false,
                )
                languageModelsState.update { current ->
                    current.map { info ->
                        if (info.language == dialog.language) {
                            if (targetEngine == SttEngine.SHERPA_ONNX) {
                                info.copy(sherpaModelStatus = OnDeviceLanguageModelStatus.DOWNLOADING)
                            } else {
                                info.copy(
                                    extraEngineStatuses = info.extraEngineStatuses +
                                        (targetEngine to OnDeviceLanguageModelStatus.DOWNLOADING),
                                )
                            }
                        } else {
                            info
                        }
                    }
                }
                viewModelScope.launch {
                    val ok = analysisRepository.requestSttModelDownload(
                        dialog.language,
                        targetEngine,
                    )
                    refreshDownloadableModels()
                    if (ok) {
                        queryAndMergeLanguageModels(listOf(dialog.language))
                        userSettingsRepository.setSttEngineForLanguage(
                            dialog.language,
                            targetEngine,
                        )
                        sherpaDownloadDialogState.value = null
                    } else {
                        languageModelsState.update { current ->
                            current.map { info ->
                                if (info.language == dialog.language) {
                                    if (targetEngine == SttEngine.SHERPA_ONNX) {
                                        info.copy(sherpaModelStatus = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)
                                    } else {
                                        info.copy(
                                            extraEngineStatuses = info.extraEngineStatuses +
                                                (targetEngine to OnDeviceLanguageModelStatus.DOWNLOAD_FAILED),
                                        )
                                    }
                                } else {
                                    info
                                }
                            }
                        }
                        sherpaDownloadDialogState.update { current ->
                            current?.copy(isDownloading = false, isFailed = true)
                        }
                    }
                }
            }

            SettingsEvent.DismissSherpaModelDownloadDialog -> {
                if (sherpaDownloadDialogState.value?.isDownloading != true) {
                    sherpaDownloadDialogState.value = null
                }
            }

            is SettingsEvent.RequestSttModelDownload -> {
                val mgmtCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                    event.language.value,
                )
                val downloadableModel = event.engine.downloadableModel
                if (downloadableModel != null) {
                    downloadableModelsState.update { current ->
                        current.map { st ->
                            if (st.model == downloadableModel) {
                                st.copy(status = OnDeviceLanguageModelStatus.DOWNLOADING)
                            } else {
                                st
                            }
                        }
                    }
                }
                languageModelsState.update { current ->
                    current.map { info ->
                        val matchesLanguage = info.language == event.language ||
                            com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                                info.language.value,
                            ) == mgmtCode ||
                            (
                                downloadableModel != null &&
                                    !downloadableModel.isPerLanguageModel &&
                                    downloadableModel.isLanguageSupported(info.language)
                                )
                        if (matchesLanguage) {
                            when (event.engine) {
                                SttEngine.ML_KIT -> info.copy(mlKitStatus = OnDeviceLanguageModelStatus.DOWNLOADING)

                                SttEngine.SPEECH_RECOGNIZER -> info.copy(status = OnDeviceLanguageModelStatus.DOWNLOADING)

                                SttEngine.SHERPA_ONNX -> info.copy(sherpaModelStatus = OnDeviceLanguageModelStatus.DOWNLOADING)

                                else -> info.copy(
                                    extraEngineStatuses = info.extraEngineStatuses +
                                        (event.engine to OnDeviceLanguageModelStatus.DOWNLOADING),
                                )
                            }
                        } else {
                            info
                        }
                    }
                }
                viewModelScope.launch {
                    val ok = analysisRepository.requestSttModelDownload(event.language, event.engine)
                    refreshDownloadableModels()
                    if (!ok) {
                        if (downloadableModel != null) {
                            downloadableModelsState.update { current ->
                                current.map { st ->
                                    if (st.model == downloadableModel) {
                                        st.copy(status = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)
                                    } else {
                                        st
                                    }
                                }
                            }
                        }
                        languageModelsState.update { current ->
                            current.map { info ->
                                val matchesLanguage = info.language == event.language ||
                                    com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                                        info.language.value,
                                    ) == mgmtCode ||
                                    (
                                        downloadableModel != null &&
                                            !downloadableModel.isPerLanguageModel &&
                                            downloadableModel.isLanguageSupported(info.language)
                                        )
                                if (matchesLanguage) {
                                    when (event.engine) {
                                        SttEngine.ML_KIT -> info.copy(mlKitStatus = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)

                                        SttEngine.SPEECH_RECOGNIZER -> info.copy(status = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)

                                        SttEngine.SHERPA_ONNX -> info.copy(sherpaModelStatus = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)

                                        else -> info.copy(
                                            extraEngineStatuses = info.extraEngineStatuses +
                                                (event.engine to OnDeviceLanguageModelStatus.DOWNLOAD_FAILED),
                                        )
                                    }
                                } else {
                                    info
                                }
                            }
                        }
                    } else {
                        queryAndMergeLanguageModels(listOf(LanguageTag(mgmtCode)))
                        if (downloadableModel != null && !downloadableModel.isPerLanguageModel) {
                            refreshActiveLanguageModelsFirst()
                        }
                    }
                }
            }

            is SettingsEvent.DeleteSherpaModel -> {
                viewModelScope.launch {
                    analysisRepository.deleteSherpaModel(event.language)
                    refreshDownloadableModels()
                    languageModelsState.update { current ->
                        current.map { info ->
                            if (info.language == event.language) {
                                info.copy(
                                    sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                                    canDeleteSherpaModel = false,
                                )
                            } else {
                                info
                            }
                        }
                    }
                    queryAndMergeLanguageModels(listOf(event.language))
                    userSettingsRepository.setSttEngineForLanguage(event.language, null)
                    val currentEngines = userSettingsRepository.settings.first().sttEngineByLanguage
                    for ((lang, engine) in currentEngines) {
                        if (engine == SttEngine.SHERPA_ONNX) {
                            val isStillInstalled = languageModelsState.value.any { info ->
                                com.miuraken.chunkflow.core.model.LanguageSettings.normalize(info.language) == lang &&
                                    info.sherpaModelStatus == OnDeviceLanguageModelStatus.INSTALLED
                            }
                            if (!isStillInstalled) {
                                userSettingsRepository.setSttEngineForLanguage(lang, null)
                            }
                        }
                    }
                }
            }

            is SettingsEvent.DeleteLanguageSttModel -> {
                val mgmtCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                    event.language.value,
                )
                viewModelScope.launch {
                    analysisRepository.deleteSttModelForLanguage(event.language, event.model)
                    refreshDownloadableModels()
                    languageModelsState.update { current ->
                        current.map { info ->
                            if (info.language == event.language ||
                                com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                                    info.language.value,
                                ) == mgmtCode
                            ) {
                                info.copy(
                                    extraEngineStatuses = info.extraEngineStatuses +
                                        (event.model.sttEngine to OnDeviceLanguageModelStatus.NOT_INSTALLED),
                                )
                            } else {
                                info
                            }
                        }
                    }
                    queryAndMergeLanguageModels(listOf(LanguageTag(mgmtCode)))
                    val currentEngines = userSettingsRepository.settings.first().sttEngineByLanguage
                    for ((lang, engine) in currentEngines) {
                        val langMgmt = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                            lang.value,
                        )
                        if (engine == event.model.sttEngine && langMgmt == mgmtCode) {
                            userSettingsRepository.setSttEngineForLanguage(lang, null)
                        }
                    }
                }
            }

            is SettingsEvent.DownloadSttModel -> {
                downloadableModelsState.update { current ->
                    current.map { st ->
                        if (st.model == event.model) {
                            st.copy(status = OnDeviceLanguageModelStatus.DOWNLOADING)
                        } else {
                            st
                        }
                    }
                }
                viewModelScope.launch {
                    val ok = analysisRepository.downloadSttModel(event.model)
                    refreshDownloadableModels()
                    if (!ok) {
                        downloadableModelsState.update { current ->
                            current.map { st ->
                                if (st.model == event.model) {
                                    st.copy(status = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED)
                                } else {
                                    st
                                }
                            }
                        }
                    }
                    refreshActiveLanguageModelsFirst()
                }
            }

            is SettingsEvent.PurgeSttModel -> {
                viewModelScope.launch {
                    analysisRepository.purgeSttModel(event.model)
                    refreshDownloadableModels()
                    val currentSettings = userSettingsRepository.settings.first()
                    for ((lang, engine) in currentSettings.sttEngineByLanguage) {
                        if (engine.downloadableModel == event.model) {
                            userSettingsRepository.setSttEngineForLanguage(lang, null)
                        }
                    }
                    refreshActiveLanguageModelsFirst()
                }
            }

            is SettingsEvent.SetOpenAiApiKey -> {
                viewModelScope.launch {
                    userSettingsRepository.setOpenAiApiKey(event.apiKey)
                    refreshActiveLanguageModelsFirst()
                }
            }

            is SettingsEvent.SetGroqApiKey -> {
                viewModelScope.launch {
                    userSettingsRepository.setGroqApiKey(event.apiKey)
                    refreshActiveLanguageModelsFirst()
                }
            }

            is SettingsEvent.SetGeminiApiKey -> {
                viewModelScope.launch {
                    userSettingsRepository.setGeminiApiKey(event.apiKey)
                    refreshActiveLanguageModelsFirst()
                }
            }

            SettingsEvent.RefreshRecognitionModels -> {
                viewModelScope.launch {
                    analysisRepository.refreshRecognitionModelIssues()
                    refreshDownloadableModels()
                    refreshActiveLanguageModelsFirst()
                }
            }

            SettingsEvent.ApplyRecommendedAutoForUpdatedLanguages -> {
                val changes = uiState.value.sttRecommendationChanges
                if (changes.isEmpty()) return
                viewModelScope.launch {
                    for (change in changes) {
                        userSettingsRepository.setSttEngineForLanguage(change.language, null)
                    }
                    userSettingsRepository.acknowledgeSttRecommendationChanges(changes.map { it.language })
                }
            }

            SettingsEvent.AcknowledgeSttRecommendationChanges -> {
                val changes = uiState.value.sttRecommendationChanges
                if (changes.isEmpty()) return
                viewModelScope.launch {
                    userSettingsRepository.acknowledgeSttRecommendationChanges(changes.map { it.language })
                }
            }

            SettingsEvent.Back -> Unit
        }
    }

    private suspend fun refreshDownloadableModels() {
        val queried = runCatching { analysisRepository.queryDownloadableModelStates() }.getOrDefault(emptyList())
        if (queried.isNotEmpty()) {
            downloadableModelsState.value = queried
        }
    }

    private fun buildActiveModelManagementLanguages(settings: UserSettings): List<LanguageTag> {
        val codes = LinkedHashSet<String>()
        settings.defaultLanguagePair.target.value.takeIf { it.isNotBlank() }?.let {
            codes.add(com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(it))
        }
        settings.defaultLanguagePair.base?.value?.takeIf { it.isNotBlank() }?.let {
            codes.add(com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(it))
        }
        for (tag in settings.enabledLanguages) {
            if (tag.value.isNotBlank()) {
                codes.add(com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(tag.value))
            }
        }
        return codes.filter { it.isNotBlank() }.map(::LanguageTag)
    }

    private suspend fun refreshActiveLanguageModelsFirst() {
        val currentSettings = runCatching { userSettingsRepository.settings.first() }.getOrDefault(UserSettings())
        val activeLanguages = buildActiveModelManagementLanguages(currentSettings)
        if (activeLanguages.isNotEmpty()) {
            queryAndMergeLanguageModels(activeLanguages)
        }
        val remainingLanguages = SettingsUiState.SUPPORTED_LANGUAGES.filter { it !in activeLanguages }
        if (remainingLanguages.isNotEmpty()) {
            queryAndMergeLanguageModels(remainingLanguages)
        }
    }

    private suspend fun queryAndMergeLanguageModels(languages: List<LanguageTag>) {
        if (languages.isEmpty()) return
        val queried = analysisRepository.queryLanguageModelInfos(languages)
        if (queried.isEmpty()) return
        val queriedByCode = queried.associateBy {
            com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(it.language.value)
        }
        languageModelsState.update { current ->
            current.map { existing ->
                val mgmtCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(
                    existing.language.value,
                )
                queriedByCode[mgmtCode] ?: existing
            }
        }
    }

    /** 選択肢として有効な言語の ON / OFF を切り替える（最低 1 言語は残す）。 */
    fun toggleEnabledLanguage(tag: LanguageTag) {
        if (tag.value.isBlank()) return
        val current = uiState.value.enabledLanguages
        val next = if (tag in current) {
            if (current.size <= 1) return
            current - tag
        } else {
            current + tag
        }
        updateEnabledLanguages(next)
    }

    /** 選択肢として有効な言語のセットを更新する（空の場合は既定の日本語・英語にフォールバックする）。 */
    fun updateEnabledLanguages(languages: Set<LanguageTag>) {
        val cleaned = languages
            .mapNotNull { tag ->
                val code = tag.value.trim().lowercase(Locale.ROOT)
                if (code.isNotEmpty()) LanguageTag(code) else null
            }
            .toSet()
            .ifEmpty { UserSettings.DEFAULT_ENABLED_LANGUAGES }
        viewModelScope.launch {
            userSettingsRepository.setEnabledLanguages(cleaned)
        }
    }
}

/**
 * 設定画面の言語別音声認識エンジン選択で、指定した [engine]（`null` は自動）を選択可能（enabled）にするかどうかを返す。
 *
 * - `null`（自動）は少なくとも 1 つのエンジンが対応している（または確認中）のときに選択可能。
 * - ダウンロードが必要な端末内モデル（[SttEngine.downloadableModel] != null）は、ダウンロード済み（`INSTALLED`）のときだけ選択可能（未ダウンロード時は無効化し横にダウンロードボタンを出す）。
 * - クラウド API エンジン（OpenAI / Groq / Gemini）は API キーが設定済み（`INSTALLED`）のときだけ選択可能。
 * - それ以外（ML Kit など）は、端末・言語で対応が確認されている（`UNSUPPORTED` でも `CHECKING` でもない）ときだけ選択可能。
 */
internal fun isSttEngineSelectableInSettings(
    info: OnDeviceLanguageModelInfo,
    engine: SttEngine?,
): Boolean {
    val enriched = info.enrichedWithCatalog()
    if (engine == null) {
        return SttEngineSelection.isAnyEngineSupported(enriched)
    }
    val status = enriched.statusOf(engine)
    return status == OnDeviceLanguageModelStatus.INSTALLED
}

/**
 * 速度を設定画面のスライダーと同じ 0.05 刻みに丸め、[ItemState.MIN_SPEED]〜[ItemState.MAX_SPEED] に収める
 * （もうひとつの再生速度とベース言語の速度で共通）。
 */
internal fun roundSpeedToSliderStep(speed: Float): Float {
    val clamped = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
    return ((clamped * 20f).roundToInt() / 20f).coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
}
