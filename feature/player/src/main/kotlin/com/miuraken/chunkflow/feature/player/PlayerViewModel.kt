package com.miuraken.chunkflow.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineSelection
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ブラインドモード（スクリプトを隠す学習モード。F7.2、§7.5.5）。
 */
enum class BlindMode {
    /** すべてのテキストを表示する。 */
    OFF,

    /** 学習言語（target）の行だけを隠す（母語を見て学習言語で言ってみる練習）。 */
    HIDE_TARGET_ONLY,

    /** すべての行を隠す（聞き取り練習）。 */
    HIDE_ALL,
}

/**
 * プレイヤー画面での文字起こしファイル取り込み結果の通知状態（F1.3、§7.5.5）。
 */
sealed interface TranscriptImportBannerState {
    /**
     * 文字起こしを取り込んだ。
     *
     * @property chunkCount 作成したチャンク数。
     * @property durationMismatch 音声の長さと 0.5 秒以上の差があるかどうか。
     */
    data class Attached(val chunkCount: Int, val durationMismatch: Boolean) : TranscriptImportBannerState

    /**
     * 対応する音声が一致しなかった。
     *
     * @property audioFileName JSON に記載されていた音声ファイル名。
     */
    data class NoMatchingAudio(val audioFileName: String?) : TranscriptImportBannerState

    /**
     * 文字起こしファイルが不正だった。
     *
     * @property error エラーの種別。
     */
    data class Invalid(val error: TranscriptError) : TranscriptImportBannerState
}

/**
 * プレイヤー画面での字幕・テキストファイル書き出し結果の通知状態（F7.5）。
 */
sealed interface TranscriptExportBannerState {
    /**
     * 指定形式でファイルを書き出した。
     *
     * @property format 書き出したフォーマット（SRT / LRC / TXT）。
     */
    data class Exported(val format: TranscriptExportFormat) : TranscriptExportBannerState

    /** 文字起こしをリセットし、保存済みファイルを削除した。 */
    data object ResetCompleted : TranscriptExportBannerState

    /** 書き出しに失敗した。 */
    data object Failed : TranscriptExportBannerState
}

/**
 * チャンク 1 件の UI 表示モデル（§6.2、§7.5.5、F7.7、F8.1）。
 *
 * @property id チャンク ID。
 * @property startMs 開始時刻（ミリ秒）。
 * @property endMs 終了時刻（ミリ秒）。
 * @property text 文字起こしテキスト（なければ `null`）。
 * @property language 言語タグ（未判定または信頼度が低い場合は `null`）。
 * @property isBookmarked ブックマーク済みかどうか（F8.1）。
 * @property isRepeatChecked チャンクごとの連続無限リピート対象としてチェックされているかどうか（F6.1）。
 * @property textVariants 別の字体のテキスト（キーは `"Hans"` / `"Hant"`）。
 * @property words 単語ごとのタイムスタンプ（カラオケ強調用、F7.7）。
 * @property origin チャンクの出どころ。
 */
data class ChunkUi(
    val id: ChunkId,
    val startMs: Long,
    val endMs: Long,
    val text: String?,
    val language: LanguageTag?,
    val isBookmarked: Boolean = false,
    val isRepeatChecked: Boolean = false,
    val isSkipped: Boolean = false,
    val translatedText: String? = null,
    val translatedLanguage: LanguageTag? = null,
    val textVariants: Map<String, String> = emptyMap(),
    val words: List<WordTiming> = emptyList(),
    val origin: ChunkOrigin = ChunkOrigin.AUTO,
) {
    /**
     * 設定された漢字の字体（[chineseScript] / [cantoneseScript]）に従って表示するテキストを返す（§7.5.5）。
     *
     * `textVariants` に該当スクリプトがない場合でも、端末の ICU `Transliterator`（`Hans-Hant` / `Hant-Hans`）が
     * 利用可能であれば自動的に簡体字・繁体字へ変換する。
     */
    private fun resolveMandarinScript(fallback: ChineseScript): ChineseScript = explicitChineseScriptForTag(language?.value) ?: fallback

    fun resolveDisplayText(
        chineseScript: ChineseScript,
        cantoneseScript: ChineseScript,
    ): String? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        return when {
            PhoneticGuideKind.isMandarinChinese(language) -> {
                val script = resolveMandarinScript(chineseScript)
                textVariants[script.iso15924]
                    ?: transliterateChineseScript(raw, script)
                    ?: raw
            }

            PhoneticGuideKind.isCantonese(language) ->
                textVariants[cantoneseScript.iso15924]
                    ?: transliterateChineseScript(raw, cantoneseScript)
                    ?: raw

            else -> raw
        }
    }

    /**
     * 学習言語チャンクの下に小さく表示する読み方ガイド（ローマ字 / IAST / Pinyin / ラテン文字転写）を返す。
     *
     * 1. `textVariants` に `"Latn"` / `"pinyin"` / `"romaji"` / `"iast"` / `"jyutping"` があれば優先して返す。
     * 2. なければ [PhoneticGuideKind.forLanguage] に応じて ICU `Transliterator`（または純 Kotlin フォールバック）で生成する。
     * 3. 元の表示テキストと同一または空の場合は `null` を返す。
     */
    fun resolvePhoneticReading(
        chineseScript: ChineseScript = ChineseScript.HANS,
        cantoneseScript: ChineseScript = ChineseScript.HANT,
    ): String? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val explicit = textVariants["Latn"]
            ?: textVariants["latn"]
            ?: textVariants["pinyin"]
            ?: textVariants["romaji"]
            ?: textVariants["iast"]
            ?: textVariants["jyutping"]
        if (!explicit.isNullOrBlank()) {
            return explicit.trim()
        }
        val kind = PhoneticGuideKind.forLanguage(language) ?: return null
        val transliterated = transliterateToLatin(raw, language, kind)?.trim()
        if (transliterated.isNullOrEmpty()) return null
        val display = resolveDisplayText(chineseScript, cantoneseScript)?.trim() ?: raw
        if (transliterated.equals(display, ignoreCase = true) ||
            transliterated.equals(raw, ignoreCase = true)
        ) {
            return null
        }
        return transliterated
    }

    /**
     * 行ごとの `LocaleList` に指定する BCP-47 ロケールタグを返す（§7.5.5）。
     *
     * 言語が未判定（`null`）の場合は `null` を返し、端末の既定ロケールを使う。
     */
    fun resolveLocaleTag(
        chineseScript: ChineseScript,
        cantoneseScript: ChineseScript,
    ): String? {
        val rawTag = language?.value?.trim().orEmpty()
        if (rawTag.isEmpty() || rawTag.equals("und", ignoreCase = true)) return null
        return when {
            PhoneticGuideKind.isMandarinChinese(language) -> {
                val script = resolveMandarinScript(chineseScript)
                "zh-${script.iso15924}"
            }

            PhoneticGuideKind.isCantonese(language) -> "yue-${cantoneseScript.iso15924}"

            else -> rawTag
        }
    }
}

internal fun explicitChineseScriptForTag(rawTag: String?): ChineseScript? {
    val normalized = rawTag?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
    if (!normalized.startsWith("zh-") && !normalized.startsWith("cmn-")) return null
    val subtags = normalized.split('-').drop(1)
    return when {
        subtags.any { it == "hans" || it == "cn" || it == "sg" } -> ChineseScript.HANS
        subtags.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" } -> ChineseScript.HANT
        else -> null
    }
}

private fun transliterateChineseScript(text: String, targetScript: ChineseScript): String? {
    val transformId = if (targetScript == ChineseScript.HANS) "Hant-Hans" else "Hans-Hant"
    return runCatching {
        android.icu.text.Transliterator.getInstance(transformId).transliterate(text)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun transliterateToLatin(
    text: String,
    language: LanguageTag?,
    kind: PhoneticGuideKind,
): String? {
    val transformId = when (kind) {
        PhoneticGuideKind.PINYIN -> "Han-Latin"

        PhoneticGuideKind.IAST -> "Devanagari-Latin; Any-Latin"

        PhoneticGuideKind.ROMAJI -> "Katakana-Hiragana; Hiragana-Latin; Any-Latin; Latin-ASCII"

        PhoneticGuideKind.LATIN_TRANSLITERATION -> {
            val code = language?.value?.lowercase(Locale.ROOT).orEmpty()
            if (code.startsWith("ko")) "Hangul-Latin" else "Any-Latin"
        }
    }
    val icuResult = runCatching {
        android.icu.text.Transliterator.getInstance(transformId).transliterate(text)
    }.getOrNull()?.takeIf { it.isNotBlank() && it != text }
    if (icuResult != null) return icuResult

    // JVM ユニットテスト環境等で android.icu.text.Transliterator が未初期化の場合の純 Kotlin フォールバック
    return when (kind) {
        PhoneticGuideKind.ROMAJI -> fallbackKanaToRomaji(text)

        PhoneticGuideKind.LATIN_TRANSLITERATION -> {
            val code = language?.value?.lowercase(Locale.ROOT).orEmpty()
            if (code.startsWith("ko")) fallbackHangulToRomanization(text) else null
        }

        PhoneticGuideKind.IAST -> fallbackDevanagariToIast(text)

        PhoneticGuideKind.PINYIN -> null
    }?.takeIf { it.isNotBlank() && it != text }
}

private fun fallbackHangulToRomanization(text: String): String {
    val initials = arrayOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s",
        "ss", "", "j", "jj", "ch", "k", "t", "p", "h",
    )
    val medials = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa",
        "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    )
    val finals = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p",
        "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t",
        "k", "t", "p", "t",
    )
    val sb = StringBuilder()
    for (ch in text) {
        val code = ch.code
        if (code in 0xAC00..0xD7A3) {
            val syllableIndex = code - 0xAC00
            val initialIndex = syllableIndex / (21 * 28)
            val medialIndex = (syllableIndex % (21 * 28)) / 28
            val finalIndex = syllableIndex % 28
            sb.append(initials[initialIndex])
            sb.append(medials[medialIndex])
            sb.append(finals[finalIndex])
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

private fun fallbackKanaToRomaji(text: String): String {
    val kanaMap = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
    )
    val sb = StringBuilder()
    for (ch in text) {
        val hira = if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar() else ch
        sb.append(kanaMap[hira] ?: ch)
    }
    return sb.toString()
}

private fun fallbackDevanagariToIast(text: String): String {
    val vowels = mapOf(
        'अ' to "a", 'आ' to "ā", 'इ' to "i", 'ई' to "ī", 'उ' to "u", 'ऊ' to "ū",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )
    val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh",
        'च' to "c", 'छ' to "ch", 'ज' to "j", 'झ' to "jh",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "ś", 'ष' to "ṣ", 'स' to "s", 'ह' to "h",
    )
    val matras = mapOf(
        'ा' to "ā", 'ि' to "i", 'ी' to "ī", 'ु' to "u", 'ू' to "ū",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
    )
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val cBase = consonants[ch]
        if (cBase != null) {
            sb.append(cBase)
            val next = text.getOrNull(i + 1)
            when {
                next == '्' -> i += 2

                next != null && next in matras -> {
                    sb.append(matras[next])
                    i += 2
                }

                else -> {
                    sb.append('a')
                    i += 1
                }
            }
        } else if (ch in vowels) {
            sb.append(vowels[ch])
            i += 1
        } else if (ch == 'ं') {
            sb.append("ṃ")
            i += 1
        } else {
            sb.append(ch)
            i += 1
        }
    }
    return sb.toString()
}

/**
 * 詳細文字起こしダイアログで初期選択する認識言語を決定する。
 *
 * チャンクに自動認識（または既存設定）された言語があればその言語（[languagePair] の target / base にマッチする側、
 * またはその言語タグ）、なければ学習対象言語（[LanguagePair.target]）を返す。
 */
internal fun resolveInitialDetailedLanguage(
    chunkLanguage: LanguageTag?,
    languagePair: LanguagePair?,
): LanguageTag {
    val validChunkLang = chunkLanguage?.takeIf {
        it.value.isNotBlank() && !it.value.equals("und", ignoreCase = true)
    }
    if (validChunkLang != null) {
        if (languagePair != null) {
            if (matchesLanguageRole(validChunkLang, languagePair.target)) {
                return languagePair.target
            }
            val base = languagePair.base
            if (base != null && matchesLanguageRole(validChunkLang, base)) {
                return base
            }
        }
        return validChunkLang
    }
    return languagePair?.target ?: LanguageTag("en")
}

/**
 * 「より詳しく文字起こし」ダイアログで試験文字起こしを実行する前に検出された設定・準備状態の問題。
 */
enum class DetailedTranscriptionPrecheckIssue {
    /** `ANDROID_SPEECH_RECOGNIZER` が選択されているが、マイク権限（`RECORD_AUDIO`）が付与されていない。 */
    MIC_PERMISSION_REQUIRED,

    /** 選択中の言語とエンジンの音声認識モデルが未ダウンロードまたはダウンロード失敗（`NOT_INSTALLED` / `DOWNLOAD_FAILED`）。 */
    MODEL_DOWNLOAD_REQUIRED,

    /** 選択中の言語とエンジンの音声認識モデルを現在ダウンロード中（`DOWNLOADING`）。 */
    MODEL_DOWNLOADING,

    /** 選択中の文字起こしエンジンが選択中の言語に非対応（`UNSUPPORTED`）。 */
    ENGINE_UNSUPPORTED,
}

/**
 * チャンクの「より詳しく文字起こし」ダイアログの UI 状態。
 */
data class DetailedTranscriptionDialogState(
    val chunkId: ChunkId,
    val chunkIndex: Int,
    val engine: OnDeviceTranscriptionEngine = OnDeviceTranscriptionEngine.ML_KIT,
    val languageOverride: LanguageTag? = null,
    val audioSpeed: Float = 1.0f,
    val isRunning: Boolean = false,
    val hasAttempted: Boolean = false,
    val resultText: String = "",
    val trialEmptyMessage: String? = null,
    val recognizedLanguage: LanguageTag? = null,
    val recognizedEngineId: String? = null,
    val languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    val isPreviewPlaying: Boolean = false,
    val hasRecordAudioPermission: Boolean = true,
) {
    /** 選択中の認識言語タグ。 */
    val selectedLanguageTag: LanguageTag
        get() = languageOverride ?: recognizedLanguage ?: LanguageTag("en")

    /** 選択中の文字起こしエンジン。 */
    val selectedEngine: OnDeviceTranscriptionEngine
        get() = engine

    /**
     * 言語 [language] とエンジン [targetEngine]（およびマイク権限 [recordAudioPermissionGranted]）に対して、
     * 実行前にユーザーへ警告すべき設定・モデル準備の問題があれば返す。問題がなければ `null` を返す。
     */
    fun precheckIssue(
        language: LanguageTag? = languageOverride,
        targetEngine: OnDeviceTranscriptionEngine = engine,
        recordAudioPermissionGranted: Boolean = hasRecordAudioPermission,
    ): DetailedTranscriptionPrecheckIssue? {
        val status = engineStatus(language, targetEngine)
        return when {
            status == OnDeviceLanguageModelStatus.UNSUPPORTED ->
                DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED

            targetEngine == OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER && !recordAudioPermissionGranted ->
                DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED

            status == OnDeviceLanguageModelStatus.DOWNLOADING ->
                DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING

            status == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                status == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ->
                DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED

            else -> null
        }
    }

    /**
     * 指定した言語 [language] に対する [OnDeviceLanguageModelInfo] を返す。
     * [SupportedLanguageCatalog] の対応フラグも反映する。
     */
    fun modelInfoFor(language: LanguageTag?): OnDeviceLanguageModelInfo {
        val targetLang = language ?: languageOverride ?: LanguageTag("en")
        val normalized = LanguageSettings.normalize(targetLang)
        val mgmtTag = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(targetLang.value))
        val raw = languageModelInfos[targetLang]
            ?: languageModelInfos[normalized]
            ?: languageModelInfos[mgmtTag]
            ?: languageModelInfos.entries.firstOrNull { (k, _) -> matchesLanguageRole(k, targetLang) }?.value
            ?: OnDeviceLanguageModelInfo(language = targetLang)
        return enrichWithCatalog(raw, targetLang)
    }

    /**
     * 言語 [language] におけるエンジン [targetEngine] のモデル利用可能状態を返す。
     */
    fun engineStatus(
        language: LanguageTag?,
        targetEngine: OnDeviceTranscriptionEngine,
    ): OnDeviceLanguageModelStatus {
        val info = modelInfoFor(language)
        return when (targetEngine) {
            OnDeviceTranscriptionEngine.ML_KIT,
            OnDeviceTranscriptionEngine.AUTO,
            -> info.mlKitStatus

            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER -> info.status

            OnDeviceTranscriptionEngine.SHERPA_ONNX -> info.sherpaModelStatus
        }
    }

    /**
     * 詳細文字起こしダイアログで、言語 [language] に対してエンジン [targetEngine] の選択肢を有効（選択可能）にするかどうかを返す。
     *
     * - `ML_KIT`: 端末・言語で対応が確認されている（`UNSUPPORTED` でも `CHECKING` でもない）ときだけ有効。
     * - `ANDROID_SPEECH_RECOGNIZER`: 非対応（`UNSUPPORTED`）でないとき有効。
     * - `SHERPA_ONNX`: モデルがダウンロード済み（[OnDeviceLanguageModelStatus.INSTALLED]）のときだけ有効。
     */
    fun isEngineEnabled(
        language: LanguageTag?,
        targetEngine: OnDeviceTranscriptionEngine,
    ): Boolean {
        val status = engineStatus(language, targetEngine)
        return when (targetEngine) {
            OnDeviceTranscriptionEngine.ML_KIT,
            OnDeviceTranscriptionEngine.AUTO,
            -> status != OnDeviceLanguageModelStatus.UNSUPPORTED &&
                status != OnDeviceLanguageModelStatus.CHECKING

            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER ->
                status != OnDeviceLanguageModelStatus.UNSUPPORTED

            OnDeviceTranscriptionEngine.SHERPA_ONNX ->
                status == OnDeviceLanguageModelStatus.INSTALLED
        }
    }

    /**
     * 言語 [language] でモデルがダウンロード済みですぐに使えるもの（[OnDeviceLanguageModelStatus.INSTALLED]）の
     * うち最上位（`ML Kit` → `SpeechRecognizer` → `sherpa-onnx` の順）のエンジンを返す。
     * `INSTALLED` が 1 つもなければ、[isEngineEnabled] が `true` の最上位（フォールバックは `SpeechRecognizer`）を返す。
     */
    fun selectDefaultEngineForLanguage(language: LanguageTag?): OnDeviceTranscriptionEngine {
        val installed = ENGINE_OPTIONS.firstOrNull { candidate ->
            isEngineEnabled(language, candidate) &&
                engineStatus(language, candidate) == OnDeviceLanguageModelStatus.INSTALLED
        }
        if (installed != null) return installed
        val enabled = ENGINE_OPTIONS.firstOrNull { candidate ->
            isEngineEnabled(language, candidate)
        }
        return enabled ?: OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER
    }

    companion object {
        /** 詳細文字起こしで選択可能なエンジンの並び順（ML Kit → SpeechRecognizer → sherpa-onnx）。 */
        val ENGINE_OPTIONS: List<OnDeviceTranscriptionEngine> = listOf(
            OnDeviceTranscriptionEngine.ML_KIT,
            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            OnDeviceTranscriptionEngine.SHERPA_ONNX,
        )

        /** STT へ入力およびチャンク試聴する音声の速度倍率の選択肢。 */
        val AUDIO_SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.5f)

        /** [OnDeviceTranscriptionEngine] を対応する [SttEngine] に変換する。 */
        fun toSttEngine(engine: OnDeviceTranscriptionEngine): SttEngine = when (engine) {
            OnDeviceTranscriptionEngine.ML_KIT,
            OnDeviceTranscriptionEngine.AUTO,
            -> SttEngine.ML_KIT

            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER -> SttEngine.SPEECH_RECOGNIZER

            OnDeviceTranscriptionEngine.SHERPA_ONNX -> SttEngine.SHERPA_ONNX
        }

        internal fun enrichWithCatalog(
            info: OnDeviceLanguageModelInfo,
            language: LanguageTag,
        ): OnDeviceLanguageModelInfo = info.enrichedWithCatalog(language)
    }
}

/**
 * 詳細文字起こしの試行結果が空だったときに、モデル状態・エンジン・マイク権限に応じた具体的な理由メッセージを返す。
 */
internal fun resolveDetailedTrialEmptyMessage(
    targetLanguage: LanguageTag,
    engine: OnDeviceTranscriptionEngine,
    modelStatus: OnDeviceLanguageModelStatus,
    hasRecordAudioPermission: Boolean = true,
): String = when {
    modelStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
        modelStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ->
        "選択した言語（${targetLanguage.value}）の音声認識モデルがダウンロードされていません。モデルをダウンロードしてから再試行してください。"

    modelStatus == OnDeviceLanguageModelStatus.UNSUPPORTED ->
        "選択した文字起こしエンジンは言語（${targetLanguage.value}）に対応していません。別のエンジンを選択してください。"

    engine == OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER && !hasRecordAudioPermission ->
        "SpeechRecognizer で認識できませんでした。マイク権限が許可されているか確認するか、ML Kit / sherpa-onnx をお試しください。"

    else ->
        "音声を認識できませんでした。このチャンクが無音・BGM のみであるか、認識言語（現在: ${targetLanguage.value}）が実際の音声の言語と異なっている可能性があります。認識言語やエンジン・入力速度を変えて再試行してください。"
}

/**
 * バックグラウンド文字起こしを有効化した際、対象言語で `SpeechRecognizer` が使われる場合の警告ダイアログ状態。
 *
 * @property speechRecognizerLanguages 実効エンジンが [SttEngine.SPEECH_RECOGNIZER] となる言語の一覧。
 * @property sherpaSupportedLanguages [speechRecognizerLanguages] のうち `sherpa-onnx` がサポートされている言語の一覧。
 */
data class BackgroundSpeechRecognizerWarningState(
    val speechRecognizerLanguages: List<LanguageTag>,
    val sherpaSupportedLanguages: List<LanguageTag>,
)

/**
 * [OnDeviceLanguageModelInfo] とファイル別上書き [override]・全体設定 [userPreferred] から、
 * 実際に使用される [SttEngine] を解決する。
 */
internal fun OnDeviceLanguageModelInfo.effectiveEngine(
    override: SttEngine? = null,
    userPreferred: SttEngine? = null,
): SttEngine? {
    val enriched = enrichedWithCatalog(language)
    val preferred = override ?: userPreferred
    if (preferred != null) {
        val preferredStatus = enriched.statusOf(preferred)
        val isPreferredUsable = when (preferred) {
            SttEngine.ML_KIT ->
                preferredStatus == OnDeviceLanguageModelStatus.INSTALLED ||
                    preferredStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                    preferredStatus == OnDeviceLanguageModelStatus.DOWNLOADING

            SttEngine.SHERPA_ONNX ->
                preferredStatus == OnDeviceLanguageModelStatus.INSTALLED

            SttEngine.SPEECH_RECOGNIZER ->
                preferredStatus != OnDeviceLanguageModelStatus.UNSUPPORTED
        }
        if (isPreferredUsable) {
            return preferred
        }
    }
    return SttEngineSelection.resolveAutoEngine(enriched)
}

/**
 * プレイヤー画面の UI 状態（§6.2、F3.1〜F8.1）。
 *
 * 高頻度で変わる再生位置はここには含めず、[PositionProvider] から描画・子 Composable で読む。
 */
data class PlayerUiState(
    val mediaId: MediaId? = null,
    val title: String = "",
    val course: String? = null,
    val languagePair: LanguagePair? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val customSpeed: Float = DEFAULT_CUSTOM_SPEED,
    val seekBackMs: Long = UserSettings.DEFAULT_SEEK_BACK_MS,
    val seekForwardMs: Long = UserSettings.DEFAULT_SEEK_FORWARD_MS,
    val practice: PracticeSettings = PracticeSettings(),
    val autoPauseEndsAtMs: Long? = null,
    val waveform: Waveform? = null,
    val chunks: List<ChunkUi> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val activeChunkIndex: Int? = null,
    val analysis: Map<AnalysisStage, StageStatus> = emptyMap(),
    val error: PlaybackError? = null,
    val blindMode: BlindMode = BlindMode.OFF,
    val revealedChunkIds: Set<ChunkId> = emptySet(),
    val chineseScript: ChineseScript = ChineseScript.HANS,
    val cantoneseScript: ChineseScript = ChineseScript.HANT,
    val showTargetRomanization: Boolean = false,
    val showTargetTranslation: Boolean = false,
    val isTranslationSupported: Boolean = false,
    val translatingChunkId: ChunkId? = null,
    val failedTranslationChunkIds: Set<ChunkId> = emptySet(),
    val isTranscriptionProgressDismissed: Boolean = false,
    val chunkSource: ChunkOrigin = ChunkOrigin.AUTO,
    val availableChunkOrigins: Set<ChunkOrigin> = emptySet(),
    val segmentationParams: SegmentationParams = SegmentationParams(),
    val useGlobalSegmentationParams: Boolean = true,
    val transcriptImportBanner: TranscriptImportBannerState? = null,
    val transcriptExportBanner: TranscriptExportBannerState? = null,
    val transcriptOffsetMs: Int = 0,
    val lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
    val localTranscriptExported: Boolean = false,
    val detailedTranscriptionDialog: DetailedTranscriptionDialogState? = null,
    val itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
    val sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    val languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    val enabledLanguages: List<LanguageTag> = UserSettings.DEFAULT_ENABLED_LANGUAGES.toList(),
    val nextMediaId: MediaId? = null,
    val backgroundSpeechRecognizerWarning: BackgroundSpeechRecognizerWarningState? = null,
    val isLanguageFilterFallbackHighlighted: Boolean = false,
    val autoFollowResetToken: Long = 0L,
) {
    /** 文字起こしテキストを持つチャンクが 1 件以上あるかどうか。 */
    val hasTranscript: Boolean
        get() = chunks.any { !it.text.isNullOrBlank() }

    /** 言語タグ（未判定 `und` や空文字を除く）を持つチャンクが 1 件以上あるかどうか。 */
    val hasChunkLanguage: Boolean
        get() = chunks.any { chunk ->
            val tag = chunk.language?.value?.trim()
            !tag.isNullOrEmpty() && !tag.equals("und", ignoreCase = true)
        }

    /** 全チャンクの文字起こし（スキップ済み・空結果スキップ済みを含む）が完了し、実行中・待機中のキューもないかどうか。 */
    val isAllTranscriptionCompleted: Boolean
        get() {
            if (chunks.isEmpty()) return false
            if (lookaheadStatus.inFlightChunkId != null ||
                lookaheadStatus.isForcedChunkInFlight ||
                lookaheadStatus.queuedChunkIds.isNotEmpty()
            ) {
                return false
            }
            if (chunks.none { !it.text.isNullOrBlank() }) return false
            return chunks.all { chunk ->
                !chunk.text.isNullOrBlank() ||
                    chunk.isSkipped ||
                    chunk.id in lookaheadStatus.skippedEmptyChunkIds
            }
        }

    /** 開いている教材の言語のうち、端末内音声認識モデルに問題（未導入・DL中・失敗・非対応）がある状態マップ。 */
    val recognitionModelIssues: Map<LanguageTag, OnDeviceRecognitionModelState>
        get() = lookaheadStatus.recognitionModelIssues

    /** 学習言語（target）と母語（base）に同じ言語が設定されているかどうか。 */
    val hasSameTargetAndBaseLanguage: Boolean
        get() {
            val pair = languagePair ?: return false
            val base = pair.base ?: return false
            return matchesLanguageRole(pair.target, base)
        }

    /**
     * 先読み文字起こしが全対象チャンクを一通り試行し終えたものの、
     * 認識できたテキストが 1 件もなく（すべて空結果スキップまたはスキップ済み）、実行中・待機中のキューもない状態かどうか。
     */
    val isAllTranscriptionEmptyAfterCompletion: Boolean
        get() {
            if (chunks.isEmpty() || hasTranscript) return false
            if (lookaheadStatus.inFlightChunkId != null ||
                lookaheadStatus.isForcedChunkInFlight ||
                lookaheadStatus.queuedChunkIds.isNotEmpty()
            ) {
                return false
            }
            val finishedByCount = lookaheadStatus.skippedEmptyChunkIds.isNotEmpty() &&
                lookaheadStatus.totalTargetCount > 0 &&
                lookaheadStatus.completedCount >= lookaheadStatus.totalTargetCount
            val finishedBySkippedSet = lookaheadStatus.skippedEmptyChunkIds.isNotEmpty() &&
                chunks.all { chunk -> chunk.isSkipped || chunk.id in lookaheadStatus.skippedEmptyChunkIds }
            return finishedByCount || finishedBySkippedSet
        }

    /** 全チャンク分の文字起こしが完了しているかどうか。 */
    val isTranscriptionCompleted: Boolean
        get() = isAllTranscriptionCompleted

    /** 先読み文字起こしまたは強制再文字起こしキューが現在実行中かどうか。 */
    val isTranscriptionRunning: Boolean
        get() = lookaheadStatus.inFlightChunkId != null ||
            lookaheadStatus.isForcedChunkInFlight ||
            lookaheadStatus.queuedChunkIds.isNotEmpty() ||
            (
                lookaheadStatus.enabled &&
                    !isAllTranscriptionCompleted &&
                    !isAllTranscriptionEmptyAfterCompletion &&
                    chunks.isNotEmpty()
                )

    /** 文字起こし進捗ペーン（カード）を表示するかどうか。文字起こし開始時は自動で `true` になり、バッジタップでトグルできる。 */
    val isTranscriptionProgressVisible: Boolean
        get() = isTranscriptionRunning && !isTranscriptionProgressDismissed

    /** 翻訳表示が有効なとき、すべての対象チャンクの翻訳が完了しているかどうか。 */
    val isAllTranslationCompleted: Boolean
        get() {
            if (!showTargetTranslation || !isTranslationSupported) return true
            if (!isAllTranscriptionCompleted || translatingChunkId != null) return false
            val pair = languagePair ?: return true
            val baseLang = pair.base ?: return true
            return chunks.none { chunk ->
                val isTargetRole = matchesLanguageRole(chunk.language, pair.target) || chunk.language == null
                isTargetRole &&
                    !chunk.isSkipped &&
                    !chunk.text.isNullOrBlank() &&
                    chunk.id !in failedTranslationChunkIds &&
                    (
                        chunk.translatedText.isNullOrBlank() ||
                            (chunk.translatedLanguage != null && !matchesLanguageRole(chunk.translatedLanguage, baseLang))
                        )
            }
        }

    /** 「文字起こし」バッジを表示するかどうか（全文字起こし・有効時の全翻訳・実行中キューがすべて完了したら非表示）。 */
    val isTranscriptionBadgeVisible: Boolean
        get() {
            if (isTranscriptionRunning) return true
            if (chunks.isEmpty()) return false
            if (!isAllTranscriptionCompleted) return true
            if (showTargetTranslation && isTranslationSupported && !isAllTranslationCompleted) return true
            return false
        }

    /** 学習言語に対する読み方ガイド種別（ラテン文字言語の場合は `null`）。 */
    val targetPhoneticGuideKind: PhoneticGuideKind?
        get() = PhoneticGuideKind.forLanguage(languagePair?.target)

    /** 言語ラベルが未判定（`null` または `"und"`）の文字起こしチャンクが存在するかどうか（F1.4、F7.3）。 */
    val hasUnknownLanguageChunks: Boolean
        get() = chunks.any { chunk ->
            chunk.text != null &&
                (chunk.language == null || chunk.language.value.isBlank() || chunk.language.value == "und")
        }

    /** 現在位置より前にチェック済み（またはブックマーク済み）のチャンクがあるかどうか。 */
    val hasPreviousCheckedChunk: Boolean
        get() {
            val idx = activeChunkIndex ?: 0
            if (idx <= 0 || chunks.isEmpty()) return false
            val useRepeatChecked = chunks.any { it.isRepeatChecked }
            return chunks.subList(0, idx.coerceAtMost(chunks.size)).any { chunk ->
                if (useRepeatChecked) chunk.isRepeatChecked else chunk.isBookmarked
            }
        }

    /** 現在位置より後ろにチェック済み（またはブックマーク済み）のチャンクがあるかどうか。 */
    val hasNextCheckedChunk: Boolean
        get() {
            if (chunks.isEmpty()) return false
            val startIdx = (activeChunkIndex ?: -1) + 1
            if (startIdx !in chunks.indices) return false
            val useRepeatChecked = chunks.any { it.isRepeatChecked }
            return chunks.subList(startIdx, chunks.size).any { chunk ->
                if (useRepeatChecked) chunk.isRepeatChecked else chunk.isBookmarked
            }
        }

    companion object {
        /** 指定速度バッジの初期倍率（1.5 倍）。 */
        const val DEFAULT_CUSTOM_SPEED: Float = 1.5f

        /** 再生速度の微調整の刻み幅（0.05 倍、F3.2）。 */
        const val SPEED_STEP: Float = 0.05f

        /** 再生速度のプリセット一覧（F3.2、1.0x を除く）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f)

        /** 巻き戻し秒数のプリセット一覧（ミリ秒）。 */
        val SEEK_BACK_PRESETS_MS: List<Long> = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

        /** 早送り秒数のプリセット一覧（ミリ秒）。 */
        val SEEK_FORWARD_PRESETS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L)
    }
}

/** プレイヤー画面のイベント。 */
sealed interface PlayerEvent {
    /** 前の画面に戻る。 */
    data object Back : PlayerEvent

    /** 再生と一時停止を切り替える。 */
    data object TogglePlayPause : PlayerEvent

    /** 指定した位置（ミリ秒）にシークする。 */
    data class SeekTo(val positionMs: Long) : PlayerEvent

    /** 設定された秒数だけ巻き戻す。 */
    data object SeekBack : PlayerEvent

    /** 設定された秒数だけ早送りする。 */
    data object SeekForward : PlayerEvent

    /** 前のチャンク（または先頭）へ移動する（F3.4、F6.1）。 */
    data object PreviousChunk : PlayerEvent

    /** 次のチャンクへ移動する（F3.4、F6.1）。 */
    data object NextChunk : PlayerEvent

    /** 指定したチャンクの頭へシークする（F6.1）。 */
    data class SeekToChunk(val chunkId: ChunkId) : PlayerEvent

    /** 再生速度を指定した倍率（0.5〜2.0 倍）に変更する。 */
    data class SetSpeed(val speed: Float) : PlayerEvent

    /** 現在の再生速度を [delta]（例: `-0.05f` / `+0.05f`）だけ増減する。 */
    data class AdjustSpeed(val delta: Float) : PlayerEvent

    /** 指定速度（1x 以外のトグル対象速度）を指定した倍率（0.5〜2.0 倍）に変更し、その速度で再生する。 */
    data class SetCustomSpeed(val speed: Float) : PlayerEvent

    /** 指定速度（1x 以外のトグル対象速度）を [delta]（例: `-0.05f` / `+0.05f`）だけ増減し、その速度で再生する。 */
    data class AdjustCustomSpeed(val delta: Float) : PlayerEvent

    /** リピートモードを変更する（F6.1、F6.2）。 */
    data class SetLoopMode(val loopMode: LoopMode) : PlayerEvent

    /** 下部再生コントロールの「リピート」トグル（on / *off）を切り替える。 */
    data object ToggleRepeat : PlayerEvent

    /** リピートバッジの長押し確認ダイアログで「はい」が選ばれたときにファイル全体リピートを有効にする（チャンク選択は変更しない）。 */
    data object EnableWholeFileRepeat : PlayerEvent

    /** 下部再生コントロールの「無音スキップ」トグル（チャンク間の無音をスキップ）を切り替える。 */
    data object ToggleSkipSilence : PlayerEvent

    /** 自動ポーズの設定を変更する（F6.3）。 */
    data class SetAutoPause(val autoPause: AutoPause) : PlayerEvent

    /** 自動ポーズの範囲（すべてのチャンク / 母語チャンクの後のみ）を変更する（F6.5）。 */
    data class SetAutoPauseScope(val scope: AutoPauseScope) : PlayerEvent

    /** 瞬間作文モード（母語チャンクの後に自動ポーズ）の ON / OFF を切り替える（F6.5）。 */
    data object ToggleInstantComposition : PlayerEvent

    /** 言語フィルタ再生（すべて / 学習言語のみ / 母語のみ / ☑ のみ）を変更する（F6.4）。 */
    data class SetLanguageFilter(val filter: LanguageFilter) : PlayerEvent

    /** ブックマークのみ再生フィルタ（`PracticeSettings.bookmarksOnly`）の ON / OFF を切り替える（F8.1）。 */
    data object ToggleBookmarksOnly : PlayerEvent

    /** 指定したチャンクのブックマークを追加または解除する（F8.1）。 */
    data class ToggleBookmark(val chunkId: ChunkId) : PlayerEvent

    /** 指定したチャンクの連続無限リピートチェック（`PracticeSettings.repeatChunkIds`）をトグルする（F6.1）。 */
    data class ToggleChunkRepeat(val chunkId: ChunkId) : PlayerEvent

    /** 指定したチャンクを選択状態（`PracticeSettings.repeatChunkIds`）にする、または選択を外す（拡大波形の長押し）。 */
    data class SetChunkSelected(val chunkId: ChunkId, val selected: Boolean) : PlayerEvent

    /** チャンクの選択（チェックボックス）をすべて外す。 */
    data object ClearChunkSelection : PlayerEvent

    /** 拡大波形で選択された範囲（[LoopMode.AbRange]）を解除し、選択範囲の中央へ再生位置を移動する。 */
    data object ClearWaveformRangeToCenter : PlayerEvent

    /** 拡大波形で選択された範囲（[LoopMode.AbRange]）を解除し、タッチした位置 [positionMs] へ再生位置を移動する。 */
    data class DismissWaveformRangeAndSeek(val positionMs: Long) : PlayerEvent

    /** 拡大波形で選択されたチャンク以外の範囲（[LoopMode.AbRange]）に新しいチャンクを追加する。 */
    data object AddChunkInSelectedRange : PlayerEvent

    /** 拡大波形で選択された範囲（[LoopMode.AbRange]）に含まれる複数のチャンクを 1 つに連結する。 */
    data object MergeChunksInSelectedRange : PlayerEvent

    /** 拡大波形で選択された範囲（[LoopMode.AbRange]）に含まれるチャンクを一括で解除（削除）する。 */
    data object DeleteChunksInSelectedRange : PlayerEvent

    /** 選択中のチャンク（先頭から末尾までの範囲）を 1 つに連結する（言語は最初のチャンクの設定に合わせる）。 */
    data object MergeSelectedChunks : PlayerEvent

    /** 現在の再生位置（拡大波形の中央のカーソル）でチャンクを 2 つに分割する。 */
    data object SplitChunkAtCurrentPosition : PlayerEvent

    /** 開いている教材の学習言語とベース言語を変更する。 */
    data class UpdateItemLanguages(val target: LanguageTag, val base: LanguageTag?) : PlayerEvent

    /** 全体設定で選択可能な有効言語の ON / OFF を切り替える（最低 1 言語は維持）。 */
    data class ToggleEnabledLanguage(val language: LanguageTag) : PlayerEvent

    /** ブラインドモードを変更する（F7.2）。 */
    data class SetBlindMode(val blindMode: BlindMode) : PlayerEvent

    /** ブラインドモード中に特定のチャンクの一時表示を切り替える（F7.2）。 */
    data class ToggleRevealChunk(val chunkId: ChunkId) : PlayerEvent

    /** 学習言語の読み方ガイド（ローマ字 / IAST / Pinyin 等）の表示 ON / OFF を切り替える。 */
    data class SetShowTargetRomanization(val enabled: Boolean) : PlayerEvent

    /** 学習言語からベース言語への翻訳テキスト表示の ON / OFF を切り替える。 */
    data class SetShowTargetTranslation(val enabled: Boolean) : PlayerEvent

    /** 指定したチャンク群のスキップ状態（`Chunk.isSkipped`）を設定または解除する。 */
    data class SetChunksSkipped(val chunkIds: Set<ChunkId>, val skipped: Boolean) : PlayerEvent

    /** チャンクの出どころ（文字起こし / 自動分割）を切り替える（F5.4）。 */
    data class SetChunkSource(val origin: ChunkOrigin) : PlayerEvent

    /** 分割パラメータを変更して再分割を実行する（F5.2）。 */
    data class Resegment(val params: SegmentationParams) : PlayerEvent

    /** チャンク分割パラメータで「全体設定に従う」の ON / OFF を切り替える。 */
    data class SetUseGlobalSegmentation(val useGlobal: Boolean) : PlayerEvent

    /** チャンク設定に従ってチャンク分割をやり直し、文字起こしもクリアする。 */
    data class ResetChunkSegmentation(val params: SegmentationParams? = null) : PlayerEvent

    /** チャンク 1 件の言語ラベルを手動で更新する（F7.3）。 */
    data class UpdateChunkLanguage(val chunkId: ChunkId, val language: LanguageTag?) : PlayerEvent

    /** チャンク 1 件の文字起こし本文を手動で更新する（F7.4）。 */
    data class UpdateChunkText(val chunkId: ChunkId, val text: String) : PlayerEvent

    /** 指定したチャンクの文字起こしキャッシュを破棄して強制的に再度文字起こしする。 */
    data class RetranscribeChunk(val chunkId: ChunkId) : PlayerEvent

    /** 指定したチャンクを [splitPositionMs] で 2 つに分割する（F5.3）。 */
    data class SplitChunk(val chunkId: ChunkId, val splitPositionMs: Long) : PlayerEvent

    /** 指定したチャンクを直後のチャンクと結合する（F5.3）。 */
    data class MergeWithNextChunk(val chunkId: ChunkId) : PlayerEvent

    /** 指定したチャンクの開始・終了時刻を微調整する（F5.3）。 */
    data class MoveChunkBoundary(
        val chunkId: ChunkId,
        val newStartMs: Long,
        val newEndMs: Long,
    ) : PlayerEvent

    /** 指定したチャンクを解除（削除）する（F5.3）。 */
    data class DeleteChunk(val chunkId: ChunkId) : PlayerEvent

    /** 言語未設定（`null` / `und`）のチャンクを一括で指定言語に設定する（F1.4、F7.3）。 */
    data class BulkSetUnknownTranscriptLanguage(val language: LanguageTag) : PlayerEvent

    /** 文字起こしの時刻オフセット（ミリ秒）を設定する（F7.6）。 */
    data class SetTranscriptOffset(val offsetMs: Int) : PlayerEvent

    /** 文字起こしの時刻オフセットを [deltaMs] だけ微調整する（F7.6）。 */
    data class AdjustTranscriptOffset(val deltaMs: Int) : PlayerEvent

    /** 特徴量の有声開始とチャンク開始の差から文字起こしオフセットを自動補正する（F7.6）。 */
    data object AutoAlignTranscriptOffset : PlayerEvent

    /** 端末内の先読み簡易文字起こし（F6.2）の有効・無効を切り替える。 */
    data class SetLookaheadEnabled(val enabled: Boolean) : PlayerEvent

    /** バックグラウンドでの文字起こし継続の有効・無効を切り替える。 */
    data class SetBackgroundTranscriptionEnabled(val enabled: Boolean) : PlayerEvent

    /** バックグラウンド文字起こしでの `SpeechRecognizer` 制限警告ダイアログを閉じる。 */
    data object DismissBackgroundSpeechRecognizerWarning : PlayerEvent

    /** 文字起こし実行中に進捗ペーン（カード）の表示・非表示を切り替える。 */
    data object ToggleTranscriptionProgress : PlayerEvent

    /** 実行中・待機中のすべての文字起こし処理とロックを強制中断・リセットする。 */
    data object ResetTranscription : PlayerEvent

    /** 指定チャンク位置からの先読み簡易文字起こしを要求する。 */
    data class TriggerLookahead(val chunkId: ChunkId?) : PlayerEvent

    /** [language] の端末内音声認識モデルのダウンロードを OS に要求する（モデルが未ダウンロードのとき）。 */
    data class RequestRecognitionModelDownload(val language: LanguageTag) : PlayerEvent

    /** 文字起こしをローカル `.chunkflow.json` に書き出す。 */
    data object ExportLocalTranscriptJson : PlayerEvent

    /** 文字起こし・字幕ファイルを指定フォーマットで SAF の [uri] へ書き出す（F7.5）。 */
    data class ExportTranscriptToUri(val format: TranscriptExportFormat, val uri: Uri) : PlayerEvent

    /** 字幕書き出し通知バナーを閉じる。 */
    data object DismissExportBanner : PlayerEvent

    /** 文字起こし JSON / 字幕ファイルを取り込む（F1.3、F1.4、§7.5.5）。 */
    data class ImportTranscript(val uri: Uri) : PlayerEvent

    /** 現在の教材の文字起こしをリセットし、保存済み文字起こしファイルを削除する。 */
    data object ResetTranscripts : PlayerEvent

    /** 文字起こし取り込み結果のバナーを閉じる。 */
    data object DismissTranscriptBanner : PlayerEvent

    /** チャンクの「より詳しく文字起こし」ダイアログを開く。 */
    data class OpenDetailedTranscription(val chunkId: ChunkId) : PlayerEvent

    /** チャンクの「より詳しく文字起こし」ダイアログを閉じる。 */
    data object DismissDetailedTranscription : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログで指定した条件で試験文字起こしを実行する。 */
    data class RunDetailedTranscriptionTrial(
        val chunkId: ChunkId,
        val engine: OnDeviceTranscriptionEngine,
        val languageOverride: LanguageTag?,
        val audioSpeed: Float,
        val hasRecordAudioPermission: Boolean = true,
    ) : PlayerEvent

    /** 端末のマイク権限（`RECORD_AUDIO`）の付与状態を UI 状態へ同期する。 */
    data class SetRecordAudioPermissionGranted(val granted: Boolean) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログの結果をチャンクの文字起こしへ反映して閉じる。 */
    data class ApplyDetailedTranscriptionResult(
        val chunkId: ChunkId,
        val text: String,
        val language: LanguageTag?,
    ) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログで認識言語を切り替える（対応する既定エンジンも自動選択する）。 */
    data class SelectDetailedTranscriptionLanguage(val language: LanguageTag) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログで文字起こしエンジンを切り替える。 */
    data class SelectDetailedTranscriptionEngine(val engine: OnDeviceTranscriptionEngine) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログから指定言語・エンジンのモデルダウンロードを要求する。 */
    data class RequestDetailedTranscriptionModelDownload(
        val language: LanguageTag,
        val engine: OnDeviceTranscriptionEngine,
    ) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログで対象チャンクを指定速度でプレビュー再生する。 */
    data class PlayDetailedTranscriptionChunkPreview(
        val chunkId: ChunkId,
        val speed: Float,
    ) : PlayerEvent

    /** 「より詳しく文字起こし」ダイアログのチャンクプレビュー再生を停止し、元の再生速度へ戻す。 */
    data object StopDetailedTranscriptionChunkPreview : PlayerEvent

    /** ファイル設定の対象言語の文字起こしエンジンを上書きする。 */
    data class SetTargetEngineOverride(val engine: com.miuraken.chunkflow.core.model.SttEngine?) : PlayerEvent

    /** ファイル設定のベース言語の文字起こしエンジンを上書きする。 */
    data class SetBaseEngineOverride(val engine: com.miuraken.chunkflow.core.model.SttEngine?) : PlayerEvent

    /** 巻き戻し秒数（ミリ秒）を変更する。 */
    data class SetSeekBackMs(val durationMs: Long) : PlayerEvent

    /** 早送り秒数（ミリ秒）を変更する。 */
    data class SetSeekForwardMs(val durationMs: Long) : PlayerEvent

    /** ファイルの先頭（最初の有効チャンクまたは 0ms）へ移動する。 */
    data object SeekToFileStart : PlayerEvent

    /** 現在位置より前のチェック項目（選択チャンクまたはブックマーク）へ移動する。 */
    data object SeekToPreviousCheckedChunk : PlayerEvent

    /** 現在位置より後ろのチェック項目（選択チャンクまたはブックマーク）へ移動する。 */
    data object SeekToNextCheckedChunk : PlayerEvent

    /** 同じフォルダ（コース）内の次の教材ファイルを開いて再生する。 */
    data object OpenNextMediaItem : PlayerEvent
}

/** 端末内音声認識モデルの問題が残っている間、ダウンロードの完了を OS に問い合わせる間隔。 */
private const val RECOGNITION_MODEL_REFRESH_INTERVAL_MS: Long = 30_000L

/** 言語バッジの最後のトグルから、文字起こしのやり直しを始めるまでの待ち時間（誤タップ対策）。 */
internal const val LANGUAGE_TOGGLE_COMMIT_DELAY_MS: Long = 1_000L

/** 「☑ のみ」からすべてのチェックが外れて「両方」へ自動復帰したときに言語フィルタバッジを強調表示する時間（ミリ秒）。 */
internal const val LANGUAGE_FILTER_FALLBACK_HIGHLIGHT_MS: Long = 3_000L

private data class AnalysisSnapshot(
    val status: Map<AnalysisStage, StageStatus> = emptyMap(),
    val waveform: Waveform? = null,
    val chunks: List<Chunk> = emptyList(),
    val transcripts: Map<ChunkId, Transcript> = emptyMap(),
    val bookmarks: List<Bookmark> = emptyList(),
    val availableOrigins: Set<ChunkOrigin> = emptySet(),
    val lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
)

private data class LocalUiSnapshot(
    val customSpeedOverride: Float? = null,
    val blindMode: BlindMode = BlindMode.OFF,
    val revealedChunkIds: Set<ChunkId> = emptySet(),
    val showTargetTranslation: Boolean = false,
    val translatingChunkId: ChunkId? = null,
    val failedTranslationChunkIds: Set<ChunkId> = emptySet(),
    val isTranscriptionProgressDismissed: Boolean = false,
    val useGlobalSegmentationOverride: Boolean? = null,
    val customSegmentationParams: SegmentationParams? = null,
    val languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    val transcriptImportBanner: TranscriptImportBannerState? = null,
    val transcriptExportBanner: TranscriptExportBannerState? = null,
    val transcriptOffsetMs: Int = 0,
    val localTranscriptExported: Boolean = false,
    val languageOverrides: Map<ChunkId, LanguageTag?> = emptyMap(),
    val clearingChunkIds: Set<ChunkId> = emptySet(),
    val manuallyClearedChunkIds: Set<ChunkId> = emptySet(),
    val detailedTranscriptionDialog: DetailedTranscriptionDialogState? = null,
    val hasRecordAudioPermission: Boolean = true,
    val backgroundSpeechRecognizerWarning: BackgroundSpeechRecognizerWarningState? = null,
    val isLookaheadEnabled: Boolean = false,
    val isLanguageFilterFallbackHighlighted: Boolean = false,
    val autoFollowResetToken: Long = 0L,
)

/**
 * プレイヤー画面の ViewModel（§6.2、F3.1〜F8.1）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val analysisRepository: AnalysisRepository,
    private val playbackController: PlaybackController,
    private val userSettingsRepository: UserSettingsRepository,
    private val itemSttOverrideRepository: com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        libraryRepository = libraryRepository,
        analysisRepository = FakeAnalysisRepository(),
        playbackController = playbackController,
        userSettingsRepository = userSettingsRepository,
        itemSttOverrideRepository = object : com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository {
            override fun observe(id: MediaId): kotlinx.coroutines.flow.Flow<com.miuraken.chunkflow.core.model.ItemSttOverride> = kotlinx.coroutines.flow.flowOf(com.miuraken.chunkflow.core.model.ItemSttOverride.NONE)
            override suspend fun get(id: MediaId): com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
            override suspend fun set(id: MediaId, override: com.miuraken.chunkflow.core.model.ItemSttOverride): Boolean = true
        },
    )

    private val currentMediaId = MutableStateFlow<MediaId?>(null)
    private val localUiState = MutableStateFlow(LocalUiSnapshot())
    private val perMediaLookaheadEnabled = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
    private val prioritizedMediaIds = MutableStateFlow<Set<MediaId>>(emptySet())
    private var lastOpenedMediaId: MediaId? = null

    /** 言語バッジのトグル後、文字起こしのやり直しを待っているチャンクごとのジョブ（最後のトグルから 1 秒後に実行）。 */
    private val pendingLanguageCommitJobs = mutableMapOf<ChunkId, Job>()
    private var lookaheadLoopJob: Job? = null
    private var translationLoopJob: Job? = null
    private val failedTranslationChunkIds = mutableSetOf<ChunkId>()
    private var detailedTrialJob: Job? = null
    private var detailedPreviewJob: Job? = null
    private var savedSpeedBeforeDetailedPreview: Float? = null
    private var retranscribeJob: Job? = null
    private var chunkSeekPrioritizeJob: Job? = null
    private var languageFilterHighlightJob: Job? = null

    /** 高頻度で変わる再生位置のプロバイダ（§6.2）。 */
    val positionProvider: PositionProvider
        get() = playbackController.position

    private val currentAudioItem = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItem(id)
    }

    private val currentItemState = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItemState(id)
    }

    private val currentItemSttOverride = currentMediaId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(com.miuraken.chunkflow.core.model.ItemSttOverride.NONE) else itemSttOverrideRepository.observe(id)
    }

    private val currentAnalysisSnapshot = currentMediaId.flatMapLatest { id ->
        if (id == null) {
            flowOf(AnalysisSnapshot())
        } else {
            combine(
                combine(
                    analysisRepository.observeStatus(id),
                    analysisRepository.observeWaveform(id),
                    analysisRepository.observeChunks(id),
                    analysisRepository.observeBookmarks(id),
                ) { status, waveform, chunks, bookmarks ->
                    Quad(status, waveform, chunks, bookmarks)
                },
                analysisRepository.observeTranscripts(id),
                analysisRepository.observeAvailableChunkOrigins(id),
                analysisRepository.observeLookaheadStatus(id),
            ) { (status, waveform, chunks, bookmarks), transcripts, origins, lookahead ->
                AnalysisSnapshot(
                    status = status,
                    waveform = waveform,
                    chunks = chunks,
                    transcripts = transcripts,
                    bookmarks = bookmarks,
                    availableOrigins = origins,
                    lookaheadStatus = lookahead,
                )
            }
        }
    }

    private data class MediaStateInfo(
        val mediaId: MediaId?,
        val item: AudioItem?,
        val itemState: ItemState?,
        val analysis: AnalysisSnapshot,
        val itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        val nextMediaId: MediaId? = null,
    )

    private val currentItemAndNextMediaFlow = combine(
        currentAudioItem,
        libraryRepository.observeItems(),
    ) { item, allItems ->
        val nextId = if (item == null) {
            null
        } else {
            val sameCourse = allItems
                .filter { it.course == item.course }
                .sortedWith { a, b ->
                    val cmp = compareFileNamesNatural(a.title, b.title)
                    if (cmp != 0) cmp else a.id.value.compareTo(b.id.value)
                }
            val idx = sameCourse.indexOfFirst { it.id == item.id }
            if (idx >= 0) sameCourse.getOrNull(idx + 1)?.id else null
        }
        item to nextId
    }

    private val mediaAndAnalysisFlow = combine(
        currentMediaId,
        currentItemAndNextMediaFlow,
        currentItemState,
        currentAnalysisSnapshot,
        currentItemSttOverride,
    ) { mediaId, (item, nextMediaId), itemState, analysis, override ->
        MediaStateInfo(mediaId, item, itemState, analysis, override, nextMediaId)
    }

    /** プレイヤー画面の UI 状態。 */
    val uiState: StateFlow<PlayerUiState> = combine(
        mediaAndAnalysisFlow,
        playbackController.status,
        playbackController.practice,
        userSettingsRepository.settings,
        localUiState,
    ) { mediaState, status, practice, settings, local ->
        val mediaId = mediaState.mediaId
        val item = mediaState.item
        val itemState = mediaState.itemState
        val analysis = mediaState.analysis
        val override = mediaState.itemSttOverride
        buildUiState(
            mediaId = mediaId,
            item = item,
            itemState = itemState,
            analysis = analysis,
            status = status,
            practice = practice,
            settings = settings,
            local = local,
            itemSttOverride = override,
            nextMediaId = mediaState.nextMediaId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    init {
        // ファイル単位の「文字起こし」トグル（既定は OFF）と開いている教材・チャンク読み込みを同期する
        viewModelScope.launch {
            combine(
                currentMediaId,
                perMediaLookaheadEnabled,
                currentAnalysisSnapshot
                    .map { it.chunks.firstOrNull()?.id }
                    .distinctUntilChanged(),
            ) { mediaId, enabledMap, firstChunkId ->
                val enabled = if (mediaId != null) enabledMap[mediaId] ?: false else false
                Triple(mediaId, enabled, if (enabled) firstChunkId else null)
            }.distinctUntilChanged().collect { (mediaId, enabled, _) ->
                if (mediaId != null) {
                    syncLookaheadState(mediaId, enabled)
                }
            }
        }

        // 再生中のチャンクが切り替わるたび、または再生開始時に先読み文字起こしを通知する
        viewModelScope.launch {
            combine(
                currentMediaId,
                playbackController.status.map { it.currentChunkId }.distinctUntilChanged(),
                playbackController.status.map { it.isPlaying }.distinctUntilChanged(),
            ) { mediaId, activeChunkId, isPlaying ->
                Triple(mediaId, activeChunkId, isPlaying)
            }.distinctUntilChanged().collectLatest { (mediaId, activeChunkId, isPlaying) ->
                if (mediaId != null && lastOpenedMediaId == mediaId) {
                    val lookaheadEnabled = perMediaLookaheadEnabled.value[mediaId] ?: false
                    if (!lookaheadEnabled) return@collectLatest
                    if (activeChunkId != null) {
                        analysisRepository.triggerLookaheadAt(mediaId, activeChunkId)
                    } else if (isPlaying) {
                        val targetId = uiState.value.chunks.firstOrNull { isChunkUndetected(it) }?.id
                            ?: uiState.value.chunks.firstOrNull()?.id
                        analysisRepository.triggerLookaheadAt(mediaId, targetId)
                    }
                }
            }
        }

        // 「翻訳」トグルが ON のとき、文字起こし済みかつ未翻訳チャンクの発生に合わせて順次翻訳を開始する
        viewModelScope.launch {
            uiState
                .map { state ->
                    val untranslatedCount = if (state.showTargetTranslation && state.isTranslationSupported && state.hasTranscript) {
                        state.chunks.count { chunk -> isChunkEligibleForTranslation(chunk, state.languagePair) }
                    } else {
                        0
                    }
                    Triple(state.mediaId, state.showTargetTranslation && state.isTranslationSupported && state.hasTranscript, untranslatedCount)
                }
                .distinctUntilChanged()
                .collect { (_, canTranslate, untranslatedCount) ->
                    if (!canTranslate) {
                        stopTranslationLoop()
                    } else if (untranslatedCount > 0 && translationLoopJob?.isActive != true) {
                        startTranslationLoopIfNeeded()
                    }
                }
        }

        // 文字起こしが停止したときに進捗ペーン非表示フラグをリセットし、次回開始時に自動表示されるようにする
        viewModelScope.launch {
            uiState
                .map { it.isTranscriptionRunning }
                .distinctUntilChanged()
                .collect { isRunning ->
                    if (!isRunning) {
                        localUiState.update { state ->
                            if (state.isTranscriptionProgressDismissed) {
                                state.copy(isTranscriptionProgressDismissed = false)
                            } else {
                                state
                            }
                        }
                    }
                }
        }

        // 文字起こし完了等で DB 側の transcript（本文あり）が更新されたら、コミット待ちでない古い languageOverrides を消去する
        viewModelScope.launch {
            currentAnalysisSnapshot
                .map { it.transcripts }
                .distinctUntilChanged()
                .collect { transcripts ->
                    val currentOverrides = localUiState.value.languageOverrides
                    if (currentOverrides.isEmpty()) return@collect
                    val staleChunkIds = currentOverrides.keys.filter { chunkId ->
                        pendingLanguageCommitJobs[chunkId]?.isActive != true &&
                            !transcripts[chunkId]?.text.isNullOrBlank()
                    }
                    if (staleChunkIds.isNotEmpty()) {
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - staleChunkIds.toSet())
                        }
                    }
                }
        }
    }

    /** 開いている教材の言語（学習言語・母語）のうち、端末内音声認識モデルに問題がある言語。 */
    private val currentRecognitionModelIssueLanguages: Flow<Pair<MediaId?, Set<LanguageTag>>> =
        currentMediaId.flatMapLatest { id ->
            if (id == null) {
                flowOf(null to emptySet())
            } else {
                combine(
                    analysisRepository.observeLookaheadStatus(id),
                    libraryRepository.observeItem(id),
                ) { status, item ->
                    val pair = item?.languagePair
                    id to status.recognitionModelIssues.keys.filter { language ->
                        matchesLanguageRole(language, pair?.target) || matchesLanguageRole(language, pair?.base)
                    }.toSet()
                }
            }
        }.distinctUntilChanged()

    init {
        // 端末内音声認識モデルの問題（未ダウンロードなど）が解消したら、言語判定のみだったチャンクを認識し直す
        viewModelScope.launch {
            var previousMediaId: MediaId? = null
            var previousLanguages: Set<LanguageTag> = emptySet()
            currentRecognitionModelIssueLanguages.collect { (mediaId, languages) ->
                val resolved = if (mediaId == previousMediaId) previousLanguages - languages else emptySet()
                previousMediaId = mediaId
                previousLanguages = languages
                if (mediaId != null &&
                    resolved.isNotEmpty() &&
                    lastOpenedMediaId == mediaId &&
                    uiState.value.lookaheadStatus.enabled
                ) {
                    syncLookaheadState(mediaId, true)
                }
            }
        }

        // 問題が残っている間は、ダウンロードの完了を検出するため定期的に OS へ問い合わせる
        viewModelScope.launch {
            currentRecognitionModelIssueLanguages
                .map { (_, languages) -> languages.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { hasIssues ->
                    while (hasIssues && isActive) {
                        delay(RECOGNITION_MODEL_REFRESH_INTERVAL_MS)
                        analysisRepository.refreshRecognitionModelIssues()
                    }
                }
        }

        // 再生設定の「チャンク間の無音をスキップ」と PracticeSettings.skipOriginalSilence を同期する
        viewModelScope.launch {
            userSettingsRepository.settings
                .map { it.skipOriginalSilence }
                .distinctUntilChanged()
                .collect { skipSilence ->
                    playbackController.updatePractice { current ->
                        if (current.skipOriginalSilence == skipSilence) {
                            current
                        } else {
                            current.copy(skipOriginalSilence = skipSilence)
                        }
                    }
                }
        }

        // 「ファイルを開いたら文字起こしを自動で始める」が有効な場合、チャンク分割と言語判定が完了した時点で文字起こしを自動開始する
        viewModelScope.launch {
            combine(
                currentMediaId,
                perMediaLookaheadEnabled,
                prioritizedMediaIds,
                userSettingsRepository.settings.map { it.autoStartTranscriptionOnOpen }.distinctUntilChanged(),
                currentAnalysisSnapshot,
            ) { mediaId, perMediaMap, prioritizedSet, autoStartOnOpen, snapshot ->
                if (mediaId == null) return@combine null
                if (perMediaMap.containsKey(mediaId)) return@combine null
                if (!autoStartOnOpen) return@combine null
                if (mediaId !in prioritizedSet) return@combine null
                if (snapshot.chunks.isEmpty()) return@combine null
                if (snapshot.status[AnalysisStage.FEATURES] is StageStatus.Running) return@combine null
                if (snapshot.status[AnalysisStage.SEGMENTATION] is StageStatus.Running) return@combine null
                if (snapshot.lookaheadStatus.isLanguageDetectionRunning) return@combine null
                val hasUntranscribedChunk = snapshot.chunks.any { chunk ->
                    !chunk.isSkipped && snapshot.transcripts[chunk.id]?.text.isNullOrBlank()
                }
                if (!hasUntranscribedChunk) return@combine null
                mediaId
            }
                .distinctUntilChanged()
                .collect { readyMediaId ->
                    if (readyMediaId != null && !perMediaLookaheadEnabled.value.containsKey(readyMediaId)) {
                        perMediaLookaheadEnabled.update { it + (readyMediaId to true) }
                        if (lastOpenedMediaId == readyMediaId) {
                            localUiState.update { it.copy(isLookaheadEnabled = true) }
                            syncLookaheadState(readyMediaId, true)
                        }
                    }
                }
        }
    }

    /** 指定した教材を開いて再生準備を行う。[initialChunkId] が指定された場合は該当チャンクの先頭へ一時停止状態で移動する。 */
    fun open(mediaId: MediaId, initialChunkId: ChunkId? = null) {
        val previousId = lastOpenedMediaId
        if (previousId != null && previousId != mediaId) {
            analysisRepository.setMediaTranscriptionSessionOpen(previousId, false)
        }
        analysisRepository.setMediaTranscriptionSessionOpen(mediaId, true)
        currentMediaId.value = mediaId
        if (previousId == mediaId && initialChunkId == null) {
            if (lookaheadLoopJob == null) {
                val enabled = perMediaLookaheadEnabled.value[mediaId] ?: false
                syncLookaheadState(mediaId, enabled)
            }
            return
        }
        lastOpenedMediaId = mediaId
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
        translationLoopJob?.cancel()
        translationLoopJob = null
        failedTranslationChunkIds.clear()
        detailedTrialJob?.cancel()
        detailedTrialJob = null
        stopDetailedChunkPreview(pausePlayer = false)
        retranscribeJob?.cancel()
        retranscribeJob = null
        chunkSeekPrioritizeJob?.cancel()
        chunkSeekPrioritizeJob = null
        languageFilterHighlightJob?.cancel()
        languageFilterHighlightJob = null
        prioritizedMediaIds.update { it - mediaId }
        val enabled = perMediaLookaheadEnabled.value[mediaId] ?: false
        localUiState.value = LocalUiSnapshot(isLookaheadEnabled = enabled)
        syncLookaheadState(mediaId, enabled)
        viewModelScope.launch {
            val savedState = libraryRepository.observeItemState(mediaId).firstOrNull()
            val initialSpeed = savedState?.speed ?: 1.0f
            if (initialChunkId != null) {
                playbackController.player.value?.pause()
                val targetChunk = analysisRepository.observeChunks(mediaId).firstOrNull()
                    ?.firstOrNull { it.id == initialChunkId }
                val targetPos = targetChunk?.startMs ?: savedState?.positionMs ?: 0L
                libraryRepository.updatePlaybackPosition(
                    id = mediaId,
                    positionMs = targetPos,
                    speed = initialSpeed,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                playbackController.open(mediaId)
                playbackController.player.value?.let { player ->
                    player.pause()
                    player.seekTo(targetPos)
                }
                if (playbackController is FakePlaybackController) {
                    playbackController.position.positionMs = targetPos
                    playbackController.setSpeed(initialSpeed)
                    playbackController.statusState.update { status ->
                        status.copy(
                            mediaId = mediaId,
                            isPlaying = false,
                            currentChunkId = initialChunkId,
                            autoPauseEndsAtMs = null,
                        )
                    }
                }
            } else {
                playbackController.open(mediaId)
                if (playbackController is FakePlaybackController) {
                    val initialPos = savedState?.positionMs ?: 0L
                    playbackController.position.positionMs = initialPos
                    playbackController.setSpeed(initialSpeed)
                    libraryRepository.updatePlaybackPosition(
                        id = mediaId,
                        positionMs = initialPos,
                        speed = initialSpeed,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                analysisRepository.prioritizeOpenedMediaAnalysis(mediaId)
            } finally {
                prioritizedMediaIds.update { it + mediaId }
            }
        }
    }

    /**
     * 現在開いている教材ウィンドウを閉じ、待機中の先読み文字起こしキューを停止する。
     * すでに処理中（in-flight）の 1 チャンクの文字起こしは中断せず最後まで実行して出力される。
     */
    fun close(mediaId: MediaId? = currentMediaId.value) {
        val targetId = mediaId ?: lastOpenedMediaId ?: currentMediaId.value
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
        stopTranslationLoop()
        stopDetailedChunkPreview(pausePlayer = false)
        chunkSeekPrioritizeJob?.cancel()
        chunkSeekPrioritizeJob = null
        if (targetId != null) {
            analysisRepository.setMediaTranscriptionSessionOpen(targetId, false)
        }
    }

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.TogglePlayPause -> togglePlayPause()

            is PlayerEvent.SeekTo -> seekTo(event.positionMs)

            PlayerEvent.SeekBack -> {
                val seekBackMs = uiState.value.seekBackMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos - seekBackMs)
            }

            PlayerEvent.SeekForward -> {
                val seekForwardMs = uiState.value.seekForwardMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos + seekForwardMs)
            }

            PlayerEvent.PreviousChunk -> seekToAdjacentChunk(direction = -1)

            PlayerEvent.NextChunk -> seekToAdjacentChunk(direction = 1)

            is PlayerEvent.SeekToChunk -> seekToChunk(event.chunkId)

            is PlayerEvent.SetSpeed -> setSpeed(event.speed)

            is PlayerEvent.AdjustSpeed -> {
                val currentSpeed = playbackController.status.value.speed
                setSpeed(currentSpeed + event.delta)
            }

            is PlayerEvent.SetCustomSpeed -> setCustomSpeed(event.speed)

            is PlayerEvent.AdjustCustomSpeed -> setCustomSpeed(uiState.value.customSpeed + event.delta)

            is PlayerEvent.SetLoopMode -> {
                updatePracticeWithCheckedFallback {
                    it.copy(
                        loop = event.loopMode,
                        repeatChunkIds = if (event.loopMode is LoopMode.Off) emptySet() else it.repeatChunkIds,
                        repeatCheckedOnly = if (event.loopMode is LoopMode.Off) false else it.repeatCheckedOnly,
                        temporaryRepeatChunkId = if (event.loopMode is LoopMode.Off) null else it.temporaryRepeatChunkId,
                        repeatWholeFile = if (event.loopMode is LoopMode.Off) false else it.repeatWholeFile,
                    )
                }
            }

            PlayerEvent.EnableWholeFileRepeat -> {
                playbackController.updatePractice { current ->
                    current.copy(repeatWholeFile = true)
                }
            }

            PlayerEvent.ToggleSkipSilence -> {
                val next = !uiState.value.practice.skipOriginalSilence
                playbackController.updatePractice { current ->
                    current.copy(skipOriginalSilence = next)
                }
                viewModelScope.launch {
                    userSettingsRepository.setSkipOriginalSilence(next)
                }
            }

            PlayerEvent.ToggleRepeat -> {
                val chunks = uiState.value.chunks
                val posMs = playbackController.position.currentPositionMs()
                val activeChunkId = uiState.value.activeChunkIndex
                    ?.let { idx -> chunks.getOrNull(idx)?.id }
                    ?: chunks.firstOrNull { posMs >= it.startMs && posMs < it.endMs }?.id
                    ?: chunks.firstOrNull { posMs in it.startMs..it.endMs }?.id
                    ?: chunks.firstOrNull { !it.isSkipped }?.id
                    ?: chunks.firstOrNull()?.id
                updatePracticeWithCheckedFallback { current ->
                    if (current.repeatWholeFile) {
                        current.copy(repeatWholeFile = false)
                    } else {
                        val isCurrentlyOn = current.loop !is LoopMode.Off || current.repeatCheckedOnly
                        if (isCurrentlyOn) {
                            val tempId = current.temporaryRepeatChunkId
                            val nextRepeatIds = if (tempId != null) {
                                current.repeatChunkIds - tempId
                            } else {
                                current.repeatChunkIds
                            }
                            current.copy(
                                loop = LoopMode.Off,
                                repeatCheckedOnly = false,
                                repeatChunkIds = nextRepeatIds,
                                temporaryRepeatChunkId = null,
                            )
                        } else if (current.repeatChunkIds.isNotEmpty()) {
                            current.copy(
                                languageFilter = LanguageFilter.CHECKED_ONLY,
                                repeatCheckedOnly = true,
                                temporaryRepeatChunkId = null,
                            )
                        } else if (activeChunkId != null) {
                            current.copy(
                                loop = LoopMode.Off,
                                languageFilter = LanguageFilter.CHECKED_ONLY,
                                repeatChunkIds = setOf(activeChunkId),
                                repeatCheckedOnly = true,
                                temporaryRepeatChunkId = activeChunkId,
                            )
                        } else {
                            current.copy(
                                repeatCheckedOnly = true,
                                temporaryRepeatChunkId = null,
                            )
                        }
                    }
                }
            }

            is PlayerEvent.SetAutoPause -> {
                playbackController.updatePractice { current ->
                    val nextScope = if (event.autoPause is AutoPause.Off) {
                        AutoPauseScope.ALL_CHUNKS
                    } else {
                        current.autoPauseScope
                    }
                    current.copy(autoPause = event.autoPause, autoPauseScope = nextScope)
                }
            }

            is PlayerEvent.SetAutoPauseScope -> {
                viewModelScope.launch {
                    val defaultAutoPause = userSettingsRepository.settings.firstOrNull()?.defaultAutoPause ?: AutoPause.Off
                    playbackController.updatePractice { current ->
                        val nextAutoPause = if (
                            event.scope == AutoPauseScope.AFTER_BASE_LANGUAGE &&
                            current.autoPause is AutoPause.Off
                        ) {
                            if (defaultAutoPause !is AutoPause.Off) {
                                defaultAutoPause
                            } else {
                                AutoPause.Proportional(factor = 1.0f, minMs = 1_000L)
                            }
                        } else {
                            current.autoPause
                        }
                        current.copy(
                            autoPause = nextAutoPause,
                            autoPauseScope = event.scope,
                        )
                    }
                }
            }

            PlayerEvent.ToggleInstantComposition -> {
                viewModelScope.launch {
                    val defaultAutoPause = userSettingsRepository.settings.firstOrNull()?.defaultAutoPause ?: AutoPause.Off
                    playbackController.updatePractice { current ->
                        val turningOn = current.autoPauseScope != AutoPauseScope.AFTER_BASE_LANGUAGE
                        if (turningOn) {
                            val nextAutoPause = if (current.autoPause is AutoPause.Off) {
                                if (defaultAutoPause !is AutoPause.Off) {
                                    defaultAutoPause
                                } else {
                                    AutoPause.Proportional(factor = 1.0f, minMs = 1_000L)
                                }
                            } else {
                                current.autoPause
                            }
                            current.copy(
                                autoPause = nextAutoPause,
                                autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
                            )
                        } else {
                            current.copy(autoPauseScope = AutoPauseScope.ALL_CHUNKS)
                        }
                    }
                }
            }

            is PlayerEvent.SetLanguageFilter -> {
                if (event.filter == LanguageFilter.CHECKED_ONLY &&
                    playbackController.practice.value.repeatChunkIds.isEmpty()
                ) {
                    return
                }
                languageFilterHighlightJob?.cancel()
                languageFilterHighlightJob = null
                localUiState.update { state ->
                    if (state.isLanguageFilterFallbackHighlighted) {
                        state.copy(isLanguageFilterFallbackHighlighted = false)
                    } else {
                        state
                    }
                }
                playbackController.updatePractice { it.copy(languageFilter = event.filter) }
            }

            PlayerEvent.ToggleBookmarksOnly -> {
                playbackController.updatePractice { current ->
                    current.copy(bookmarksOnly = !current.bookmarksOnly)
                }
            }

            is PlayerEvent.ToggleBookmark -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    val added = analysisRepository.toggleChunkBookmark(id, event.chunkId)
                    if (!added) {
                        val remainingOtherBookmarks = uiState.value.chunks.any {
                            it.id != event.chunkId && it.isBookmarked
                        }
                        if (!remainingOtherBookmarks) {
                            playbackController.updatePractice { current ->
                                if (current.bookmarksOnly) current.copy(bookmarksOnly = false) else current
                            }
                        }
                    }
                }
            }

            is PlayerEvent.ToggleChunkRepeat -> {
                updatePracticeWithCheckedFallback { current ->
                    val isUnchecking = event.chunkId in current.repeatChunkIds
                    val nextIds = if (isUnchecking) {
                        current.repeatChunkIds - event.chunkId
                    } else {
                        current.repeatChunkIds + event.chunkId
                    }
                    val nextTemporaryId = when {
                        current.temporaryRepeatChunkId == null -> null
                        !isUnchecking && event.chunkId != current.temporaryRepeatChunkId -> null
                        isUnchecking && event.chunkId == current.temporaryRepeatChunkId -> null
                        else -> current.temporaryRepeatChunkId
                    }
                    val nextLoop = if (
                        nextIds.isNotEmpty() &&
                        (current.loop is LoopMode.SingleChunk || current.loop is LoopMode.ChunkRange)
                    ) {
                        LoopMode.Off
                    } else {
                        current.loop
                    }
                    current.copy(
                        loop = nextLoop,
                        repeatChunkIds = nextIds,
                        temporaryRepeatChunkId = nextTemporaryId,
                    )
                }
            }

            is PlayerEvent.SetChunkSelected -> {
                updatePracticeWithCheckedFallback { current ->
                    val nextIds = if (event.selected) {
                        current.repeatChunkIds + event.chunkId
                    } else {
                        current.repeatChunkIds - event.chunkId
                    }
                    if (nextIds == current.repeatChunkIds) {
                        current
                    } else {
                        val nextTemporaryId = when {
                            current.temporaryRepeatChunkId == null -> null
                            event.selected && event.chunkId != current.temporaryRepeatChunkId -> null
                            !event.selected && event.chunkId == current.temporaryRepeatChunkId -> null
                            else -> current.temporaryRepeatChunkId
                        }
                        val nextLoop = if (
                            nextIds.isNotEmpty() &&
                            (current.loop is LoopMode.SingleChunk || current.loop is LoopMode.ChunkRange)
                        ) {
                            LoopMode.Off
                        } else {
                            current.loop
                        }
                        current.copy(
                            loop = nextLoop,
                            repeatChunkIds = nextIds,
                            temporaryRepeatChunkId = nextTemporaryId,
                        )
                    }
                }
            }

            PlayerEvent.ClearChunkSelection -> {
                updatePracticeWithCheckedFallback { current ->
                    if (current.repeatChunkIds.isEmpty() && current.temporaryRepeatChunkId == null) {
                        current
                    } else {
                        current.copy(repeatChunkIds = emptySet(), temporaryRepeatChunkId = null)
                    }
                }
            }

            PlayerEvent.ClearWaveformRangeToCenter -> {
                val abRange = (playbackController.practice.value.loop as? LoopMode.AbRange)
                    ?.takeIf { it.startMs < it.endMs } ?: return
                val centerMs = (abRange.startMs + abRange.endMs) / 2L
                playbackController.updatePractice { current ->
                    if (current.loop is LoopMode.AbRange) {
                        current.copy(loop = LoopMode.Off)
                    } else {
                        current
                    }
                }
                seekTo(centerMs)
            }

            is PlayerEvent.DismissWaveformRangeAndSeek -> {
                playbackController.updatePractice { current ->
                    if (current.loop is LoopMode.AbRange) {
                        current.copy(loop = LoopMode.Off)
                    } else {
                        current
                    }
                }
                seekTo(event.positionMs)
            }

            PlayerEvent.AddChunkInSelectedRange -> {
                val id = resolveActiveMediaId() ?: return
                val abRange = (playbackController.practice.value.loop as? LoopMode.AbRange)
                    ?.takeIf { it.startMs < it.endMs } ?: return
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        val currentRepeatIds = playbackController.practice.value.repeatChunkIds
                        val selectedRanges = if (currentRepeatIds.isNotEmpty()) {
                            uiState.value.chunks
                                .filter { it.id in currentRepeatIds }
                                .map { it.startMs to it.endMs }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        val added = analysisRepository.addChunk(id, abRange.startMs, abRange.endMs)
                        if (added && currentRepeatIds.isNotEmpty()) {
                            val updatedChunks = analysisRepository.observeChunks(id).first()
                            val preservedIds = updatedChunks
                                .filter { (it.startMs to it.endMs) in selectedRanges }
                                .map { it.id }
                                .toSet()
                            updatePracticeWithCheckedFallback { current ->
                                current.copy(
                                    loop = if (current.loop is LoopMode.AbRange) LoopMode.Off else current.loop,
                                    repeatChunkIds = preservedIds,
                                    temporaryRepeatChunkId = current.temporaryRepeatChunkId?.takeIf { it in preservedIds },
                                )
                            }
                        } else {
                            playbackController.updatePractice { current ->
                                if (current.loop is LoopMode.AbRange) {
                                    current.copy(loop = LoopMode.Off)
                                } else {
                                    current
                                }
                            }
                        }
                    }
                }
            }

            PlayerEvent.MergeChunksInSelectedRange -> {
                val id = resolveActiveMediaId() ?: return
                val abRange = (playbackController.practice.value.loop as? LoopMode.AbRange)
                    ?.takeIf { it.startMs < it.endMs } ?: return
                val overlappingChunks = chunksOverlappingRange(uiState.value.chunks, abRange.startMs, abRange.endMs)
                if (overlappingChunks.size < 2) return
                val chunkIds = overlappingChunks.map { it.id }
                val firstChunkId = chunkIds.first()
                val hasFirstOverride = firstChunkId in localUiState.value.languageOverrides
                val firstOverrideLang = localUiState.value.languageOverrides[firstChunkId]
                chunkIds.forEach { cid ->
                    pendingLanguageCommitJobs.remove(cid)?.cancel()
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        val targetIdSet = chunkIds.toSet()
                        val currentRepeatIds = playbackController.practice.value.repeatChunkIds
                        val selectedRanges = if (currentRepeatIds.isNotEmpty()) {
                            uiState.value.chunks
                                .filter { it.id in currentRepeatIds && it.id !in targetIdSet }
                                .map { it.startMs to it.endMs }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        if (hasFirstOverride) {
                            analysisRepository.updateChunkLanguage(id, firstChunkId, firstOverrideLang)
                        }
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - targetIdSet)
                        }
                        val merged = analysisRepository.mergeChunks(id, chunkIds)
                        if (merged) {
                            val preservedIds = if (selectedRanges.isNotEmpty()) {
                                val updatedChunks = analysisRepository.observeChunks(id).first()
                                updatedChunks
                                    .filter { (it.startMs to it.endMs) in selectedRanges }
                                    .map { it.id }
                                    .toSet()
                            } else {
                                emptySet()
                            }
                            updatePracticeWithCheckedFallback { current ->
                                current.copy(
                                    loop = if (current.loop is LoopMode.AbRange) LoopMode.Off else current.loop,
                                    repeatChunkIds = preservedIds,
                                    temporaryRepeatChunkId = current.temporaryRepeatChunkId?.takeIf { it in preservedIds },
                                )
                            }
                        } else {
                            playbackController.updatePractice { current ->
                                if (current.loop is LoopMode.AbRange) {
                                    current.copy(loop = LoopMode.Off)
                                } else {
                                    current
                                }
                            }
                        }
                    }
                }
            }

            PlayerEvent.DeleteChunksInSelectedRange -> {
                val id = resolveActiveMediaId() ?: return
                val abRange = (playbackController.practice.value.loop as? LoopMode.AbRange)
                    ?.takeIf { it.startMs < it.endMs } ?: return
                val overlappingChunks = chunksOverlappingRange(uiState.value.chunks, abRange.startMs, abRange.endMs)
                if (overlappingChunks.isEmpty()) return
                val targetIds = overlappingChunks.mapTo(LinkedHashSet()) { it.id }
                targetIds.forEach { cid ->
                    pendingLanguageCommitJobs.remove(cid)?.cancel()
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        val currentRepeatIds = playbackController.practice.value.repeatChunkIds
                        val selectedRanges = if (currentRepeatIds.isNotEmpty()) {
                            uiState.value.chunks
                                .filter { it.id in currentRepeatIds && it.id !in targetIds }
                                .map { it.startMs to it.endMs }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - targetIds)
                        }
                        val deleted = analysisRepository.deleteChunks(id, targetIds)
                        val preservedIds = if (deleted && selectedRanges.isNotEmpty()) {
                            val updatedChunks = analysisRepository.observeChunks(id).first()
                            updatedChunks
                                .filter { (it.startMs to it.endMs) in selectedRanges }
                                .map { it.id }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        updatePracticeWithCheckedFallback { current ->
                            current.copy(
                                loop = if (current.loop is LoopMode.AbRange) LoopMode.Off else current.loop,
                                repeatChunkIds = if (deleted) preservedIds else current.repeatChunkIds,
                                temporaryRepeatChunkId = if (deleted) {
                                    current.temporaryRepeatChunkId?.takeIf { it in preservedIds }
                                } else {
                                    current.temporaryRepeatChunkId
                                },
                            )
                        }
                    }
                }
            }

            PlayerEvent.MergeSelectedChunks -> {
                val id = resolveActiveMediaId() ?: return
                val chunkIds = mergeableSelectedChunkIds(uiState.value.chunks)
                if (chunkIds.size < 2) return
                val firstChunkId = chunkIds.first()
                val hasFirstOverride = firstChunkId in localUiState.value.languageOverrides
                val firstOverrideLang = localUiState.value.languageOverrides[firstChunkId]
                chunkIds.forEach { cid ->
                    pendingLanguageCommitJobs.remove(cid)?.cancel()
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        if (hasFirstOverride) {
                            analysisRepository.updateChunkLanguage(id, firstChunkId, firstOverrideLang)
                        }
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - chunkIds.toSet())
                        }
                        val merged = analysisRepository.mergeChunks(id, chunkIds)
                        if (merged) {
                            // 連結するとチャンクの ID が変わるため、選択を外す
                            updatePracticeWithCheckedFallback {
                                it.copy(repeatChunkIds = emptySet(), temporaryRepeatChunkId = null)
                            }
                        }
                    }
                }
            }

            PlayerEvent.SplitChunkAtCurrentPosition -> {
                val id = resolveActiveMediaId() ?: return
                val positionMs = playbackController.position.currentPositionMs()
                val target = findSplitTargetChunk(uiState.value.chunks, positionMs) ?: return
                splitChunkAtPosition(id, target.id, positionMs)
            }

            is PlayerEvent.UpdateItemLanguages -> {
                val id = resolveActiveMediaId() ?: return
                val target = event.target.value.trim().lowercase(Locale.ROOT).ifBlank { return }
                val base = event.base?.value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != target }
                viewModelScope.launch {
                    val item = libraryRepository.observeItem(id).firstOrNull() ?: return@launch
                    val newPair = LanguagePair(target = LanguageTag(target), base = base?.let(::LanguageTag))
                    if (item.languagePair == newPair) return@launch
                    val targetChanged = item.languagePair.target.value.trim().lowercase(Locale.ROOT) != target
                    libraryRepository.updateItemMetadata(id = id, course = item.course, languages = newPair)
                    if (targetChanged) {
                        abortActiveViewModelTranscriptionJobs()
                        stopTranslationLoop()
                        failedTranslationChunkIds.clear()
                        libraryRepository.resetTranscriptsAndDeleteFiles(id)
                        analysisRepository.clearTranscriptsForMedia(id)
                        localUiState.update { state ->
                            state.copy(
                                languageOverrides = emptyMap(),
                                clearingChunkIds = emptySet(),
                                manuallyClearedChunkIds = emptySet(),
                                isTranscriptionProgressDismissed = false,
                                localTranscriptExported = false,
                                transcriptImportBanner = null,
                            )
                        }
                    }
                    analysisRepository.startLanguageDetectionOnly(id)
                }
            }

            is PlayerEvent.ToggleEnabledLanguage -> {
                val normalized = LanguageSettings.normalize(event.language)
                if (normalized.value.isBlank()) return
                val current = uiState.value.enabledLanguages
                    .map(LanguageSettings::normalize)
                    .toSet()
                val next = if (normalized in current) {
                    if (current.size <= 1) return
                    current - normalized
                } else {
                    current + normalized
                }
                viewModelScope.launch {
                    userSettingsRepository.setEnabledLanguages(next)
                }
            }

            is PlayerEvent.SetBlindMode -> {
                localUiState.update {
                    it.copy(
                        blindMode = event.blindMode,
                        revealedChunkIds = emptySet(),
                    )
                }
            }

            is PlayerEvent.ToggleRevealChunk -> {
                localUiState.update { current ->
                    val nextRevealed = if (event.chunkId in current.revealedChunkIds) {
                        current.revealedChunkIds - event.chunkId
                    } else {
                        current.revealedChunkIds + event.chunkId
                    }
                    current.copy(revealedChunkIds = nextRevealed)
                }
            }

            is PlayerEvent.SetShowTargetRomanization -> {
                viewModelScope.launch {
                    userSettingsRepository.setShowTargetRomanization(event.enabled)
                }
            }

            is PlayerEvent.SetShowTargetTranslation -> {
                if (!event.enabled) {
                    stopTranslationLoop()
                    localUiState.update {
                        it.copy(
                            showTargetTranslation = false,
                            translatingChunkId = null,
                        )
                    }
                } else {
                    failedTranslationChunkIds.clear()
                    localUiState.update {
                        it.copy(
                            showTargetTranslation = true,
                            failedTranslationChunkIds = emptySet(),
                        )
                    }
                    startTranslationLoopIfNeeded()
                }
            }

            is PlayerEvent.SetChunksSkipped -> {
                val id = resolveActiveMediaId() ?: return
                if (event.chunkIds.isEmpty()) return
                viewModelScope.launch {
                    analysisRepository.setChunksSkipped(id, event.chunkIds, event.skipped)
                }
            }

            is PlayerEvent.SetChunkSource -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.setChunkSource(id, event.origin)
                }
            }

            is PlayerEvent.Resegment -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update {
                    it.copy(
                        useGlobalSegmentationOverride = false,
                        customSegmentationParams = event.params,
                    )
                }
                viewModelScope.launch {
                    analysisRepository.setCustomSegmentationParams(id, event.params)
                    analysisRepository.resegment(id, event.params)
                }
            }

            is PlayerEvent.SetUseGlobalSegmentation -> {
                val id = resolveActiveMediaId() ?: return
                if (event.useGlobal) {
                    localUiState.update {
                        it.copy(
                            useGlobalSegmentationOverride = true,
                            customSegmentationParams = null,
                        )
                    }
                    viewModelScope.launch {
                        val defaultParams = userSettingsRepository.settings.firstOrNull()?.defaultSegmentationParams
                            ?: SegmentationParams()
                        analysisRepository.setCustomSegmentationParams(id, null)
                        analysisRepository.resegment(id, defaultParams)
                    }
                } else {
                    val currentParams = localUiState.value.customSegmentationParams ?: uiState.value.segmentationParams
                    localUiState.update {
                        it.copy(
                            useGlobalSegmentationOverride = false,
                            customSegmentationParams = currentParams,
                        )
                    }
                    viewModelScope.launch {
                        analysisRepository.setCustomSegmentationParams(id, currentParams)
                    }
                }
            }

            is PlayerEvent.ResetChunkSegmentation -> {
                val id = resolveActiveMediaId() ?: return
                abortActiveViewModelTranscriptionJobs()
                val customParams = event.params
                localUiState.update { state ->
                    state.copy(
                        useGlobalSegmentationOverride = if (customParams != null) false else state.useGlobalSegmentationOverride,
                        customSegmentationParams = customParams ?: state.customSegmentationParams,
                        languageOverrides = emptyMap(),
                        clearingChunkIds = emptySet(),
                        manuallyClearedChunkIds = emptySet(),
                        isTranscriptionProgressDismissed = false,
                        localTranscriptExported = false,
                        transcriptImportBanner = null,
                    )
                }
                viewModelScope.launch {
                    val itemState = libraryRepository.observeItemState(id).firstOrNull()
                    val settings = userSettingsRepository.settings.firstOrNull() ?: UserSettings()
                    val effectiveParams = if (customParams != null) {
                        analysisRepository.setCustomSegmentationParams(id, customParams)
                        customParams
                    } else {
                        val useGlobal = localUiState.value.useGlobalSegmentationOverride
                            ?: (itemState?.customSegmentationParams == null)
                        if (useGlobal) {
                            analysisRepository.setCustomSegmentationParams(id, null)
                            settings.defaultSegmentationParams
                        } else {
                            localUiState.value.customSegmentationParams
                                ?: itemState?.customSegmentationParams
                                ?: settings.defaultSegmentationParams
                        }
                    }
                    analysisRepository.clearTranscriptsForMedia(id)
                    analysisRepository.setChunkSource(id, ChunkOrigin.AUTO)
                    analysisRepository.resegment(id, effectiveParams)
                }
            }

            is PlayerEvent.UpdateChunkLanguage -> {
                val id = resolveActiveMediaId() ?: return
                pendingLanguageCommitJobs.remove(event.chunkId)?.cancel()
                val job = viewModelScope.launch {
                    delay(LANGUAGE_TOGGLE_COMMIT_DELAY_MS)
                    pendingLanguageCommitJobs.remove(event.chunkId)
                    commitChunkLanguage(id, event.chunkId, event.language)
                }
                pendingLanguageCommitJobs[event.chunkId] = job
                // 言語バッジの表示はすぐに切り替え、文字起こしのやり直しは最後のトグルから 1 秒後にまとめて行う（誤タップ対策）
                localUiState.update { state ->
                    state.copy(languageOverrides = state.languageOverrides + (event.chunkId to event.language))
                }
            }

            is PlayerEvent.RetranscribeChunk -> {
                val id = resolveActiveMediaId() ?: return
                chunkSeekPrioritizeJob?.cancel()
                chunkSeekPrioritizeJob = null
                localUiState.update { state ->
                    state.copy(
                        clearingChunkIds = state.clearingChunkIds + event.chunkId,
                        manuallyClearedChunkIds = state.manuallyClearedChunkIds - event.chunkId,
                        isTranscriptionProgressDismissed = false,
                    )
                }
                viewModelScope.launch {
                    try {
                        analysisRepository.retranscribeChunk(id, event.chunkId)
                        withContext(NonCancellable) {
                            val hasTranscript =
                                analysisRepository.observeTranscripts(id).firstOrNull()?.containsKey(event.chunkId) == true
                            if (hasTranscript) {
                                localUiState.update { state ->
                                    state.copy(languageOverrides = state.languageOverrides - event.chunkId)
                                }
                            }
                            val exported = libraryRepository.exportLocalTranscriptJson(id)
                            if (exported) {
                                localUiState.update { it.copy(localTranscriptExported = true) }
                            }
                        }
                        val lookaheadOn = perMediaLookaheadEnabled.value[id] ?: uiState.value.lookaheadStatus.enabled
                        if (lastOpenedMediaId == id && lookaheadOn) {
                            analysisRepository.triggerLookaheadAt(id, event.chunkId)
                        }
                    } finally {
                        localUiState.update { state ->
                            state.copy(clearingChunkIds = state.clearingChunkIds - event.chunkId)
                        }
                    }
                }
            }

            is PlayerEvent.UpdateChunkText -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { state ->
                    val nextCleared = if (event.text.isBlank()) {
                        state.manuallyClearedChunkIds + event.chunkId
                    } else {
                        state.manuallyClearedChunkIds - event.chunkId
                    }
                    state.copy(manuallyClearedChunkIds = nextCleared)
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        analysisRepository.updateChunkText(id, event.chunkId, event.text)
                        val exported = libraryRepository.exportLocalTranscriptJson(id)
                        if (exported) {
                            localUiState.update { it.copy(localTranscriptExported = true) }
                        }
                    }
                }
            }

            is PlayerEvent.SplitChunk -> {
                val id = resolveActiveMediaId() ?: return
                splitChunkAtPosition(id, event.chunkId, event.splitPositionMs)
            }

            is PlayerEvent.MergeWithNextChunk -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.mergeWithNextChunk(id, event.chunkId)
                }
            }

            is PlayerEvent.MoveChunkBoundary -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.moveChunkBoundary(
                        id = id,
                        chunkId = event.chunkId,
                        newStartMs = event.newStartMs,
                        newEndMs = event.newEndMs,
                    )
                }
            }

            is PlayerEvent.DeleteChunk -> {
                val id = resolveActiveMediaId() ?: return
                pendingLanguageCommitJobs.remove(event.chunkId)?.cancel()
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        val currentRepeatIds = playbackController.practice.value.repeatChunkIds
                        val selectedRanges = if (currentRepeatIds.isNotEmpty()) {
                            uiState.value.chunks
                                .filter { it.id in currentRepeatIds && it.id != event.chunkId }
                                .map { it.startMs to it.endMs }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - event.chunkId)
                        }
                        val deleted = analysisRepository.deleteChunk(id, event.chunkId)
                        if (deleted && currentRepeatIds.isNotEmpty()) {
                            val updatedChunks = analysisRepository.observeChunks(id).first()
                            val preservedIds = updatedChunks
                                .filter { (it.startMs to it.endMs) in selectedRanges }
                                .map { it.id }
                                .toSet()
                            updatePracticeWithCheckedFallback { current ->
                                current.copy(
                                    repeatChunkIds = preservedIds,
                                    temporaryRepeatChunkId = current.temporaryRepeatChunkId?.takeIf { it in preservedIds },
                                )
                            }
                        }
                    }
                }
            }

            is PlayerEvent.BulkSetUnknownTranscriptLanguage -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { state ->
                    val updatedOverrides = state.languageOverrides.mapValues { (_, lang) ->
                        if (lang == null || lang.value.isBlank() || lang.value.equals("und", ignoreCase = true)) {
                            event.language
                        } else {
                            lang
                        }
                    }
                    state.copy(languageOverrides = updatedOverrides)
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        analysisRepository.bulkSetUnknownTranscriptLanguage(id, event.language)
                        val exported = libraryRepository.exportLocalTranscriptJson(id)
                        if (exported) {
                            localUiState.update { it.copy(localTranscriptExported = true) }
                        }
                    }
                }
            }

            is PlayerEvent.SetTranscriptOffset -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { it.copy(transcriptOffsetMs = event.offsetMs) }
                viewModelScope.launch {
                    analysisRepository.setTranscriptOffset(id, event.offsetMs)
                }
            }

            is PlayerEvent.AdjustTranscriptOffset -> {
                val id = resolveActiveMediaId() ?: return
                val nextOffset = localUiState.value.transcriptOffsetMs + event.deltaMs
                localUiState.update { it.copy(transcriptOffsetMs = nextOffset) }
                viewModelScope.launch {
                    analysisRepository.setTranscriptOffset(id, nextOffset)
                }
            }

            PlayerEvent.AutoAlignTranscriptOffset -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    val aligned = analysisRepository.autoAlignTranscriptOffset(id)
                    if (aligned != null) {
                        localUiState.update { it.copy(transcriptOffsetMs = aligned) }
                    }
                }
            }

            is PlayerEvent.SetLookaheadEnabled -> {
                val id = resolveActiveMediaId() ?: return
                if (!event.enabled) {
                    abortActiveViewModelTranscriptionJobs()
                }
                localUiState.update {
                    it.copy(
                        isLookaheadEnabled = event.enabled,
                        isTranscriptionProgressDismissed = false,
                    )
                }
                perMediaLookaheadEnabled.update { it + (id to event.enabled) }
                syncLookaheadState(id, event.enabled)
            }

            is PlayerEvent.SetBackgroundTranscriptionEnabled -> {
                val mediaId = resolveActiveMediaId() ?: return
                if (!event.enabled) {
                    localUiState.update { it.copy(backgroundSpeechRecognizerWarning = null) }
                }
                viewModelScope.launch {
                    analysisRepository.setBackgroundTranscriptionEnabled(mediaId, event.enabled)
                    if (event.enabled) {
                        val item = libraryRepository.observeItem(mediaId).firstOrNull()
                        val pair = uiState.value.languagePair ?: item?.languagePair
                        val targetAndBaseLanguages = buildList {
                            pair?.target?.takeIf { isValidLanguageTag(it) }?.let { add(it) }
                            pair?.base?.takeIf { isValidLanguageTag(it) }?.let { baseTag ->
                                if (none { matchesLanguageRole(it, baseTag) }) {
                                    add(baseTag)
                                }
                            }
                        }
                        if (targetAndBaseLanguages.isNotEmpty()) {
                            val itemOverride = itemSttOverrideRepository.get(mediaId)
                                .takeIf { it != com.miuraken.chunkflow.core.model.ItemSttOverride.NONE }
                                ?: uiState.value.itemSttOverride
                            val settings = userSettingsRepository.settings.firstOrNull() ?: UserSettings()
                            val queryLanguages = (targetAndBaseLanguages + buildDetailedQueryLanguages(pair, null)).distinct()
                            val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                            val infoMap = queried.associateBy { it.language }
                            val speechRecognizerLanguages = mutableListOf<LanguageTag>()
                            val sherpaSupportedLanguages = mutableListOf<LanguageTag>()
                            for (lang in targetAndBaseLanguages) {
                                val normLang = LanguageSettings.normalize(lang)
                                val mgmtLang = LanguageTag(
                                    SupportedLanguageCatalog.normalizeToModelManagementCode(lang.value),
                                )
                                val rawInfo = listOfNotNull(
                                    infoMap[lang],
                                    infoMap[normLang],
                                    infoMap[mgmtLang],
                                ).firstOrNull { candidate ->
                                    candidate != OnDeviceLanguageModelInfo(language = candidate.language)
                                } ?: infoMap[lang] ?: OnDeviceLanguageModelInfo(language = lang)
                                val info = rawInfo.copy(language = lang)
                                val overrideEngine = when {
                                    pair != null && matchesLanguageRole(lang, pair.target) -> itemOverride.targetEngine
                                    pair?.base != null && matchesLanguageRole(lang, pair.base) -> itemOverride.baseEngine
                                    else -> null
                                }
                                val userPreferred = settings.sttEngineByLanguage[lang]
                                    ?: settings.sttEngineByLanguage[normLang]
                                    ?: settings.sttEngineByLanguage[mgmtLang]
                                val effective = info.effectiveEngine(
                                    override = overrideEngine,
                                    userPreferred = userPreferred,
                                )
                                if (effective == SttEngine.SPEECH_RECOGNIZER) {
                                    speechRecognizerLanguages += lang
                                    val enriched = DetailedTranscriptionDialogState.enrichWithCatalog(info, lang)
                                    if (enriched.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED) {
                                        sherpaSupportedLanguages += lang
                                    }
                                }
                            }
                            localUiState.update { state ->
                                state.copy(
                                    languageModelInfos = state.languageModelInfos + infoMap,
                                    backgroundSpeechRecognizerWarning = if (speechRecognizerLanguages.isNotEmpty()) {
                                        BackgroundSpeechRecognizerWarningState(
                                            speechRecognizerLanguages = speechRecognizerLanguages,
                                            sherpaSupportedLanguages = sherpaSupportedLanguages,
                                        )
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            PlayerEvent.DismissBackgroundSpeechRecognizerWarning -> {
                localUiState.update { it.copy(backgroundSpeechRecognizerWarning = null) }
            }

            PlayerEvent.ToggleTranscriptionProgress -> {
                localUiState.update { state ->
                    state.copy(isTranscriptionProgressDismissed = !state.isTranscriptionProgressDismissed)
                }
            }

            PlayerEvent.ResetTranscription -> {
                val id = resolveActiveMediaId() ?: return
                abortActiveViewModelTranscriptionJobs()
                localUiState.update {
                    it.copy(
                        isLookaheadEnabled = false,
                        isTranscriptionProgressDismissed = false,
                    )
                }
                perMediaLookaheadEnabled.update { it + (id to false) }
                viewModelScope.launch {
                    analysisRepository.setLookaheadEnabled(id, false)
                    analysisRepository.resetTranscription(id)
                }
            }

            is PlayerEvent.RequestRecognitionModelDownload -> {
                viewModelScope.launch {
                    analysisRepository.requestRecognitionModelDownload(event.language)
                }
            }

            is PlayerEvent.TriggerLookahead -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.triggerLookaheadAt(id, event.chunkId)
                }
            }

            PlayerEvent.ExportLocalTranscriptJson -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    val exported = libraryRepository.exportLocalTranscriptJson(id)
                    localUiState.update { it.copy(localTranscriptExported = exported) }
                }
            }

            is PlayerEvent.ExportTranscriptToUri -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    val success = libraryRepository.writeExportedTranscriptToUri(
                        id = id,
                        format = event.format,
                        targetUri = event.uri,
                    )
                    val banner = if (success) {
                        TranscriptExportBannerState.Exported(event.format)
                    } else {
                        TranscriptExportBannerState.Failed
                    }
                    localUiState.update { it.copy(transcriptExportBanner = banner) }
                }
            }

            PlayerEvent.DismissExportBanner -> {
                localUiState.update { it.copy(transcriptExportBanner = null) }
            }

            is PlayerEvent.ImportTranscript -> {
                val activeId = resolveActiveMediaId()
                viewModelScope.launch {
                    val result = if (activeId != null) {
                        libraryRepository.importTranscriptForMedia(activeId, event.uri)
                    } else {
                        libraryRepository.importTranscript(event.uri)
                    }
                    if (result is TranscriptImportResult.Attached && activeId != null) {
                        analysisRepository.setChunkSource(activeId, ChunkOrigin.IMPORTED)
                        localUiState.update { state ->
                            state.copy(languageOverrides = emptyMap())
                        }
                    }
                    val banner = when (result) {
                        is TranscriptImportResult.Attached ->
                            TranscriptImportBannerState.Attached(
                                chunkCount = result.chunkCount,
                                durationMismatch = result.durationMismatch,
                            )

                        is TranscriptImportResult.NoMatchingAudio ->
                            TranscriptImportBannerState.NoMatchingAudio(result.audioFileName)

                        is TranscriptImportResult.Invalid ->
                            TranscriptImportBannerState.Invalid(result.error)
                    }
                    localUiState.update { it.copy(transcriptImportBanner = banner) }
                }
            }

            PlayerEvent.ResetTranscripts -> {
                val id = resolveActiveMediaId() ?: return
                abortActiveViewModelTranscriptionJobs()
                viewModelScope.launch {
                    val itemState = libraryRepository.observeItemState(id).firstOrNull()
                    val settings = userSettingsRepository.settings.firstOrNull() ?: UserSettings()
                    val useGlobal = localUiState.value.useGlobalSegmentationOverride
                        ?: (itemState?.customSegmentationParams == null)
                    val effectiveParams = if (useGlobal) {
                        settings.defaultSegmentationParams
                    } else {
                        localUiState.value.customSegmentationParams
                            ?: itemState?.customSegmentationParams
                            ?: settings.defaultSegmentationParams
                    }
                    val success = libraryRepository.resetTranscriptsAndDeleteFiles(id)
                    analysisRepository.clearTranscriptsForMedia(id)
                    analysisRepository.setChunkSource(id, ChunkOrigin.AUTO)
                    analysisRepository.resegment(id, effectiveParams)
                    localUiState.update { state ->
                        state.copy(
                            languageOverrides = emptyMap(),
                            clearingChunkIds = emptySet(),
                            manuallyClearedChunkIds = emptySet(),
                            isTranscriptionProgressDismissed = false,
                            localTranscriptExported = false,
                            transcriptImportBanner = null,
                            transcriptExportBanner = if (success) {
                                TranscriptExportBannerState.ResetCompleted
                            } else {
                                TranscriptExportBannerState.Failed
                            },
                        )
                    }
                }
            }

            PlayerEvent.DismissTranscriptBanner -> {
                localUiState.update { it.copy(transcriptImportBanner = null) }
            }

            is PlayerEvent.OpenDetailedTranscription -> {
                detailedTrialJob?.cancel()
                detailedTrialJob = null
                stopDetailedChunkPreview(pausePlayer = true)
                val chunks = uiState.value.chunks
                val idx = chunks.indexOfFirst { it.id == event.chunkId }.coerceAtLeast(0)
                val targetChunk = chunks.getOrNull(idx)
                val initialText = targetChunk?.resolveDisplayText(
                    uiState.value.chineseScript,
                    uiState.value.cantoneseScript,
                ) ?: targetChunk?.text.orEmpty()
                val pair = uiState.value.languagePair
                val initialLanguage = resolveInitialDetailedLanguage(targetChunk?.language, pair)
                val fallbackDialog = DetailedTranscriptionDialogState(
                    chunkId = event.chunkId,
                    chunkIndex = idx,
                    languageOverride = initialLanguage,
                    audioSpeed = 1.0f,
                    isRunning = false,
                    hasAttempted = false,
                    resultText = initialText,
                    trialEmptyMessage = null,
                    recognizedLanguage = initialLanguage,
                    languageModelInfos = localUiState.value.languageModelInfos,
                    hasRecordAudioPermission = localUiState.value.hasRecordAudioPermission,
                ).let { d ->
                    d.copy(engine = d.selectDefaultEngineForLanguage(initialLanguage))
                }
                localUiState.update { state ->
                    state.copy(detailedTranscriptionDialog = fallbackDialog)
                }
                val queryLanguages = buildDetailedQueryLanguages(pair, initialLanguage)
                viewModelScope.launch {
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val infoMap = queried.associateBy { it.language }
                    localUiState.update { state ->
                        val mergedInfos = state.languageModelInfos + infoMap
                        val current = state.detailedTranscriptionDialog
                        if (current == null || current.chunkId != event.chunkId) {
                            state.copy(languageModelInfos = mergedInfos)
                        } else {
                            val withInfos = current.copy(languageModelInfos = mergedInfos)
                            val lang = withInfos.languageOverride ?: initialLanguage
                            state.copy(
                                languageModelInfos = mergedInfos,
                                detailedTranscriptionDialog = withInfos.copy(
                                    engine = withInfos.selectDefaultEngineForLanguage(lang),
                                ),
                            )
                        }
                    }
                }
            }

            is PlayerEvent.SelectDetailedTranscriptionLanguage -> {
                localUiState.update { state ->
                    val current = state.detailedTranscriptionDialog ?: return@update state
                    val nextEngine = current.selectDefaultEngineForLanguage(event.language)
                    state.copy(
                        detailedTranscriptionDialog = current.copy(
                            languageOverride = event.language,
                            recognizedLanguage = event.language,
                            engine = nextEngine,
                            trialEmptyMessage = null,
                        ),
                    )
                }
            }

            is PlayerEvent.SelectDetailedTranscriptionEngine -> {
                localUiState.update { state ->
                    val current = state.detailedTranscriptionDialog ?: return@update state
                    if (!current.isEngineEnabled(current.languageOverride, event.engine)) {
                        return@update state
                    }
                    state.copy(
                        detailedTranscriptionDialog = current.copy(
                            engine = event.engine,
                            trialEmptyMessage = null,
                        ),
                    )
                }
            }

            is PlayerEvent.SetRecordAudioPermissionGranted -> {
                localUiState.update { state ->
                    state.copy(
                        hasRecordAudioPermission = event.granted,
                        detailedTranscriptionDialog = state.detailedTranscriptionDialog?.copy(
                            hasRecordAudioPermission = event.granted,
                        ),
                    )
                }
            }

            is PlayerEvent.RequestDetailedTranscriptionModelDownload -> {
                val sttEngine = DetailedTranscriptionDialogState.toSttEngine(event.engine)
                localUiState.update { state ->
                    val current = state.detailedTranscriptionDialog ?: return@update state
                    val existingInfo = current.modelInfoFor(event.language)
                    val downloadingInfo = updateModelInfoStatus(
                        info = existingInfo,
                        engine = sttEngine,
                        status = OnDeviceLanguageModelStatus.DOWNLOADING,
                    )
                    val normLang = LanguageSettings.normalize(event.language)
                    val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(event.language.value))
                    val nextMap = current.languageModelInfos +
                        (event.language to downloadingInfo) +
                        (normLang to downloadingInfo.copy(language = normLang)) +
                        (mgmtLang to downloadingInfo.copy(language = mgmtLang))
                    state.copy(
                        languageModelInfos = state.languageModelInfos + nextMap,
                        detailedTranscriptionDialog = current.copy(
                            languageOverride = event.language,
                            engine = event.engine,
                            languageModelInfos = nextMap,
                        ),
                    )
                }
                viewModelScope.launch {
                    val ok = analysisRepository.requestSttModelDownload(event.language, sttEngine)
                    val currentDialog = localUiState.value.detailedTranscriptionDialog ?: return@launch
                    val queryLanguages = buildDetailedQueryLanguages(uiState.value.languagePair, event.language)
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val queriedMap = queried.associateBy { it.language }.toMutableMap()
                    if (!ok) {
                        val existing = queriedMap[event.language] ?: OnDeviceLanguageModelInfo(language = event.language)
                        val failedInfo = updateModelInfoStatus(
                            info = existing,
                            engine = sttEngine,
                            status = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED,
                        )
                        val normLang = LanguageSettings.normalize(event.language)
                        val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(event.language.value))
                        queriedMap[event.language] = failedInfo
                        queriedMap[normLang] = failedInfo.copy(language = normLang)
                        queriedMap[mgmtLang] = failedInfo.copy(language = mgmtLang)
                    }
                    localUiState.update { state ->
                        val mergedInfos = state.languageModelInfos + queriedMap
                        val dialog = state.detailedTranscriptionDialog
                        if (dialog == null || dialog.chunkId != currentDialog.chunkId) {
                            state.copy(languageModelInfos = mergedInfos)
                        } else {
                            state.copy(
                                languageModelInfos = mergedInfos,
                                detailedTranscriptionDialog = dialog.copy(
                                    languageModelInfos = dialog.languageModelInfos + queriedMap,
                                ),
                            )
                        }
                    }
                }
            }

            is PlayerEvent.PlayDetailedTranscriptionChunkPreview -> {
                startDetailedChunkPreview(event.chunkId, event.speed)
            }

            PlayerEvent.StopDetailedTranscriptionChunkPreview -> {
                stopDetailedChunkPreview(pausePlayer = true)
            }

            is PlayerEvent.SetTargetEngineOverride -> {
                val mediaId = uiState.value.mediaId ?: return
                viewModelScope.launch {
                    val requestedEngine = event.engine
                    if (requestedEngine != null) {
                        val targetLang = uiState.value.languagePair?.target
                            ?: libraryRepository.observeItem(mediaId).firstOrNull()?.languagePair?.target
                        if (targetLang != null && !isEngineOverrideSelectableForLanguage(targetLang, requestedEngine)) {
                            return@launch
                        }
                    }
                    val current = itemSttOverrideRepository.get(mediaId)
                    itemSttOverrideRepository.set(mediaId, current.copy(targetEngine = requestedEngine))
                }
            }

            is PlayerEvent.SetBaseEngineOverride -> {
                val mediaId = uiState.value.mediaId ?: return
                viewModelScope.launch {
                    val requestedEngine = event.engine
                    if (requestedEngine != null) {
                        val baseLang = uiState.value.languagePair?.base
                            ?: libraryRepository.observeItem(mediaId).firstOrNull()?.languagePair?.base
                        if (baseLang != null && !isEngineOverrideSelectableForLanguage(baseLang, requestedEngine)) {
                            return@launch
                        }
                    }
                    val current = itemSttOverrideRepository.get(mediaId)
                    itemSttOverrideRepository.set(mediaId, current.copy(baseEngine = requestedEngine))
                }
            }

            PlayerEvent.DismissDetailedTranscription -> {
                detailedTrialJob?.cancel()
                detailedTrialJob = null
                stopDetailedChunkPreview(pausePlayer = true)
                localUiState.update { it.copy(detailedTranscriptionDialog = null) }
            }

            is PlayerEvent.RunDetailedTranscriptionTrial -> {
                val id = resolveActiveMediaId() ?: return
                detailedTrialJob?.cancel()
                val resolvedLanguage = event.languageOverride
                    ?: localUiState.value.detailedTranscriptionDialog?.languageOverride
                    ?: resolveInitialDetailedLanguage(null, uiState.value.languagePair)
                localUiState.update { state ->
                    val currentDialog = state.detailedTranscriptionDialog ?: return@update state
                    state.copy(
                        hasRecordAudioPermission = event.hasRecordAudioPermission,
                        detailedTranscriptionDialog = currentDialog.copy(
                            engine = event.engine,
                            languageOverride = resolvedLanguage,
                            audioSpeed = event.audioSpeed,
                            isRunning = true,
                            trialEmptyMessage = null,
                            hasRecordAudioPermission = event.hasRecordAudioPermission,
                        ),
                    )
                }
                detailedTrialJob = viewModelScope.launch {
                    val trialResult = analysisRepository.tryTranscribeChunkCustom(
                        id = id,
                        chunkId = event.chunkId,
                        engine = event.engine,
                        languageOverride = resolvedLanguage,
                        audioSpeed = event.audioSpeed,
                    )
                    localUiState.update { state ->
                        val currentDialog = state.detailedTranscriptionDialog
                        if (currentDialog == null || currentDialog.chunkId != event.chunkId) {
                            state
                        } else {
                            val resultText = trialResult?.text.orEmpty()
                            val emptyMessage = if (resultText.isBlank()) {
                                val modelStatus = currentDialog.engineStatus(resolvedLanguage, event.engine)
                                resolveDetailedTrialEmptyMessage(
                                    targetLanguage = resolvedLanguage,
                                    engine = event.engine,
                                    modelStatus = modelStatus,
                                    hasRecordAudioPermission = event.hasRecordAudioPermission,
                                )
                            } else {
                                null
                            }
                            state.copy(
                                detailedTranscriptionDialog = currentDialog.copy(
                                    isRunning = false,
                                    hasAttempted = true,
                                    resultText = resultText,
                                    trialEmptyMessage = emptyMessage,
                                    recognizedLanguage = trialResult?.language ?: resolvedLanguage,
                                    recognizedEngineId = trialResult?.engineId?.value,
                                ),
                            )
                        }
                    }
                }
            }

            is PlayerEvent.ApplyDetailedTranscriptionResult -> {
                val id = resolveActiveMediaId() ?: return
                detailedTrialJob?.cancel()
                detailedTrialJob = null
                stopDetailedChunkPreview(pausePlayer = true)
                localUiState.update { state ->
                    val nextOverrides = if (event.language != null) {
                        state.languageOverrides + (event.chunkId to event.language)
                    } else {
                        state.languageOverrides
                    }
                    state.copy(
                        detailedTranscriptionDialog = null,
                        languageOverrides = nextOverrides,
                    )
                }
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        analysisRepository.applyChunkTranscriptionTrial(
                            id = id,
                            chunkId = event.chunkId,
                            text = event.text,
                            language = event.language,
                        )
                        val hasTranscript =
                            analysisRepository.observeTranscripts(id).firstOrNull()?.containsKey(event.chunkId) == true
                        if (hasTranscript) {
                            localUiState.update { state ->
                                state.copy(languageOverrides = state.languageOverrides - event.chunkId)
                            }
                        }
                        val exported = libraryRepository.exportLocalTranscriptJson(id)
                        if (exported) {
                            localUiState.update { it.copy(localTranscriptExported = true) }
                        }
                    }
                }
            }

            is PlayerEvent.SetSeekBackMs -> {
                viewModelScope.launch {
                    userSettingsRepository.setSeekBackMs(event.durationMs)
                }
            }

            is PlayerEvent.SetSeekForwardMs -> {
                viewModelScope.launch {
                    userSettingsRepository.setSeekForwardMs(event.durationMs)
                }
            }

            PlayerEvent.SeekToFileStart -> seekToFileStart()

            PlayerEvent.SeekToPreviousCheckedChunk -> seekToCheckedChunk(direction = -1)

            PlayerEvent.SeekToNextCheckedChunk -> seekToCheckedChunk(direction = 1)

            PlayerEvent.OpenNextMediaItem -> {
                val nextId = uiState.value.nextMediaId ?: return
                open(nextId)
            }

            PlayerEvent.Back -> close()
        }
    }

    override fun onCleared() {
        close()
    }

    private fun buildDetailedQueryLanguages(
        languagePair: LanguagePair?,
        extraLanguage: LanguageTag?,
    ): List<LanguageTag> {
        val raw = listOfNotNull(
            languagePair?.target,
            languagePair?.base,
            extraLanguage,
        )
        val normalized = raw.flatMap { tag ->
            listOf(
                tag,
                LanguageSettings.normalize(tag),
                LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(tag.value)),
            )
        }
        return normalized.distinct()
    }

    private fun updateModelInfoStatus(
        info: OnDeviceLanguageModelInfo,
        engine: SttEngine,
        status: OnDeviceLanguageModelStatus,
    ): OnDeviceLanguageModelInfo = when (engine) {
        SttEngine.ML_KIT -> info.copy(mlKitStatus = status)
        SttEngine.SPEECH_RECOGNIZER -> info.copy(status = status)
        SttEngine.SHERPA_ONNX -> info.copy(sherpaModelStatus = status)
    }

    private suspend fun isEngineOverrideSelectableForLanguage(
        language: LanguageTag,
        engine: SttEngine,
    ): Boolean {
        val normLang = LanguageSettings.normalize(language)
        val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(language.value))
        var infoMap = localUiState.value.languageModelInfos
        var rawInfo = listOfNotNull(
            infoMap[language],
            infoMap[normLang],
            infoMap[mgmtLang],
        ).firstOrNull { candidate ->
            candidate != OnDeviceLanguageModelInfo(language = candidate.language)
        }
        if (rawInfo == null) {
            val queryLanguages = buildDetailedQueryLanguages(uiState.value.languagePair, language)
            val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
            val queriedMap = queried.associateBy { it.language }
            localUiState.update { state ->
                state.copy(languageModelInfos = state.languageModelInfos + queriedMap)
            }
            infoMap = localUiState.value.languageModelInfos
            rawInfo = listOfNotNull(
                infoMap[language],
                infoMap[normLang],
                infoMap[mgmtLang],
            ).firstOrNull { candidate ->
                candidate != OnDeviceLanguageModelInfo(language = candidate.language)
            } ?: infoMap[language]
        }
        val enriched = (rawInfo ?: OnDeviceLanguageModelInfo(language = language)).enrichedWithCatalog(language)
        return when (engine) {
            SttEngine.ML_KIT ->
                enriched.mlKitStatus != OnDeviceLanguageModelStatus.UNSUPPORTED &&
                    enriched.mlKitStatus != OnDeviceLanguageModelStatus.CHECKING

            SttEngine.SPEECH_RECOGNIZER ->
                enriched.status != OnDeviceLanguageModelStatus.UNSUPPORTED

            SttEngine.SHERPA_ONNX ->
                enriched.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED
        }
    }

    private fun startDetailedChunkPreview(chunkId: ChunkId, speed: Float) {
        detailedPreviewJob?.cancel()
        detailedPreviewJob = null
        if (savedSpeedBeforeDetailedPreview == null) {
            savedSpeedBeforeDetailedPreview = playbackController.status.value.speed
        }
        val clampedSpeed = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        playbackController.setSpeed(clampedSpeed)
        playbackController.player.value?.setPlaybackSpeed(clampedSpeed)
        val targetChunk = uiState.value.chunks.firstOrNull { it.id == chunkId }
        playbackController.seekToChunk(chunkId)
        val player = playbackController.player.value
        if (player != null) {
            player.setPlaybackSpeed(clampedSpeed)
            player.play()
        } else if (playbackController is FakePlaybackController) {
            if (targetChunk != null) {
                playbackController.position.positionMs = targetChunk.startMs
            }
            playbackController.statusState.update {
                it.copy(
                    isPlaying = true,
                    speed = clampedSpeed,
                    currentChunkId = chunkId,
                    autoPauseEndsAtMs = null,
                )
            }
        }
        localUiState.update { state ->
            val dialog = state.detailedTranscriptionDialog ?: return@update state
            state.copy(
                detailedTranscriptionDialog = dialog.copy(
                    audioSpeed = clampedSpeed,
                    isPreviewPlaying = true,
                ),
            )
        }
        detailedPreviewJob = viewModelScope.launch {
            launch {
                var sawPlaying = false
                playbackController.status.collect { status ->
                    if (status.isPlaying) {
                        sawPlaying = true
                    }
                    if (sawPlaying && (!status.isPlaying || (status.currentChunkId != null && status.currentChunkId != chunkId))) {
                        stopDetailedChunkPreview(pausePlayer = status.isPlaying)
                    }
                }
            }
            while (isActive) {
                delay(50L)
                val pos = playbackController.position.currentPositionMs()
                if (targetChunk != null && pos >= targetChunk.endMs) {
                    stopDetailedChunkPreview(pausePlayer = true)
                    break
                }
            }
        }
    }

    private fun stopDetailedChunkPreview(pausePlayer: Boolean = true) {
        val hadActivePreview = detailedPreviewJob != null ||
            savedSpeedBeforeDetailedPreview != null ||
            localUiState.value.detailedTranscriptionDialog?.isPreviewPlaying == true
        detailedPreviewJob?.cancel()
        detailedPreviewJob = null
        val savedSpeed = savedSpeedBeforeDetailedPreview
        savedSpeedBeforeDetailedPreview = null
        if (hadActivePreview && pausePlayer) {
            val player = playbackController.player.value
            if (player != null) {
                player.pause()
            } else if (playbackController is FakePlaybackController) {
                playbackController.statusState.update { it.copy(isPlaying = false) }
            }
        }
        if (savedSpeed != null) {
            playbackController.setSpeed(savedSpeed)
            playbackController.player.value?.setPlaybackSpeed(savedSpeed)
        }
        localUiState.update { state ->
            val dialog = state.detailedTranscriptionDialog ?: return@update state
            if (!dialog.isPreviewPlaying) {
                state
            } else {
                state.copy(
                    detailedTranscriptionDialog = dialog.copy(isPreviewPlaying = false),
                )
            }
        }
    }

    private fun stopTranslationLoop() {
        translationLoopJob?.cancel()
        translationLoopJob = null
        localUiState.update { state ->
            if (state.translatingChunkId == null) state else state.copy(translatingChunkId = null)
        }
    }

    private fun isChunkEligibleForTranslation(
        chunk: ChunkUi,
        languagePair: LanguagePair?,
    ): Boolean {
        val baseLang = languagePair?.base ?: return false
        val isTargetRole = matchesLanguageRole(chunk.language, languagePair.target) || chunk.language == null
        if (!isTargetRole) return false
        if (chunk.isSkipped || chunk.text.isNullOrBlank()) return false
        if (chunk.id in failedTranslationChunkIds) return false
        val needsTranslation = chunk.translatedText.isNullOrBlank() ||
            (chunk.translatedLanguage != null && !matchesLanguageRole(chunk.translatedLanguage, baseLang))
        return needsTranslation
    }

    private fun orderTranslationCandidateChunks(
        chunks: List<ChunkUi>,
        practice: PracticeSettings,
        positionMs: Long,
    ): List<ChunkUi> {
        if (chunks.isEmpty()) return emptyList()
        fun rotateFromPosition(list: List<ChunkUi>): List<ChunkUi> {
            if (list.isEmpty()) return emptyList()
            val idx = list.indexOfFirst { positionMs < it.endMs }.takeIf { it >= 0 } ?: 0
            return list.subList(idx, list.size) + list.subList(0, idx)
        }

        // 1. 「☑ のみ」選択時
        if (practice.languageFilter == LanguageFilter.CHECKED_ONLY && practice.repeatChunkIds.isNotEmpty()) {
            val checked = chunks.filter { it.id in practice.repeatChunkIds }
            val unchecked = chunks.filterNot { it.id in practice.repeatChunkIds }
            return rotateFromPosition(checked) + rotateFromPosition(unchecked)
        }

        // 2. 区間リピート中
        val loop = practice.loop
        if (loop is LoopMode.ChunkRange) {
            val firstIdx = chunks.indexOfFirst { it.id == loop.firstChunkId }
            val lastIdx = chunks.indexOfFirst { it.id == loop.lastChunkId }
            if (firstIdx >= 0 && lastIdx >= 0) {
                val rangeStart = minOf(firstIdx, lastIdx)
                val rangeEnd = maxOf(firstIdx, lastIdx)
                val inRange = chunks.subList(rangeStart, rangeEnd + 1)
                val afterRange = chunks.subList(rangeEnd + 1, chunks.size)
                val beforeRange = chunks.subList(0, rangeStart)
                return rotateFromPosition(inRange) + afterRange + beforeRange
            }
        }

        // 3. 通常再生時
        val startIndex = chunks.indexOfFirst { positionMs < it.endMs }.takeIf { it >= 0 } ?: 0
        return chunks.subList(startIndex, chunks.size) + chunks.subList(0, startIndex)
    }

    private fun startTranslationLoopIfNeeded() {
        if (translationLoopJob?.isActive == true) return
        val initialState = uiState.value
        val mediaId = resolveActiveMediaId() ?: return
        if (!initialState.showTargetTranslation ||
            !initialState.isTranslationSupported ||
            !initialState.hasTranscript
        ) {
            return
        }
        translationLoopJob = viewModelScope.launch {
            val attemptedInCurrentPass = mutableSetOf<ChunkId>()
            try {
                while (isActive) {
                    val state = uiState.value
                    val activeMediaId = state.mediaId ?: resolveActiveMediaId()
                    val pair = state.languagePair
                    val baseLang = pair?.base
                    if (activeMediaId == null ||
                        activeMediaId != mediaId ||
                        !state.showTargetTranslation ||
                        !state.isTranslationSupported ||
                        !state.hasTranscript ||
                        pair == null ||
                        baseLang == null
                    ) {
                        break
                    }
                    val posMs = playbackController.position.currentPositionMs()
                    val ordered = orderTranslationCandidateChunks(
                        chunks = state.chunks,
                        practice = state.practice,
                        positionMs = posMs,
                    )
                    val nextChunk = ordered.firstOrNull { chunk ->
                        chunk.id !in attemptedInCurrentPass && isChunkEligibleForTranslation(chunk, pair)
                    } ?: break

                    attemptedInCurrentPass.add(nextChunk.id)
                    localUiState.update { it.copy(translatingChunkId = nextChunk.id) }
                    val translated = analysisRepository.translateChunk(
                        id = activeMediaId,
                        chunkId = nextChunk.id,
                        sourceLanguage = pair.target,
                        targetLanguage = baseLang,
                    )
                    if (translated.isNullOrBlank()) {
                        failedTranslationChunkIds.add(nextChunk.id)
                        localUiState.update {
                            it.copy(failedTranslationChunkIds = failedTranslationChunkIds.toSet())
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    localUiState.update { state ->
                        if (state.translatingChunkId == null) state else state.copy(translatingChunkId = null)
                    }
                }
            }
        }
    }

    private fun abortActiveViewModelTranscriptionJobs() {
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
        retranscribeJob?.cancel()
        retranscribeJob = null
        chunkSeekPrioritizeJob?.cancel()
        chunkSeekPrioritizeJob = null
        pendingLanguageCommitJobs.values.forEach { it.cancel() }
        pendingLanguageCommitJobs.clear()
        localUiState.update { it.copy(clearingChunkIds = emptySet()) }
    }

    private fun splitChunkAtPosition(
        id: MediaId,
        chunkId: ChunkId,
        splitPositionMs: Long,
    ) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                val currentRepeatIds = playbackController.practice.value.repeatChunkIds
                val selectedRanges = if (currentRepeatIds.isNotEmpty()) {
                    uiState.value.chunks
                        .filter { it.id in currentRepeatIds && it.id != chunkId }
                        .map { it.startMs to it.endMs }
                        .toSet()
                } else {
                    emptySet()
                }
                val split = analysisRepository.splitChunk(id, chunkId, splitPositionMs)
                if (split && currentRepeatIds.isNotEmpty()) {
                    val updatedChunks = analysisRepository.observeChunks(id).first()
                    val preservedIds = updatedChunks
                        .filter { (it.startMs to it.endMs) in selectedRanges }
                        .map { it.id }
                        .toSet()
                    updatePracticeWithCheckedFallback { current ->
                        if (current.repeatChunkIds.isEmpty()) {
                            current
                        } else {
                            current.copy(
                                repeatChunkIds = preservedIds,
                                temporaryRepeatChunkId = current.temporaryRepeatChunkId?.takeIf { it in preservedIds },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isChunkUndetected(chunk: ChunkUi): Boolean = chunk.text.isNullOrBlank() ||
        chunk.language == null ||
        chunk.language.value.isBlank() ||
        chunk.language.value.equals("und", ignoreCase = true)

    private fun syncLookaheadState(mediaId: MediaId, enabled: Boolean) {
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
        if (!enabled) {
            viewModelScope.launch {
                analysisRepository.setLookaheadEnabled(mediaId, false)
            }
        } else {
            lookaheadLoopJob = viewModelScope.launch {
                analysisRepository.setLookaheadEnabled(mediaId, true)
                val requestedStartIds = mutableSetOf<ChunkId?>()
                val initialChunks = uiState.value.chunks
                val initialChunkId = playbackController.status.value.currentChunkId
                    ?: initialChunks.firstOrNull { isChunkUndetected(it) }?.id
                    ?: initialChunks.firstOrNull()?.id
                    ?: run {
                        val repoChunks = analysisRepository.observeChunks(mediaId).firstOrNull().orEmpty()
                        val repoTranscripts = analysisRepository.observeTranscripts(mediaId).firstOrNull().orEmpty()
                        repoChunks.firstOrNull { chunk ->
                            val transcript = repoTranscripts[chunk.id]
                            transcript?.text.isNullOrBlank() ||
                                transcript.language == null ||
                                transcript.language?.value.isNullOrBlank() ||
                                transcript.language?.value.equals("und", ignoreCase = true)
                        }?.id ?: repoChunks.firstOrNull()?.id
                    }
                requestedStartIds.add(initialChunkId)
                analysisRepository.triggerLookaheadAt(mediaId, initialChunkId)

                while (isActive) {
                    val currentState = uiState.value
                    if (!currentState.lookaheadStatus.enabled || currentState.mediaId != mediaId) {
                        break
                    }
                    val chunks = currentState.chunks
                    if (chunks.isEmpty()) break
                    val activeIdx = currentState.activeChunkIndex ?: 0
                    val orderedCandidates = if (activeIdx in chunks.indices) {
                        chunks.subList(activeIdx, chunks.size) + chunks.subList(0, activeIdx)
                    } else {
                        chunks
                    }
                    val hasMore = orderedCandidates.any { chunk ->
                        isChunkUndetected(chunk) && chunk.id !in requestedStartIds
                    }
                    if (!hasMore) break

                    delay(300L)
                    val refreshedState = uiState.value
                    if (!refreshedState.lookaheadStatus.enabled || refreshedState.mediaId != mediaId) {
                        break
                    }
                    val refreshedChunks = refreshedState.chunks
                    if (refreshedChunks.isEmpty()) break
                    val refreshedActiveIdx = refreshedState.activeChunkIndex ?: 0
                    val refreshedCandidates = if (refreshedActiveIdx in refreshedChunks.indices) {
                        refreshedChunks.subList(refreshedActiveIdx, refreshedChunks.size) +
                            refreshedChunks.subList(0, refreshedActiveIdx)
                    } else {
                        refreshedChunks
                    }
                    val nextUntranscribed = refreshedCandidates.firstOrNull { chunk ->
                        isChunkUndetected(chunk) && chunk.id !in requestedStartIds
                    } ?: break
                    requestedStartIds.add(nextUntranscribed.id)
                    analysisRepository.triggerLookaheadAt(mediaId, nextUntranscribed.id)
                }
            }
        }
    }

    private fun resolveActiveMediaId(): MediaId? = uiState.value.mediaId ?: currentMediaId.value ?: playbackController.status.value.mediaId

    private fun requestAutoFollowResume() {
        localUiState.update { it.copy(autoFollowResetToken = it.autoFollowResetToken + 1L) }
    }

    private fun togglePlayPause() {
        val player = playbackController.player.value
        val isCurrentlyPlaying = playbackController.status.value.isPlaying || player?.isPlaying == true
        val isAutoPaused = playbackController.status.value.autoPauseEndsAtMs != null
        if (!isCurrentlyPlaying || isAutoPaused) {
            requestAutoFollowResume()
        }
        if (player != null) {
            if (isCurrentlyPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { it.copy(isPlaying = !it.isPlaying, autoPauseEndsAtMs = null) }
        }
    }

    private fun seekTo(targetPositionMs: Long) {
        requestAutoFollowResume()
        val durationMs = uiState.value.durationMs
        val clampedMs = if (durationMs > 0L) {
            targetPositionMs.coerceIn(0L, durationMs)
        } else {
            targetPositionMs.coerceAtLeast(0L)
        }
        val player = playbackController.player.value
        if (player != null) {
            player.seekTo(clampedMs)
        }
        val matchingChunk = uiState.value.chunks.firstOrNull { clampedMs >= it.startMs && clampedMs < it.endMs }
            ?: uiState.value.chunks.firstOrNull { clampedMs in it.startMs..it.endMs }
            ?: uiState.value.chunks.lastOrNull { it.startMs <= clampedMs }
        if (playbackController is FakePlaybackController) {
            playbackController.position.positionMs = clampedMs
            if (matchingChunk != null) {
                playbackController.statusState.update { it.copy(currentChunkId = matchingChunk.id) }
            }
        }
        val id = resolveActiveMediaId()
        if (id != null && matchingChunk != null && perMediaLookaheadEnabled.value[id] == true) {
            chunkSeekPrioritizeJob?.cancel()
            chunkSeekPrioritizeJob = viewModelScope.launch {
                analysisRepository.prioritizeChunkTranscription(id, matchingChunk.id)
            }
        }
    }

    /**
     * 言語バッジのトグルを確定し、切り替えた言語で文字起こしをやり直す（認識キューの最優先で実行）。
     *
     * 保存済みの言語（本文あり）と同じ言語に戻した場合は何もしない（誤タップの取り消し）。
     */
    private suspend fun commitChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) {
        val stored = analysisRepository.observeTranscripts(id).firstOrNull()?.get(chunkId)
        if (stored != null && stored.text.isNotBlank() && stored.language == language) {
            localUiState.update { state -> state.copy(languageOverrides = state.languageOverrides - chunkId) }
            return
        }
        localUiState.update { state -> state.copy(clearingChunkIds = state.clearingChunkIds + chunkId) }
        try {
            analysisRepository.updateChunkLanguage(id, chunkId, language)
            withContext(NonCancellable) {
                val hasTranscript =
                    analysisRepository.observeTranscripts(id).firstOrNull()?.containsKey(chunkId) == true
                if (hasTranscript) {
                    localUiState.update { state -> state.copy(languageOverrides = state.languageOverrides - chunkId) }
                }
                val exported = libraryRepository.exportLocalTranscriptJson(id)
                if (exported) {
                    localUiState.update { it.copy(localTranscriptExported = true) }
                }
            }
            val lookaheadOn = perMediaLookaheadEnabled.value[id] ?: uiState.value.lookaheadStatus.enabled
            if (lastOpenedMediaId == id && lookaheadOn) {
                analysisRepository.triggerLookaheadAt(id, chunkId)
            }
        } finally {
            localUiState.update { state -> state.copy(clearingChunkIds = state.clearingChunkIds - chunkId) }
        }
    }

    private fun seekToChunk(chunkId: ChunkId) {
        requestAutoFollowResume()
        playbackController.updatePractice { current ->
            val tempId = current.temporaryRepeatChunkId
            val isRepeatActive = !current.repeatWholeFile && (current.repeatCheckedOnly || current.loop !is LoopMode.Off)
            if (tempId != null && isRepeatActive && chunkId != tempId) {
                current.copy(
                    repeatChunkIds = (current.repeatChunkIds - tempId) + chunkId,
                    temporaryRepeatChunkId = chunkId,
                )
            } else {
                current
            }
        }
        playbackController.seekToChunk(chunkId)
        if (playbackController is FakePlaybackController) {
            val targetChunk = uiState.value.chunks.firstOrNull { it.id == chunkId }
            if (targetChunk != null) {
                playbackController.position.positionMs = targetChunk.startMs
            }
            playbackController.statusState.update {
                it.copy(
                    isPlaying = true,
                    currentChunkId = chunkId,
                    autoPauseEndsAtMs = null,
                )
            }
        }
        val id = resolveActiveMediaId()
        if (id != null && perMediaLookaheadEnabled.value[id] == true) {
            chunkSeekPrioritizeJob?.cancel()
            chunkSeekPrioritizeJob = viewModelScope.launch {
                analysisRepository.prioritizeChunkTranscription(id, chunkId)
            }
        }
    }

    private fun seekToAdjacentChunk(direction: Int) {
        requestAutoFollowResume()
        val player = playbackController.player.value
        if (player != null) {
            if (direction < 0) {
                player.seekToPrevious()
            } else {
                player.seekToNext()
            }
            return
        }
        val chunks = uiState.value.chunks
        if (chunks.isEmpty()) {
            if (direction < 0) seekTo(0L)
            return
        }
        val currentIdx = uiState.value.activeChunkIndex
            ?: chunks.indexOfFirst { playbackController.position.currentPositionMs() in it.startMs..it.endMs }
                .takeIf { it >= 0 }
            ?: 0
        val targetIdx = (currentIdx + direction).coerceIn(0, chunks.lastIndex)
        seekToChunk(chunks[targetIdx].id)
    }

    private fun seekToFileStart() {
        requestAutoFollowResume()
        val firstChunk = uiState.value.chunks.firstOrNull { !it.isSkipped }
            ?: uiState.value.chunks.firstOrNull()
        if (firstChunk != null) {
            seekToChunk(firstChunk.id)
        } else {
            seekTo(0L)
        }
    }

    private fun seekToCheckedChunk(direction: Int) {
        requestAutoFollowResume()
        val chunks = uiState.value.chunks
        if (chunks.isEmpty()) return
        val posMs = playbackController.position.currentPositionMs()
        val currentIdx = uiState.value.activeChunkIndex
            ?: chunks.indexOfFirst { posMs in it.startMs..it.endMs }.takeIf { it >= 0 }
            ?: if (direction < 0) chunks.size else -1
        val useRepeatChecked = chunks.any { it.isRepeatChecked }
        val target = if (direction < 0) {
            val endExclusive = currentIdx.coerceIn(0, chunks.size)
            chunks.subList(0, endExclusive).lastOrNull { chunk ->
                if (useRepeatChecked) chunk.isRepeatChecked else chunk.isBookmarked
            }
        } else {
            val startInclusive = (currentIdx + 1).coerceIn(0, chunks.size)
            chunks.subList(startInclusive, chunks.size).firstOrNull { chunk ->
                if (useRepeatChecked) chunk.isRepeatChecked else chunk.isBookmarked
            }
        }
        if (target != null) {
            seekToChunk(target.id)
        }
    }

    private fun roundSpeed(rawSpeed: Float): Float {
        val clamped = rawSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        return ((clamped * 20f).roundToInt() / 20f).coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
    }

    private fun setSpeed(rawSpeed: Float) {
        val rounded = roundSpeed(rawSpeed)
        playbackController.setSpeed(rounded)
        if (kotlin.math.abs(rounded - 1.0f) >= 0.01f) {
            localUiState.update { it.copy(customSpeedOverride = rounded) }
            viewModelScope.launch {
                userSettingsRepository.setDefaultSpeed(rounded)
            }
        }
    }

    private fun setCustomSpeed(rawSpeed: Float) {
        val rounded = roundSpeed(rawSpeed)
        localUiState.update { it.copy(customSpeedOverride = rounded) }
        viewModelScope.launch {
            userSettingsRepository.setDefaultSpeed(rounded)
        }
        playbackController.setSpeed(rounded)
    }

    private fun buildUiState(
        mediaId: MediaId?,
        item: AudioItem?,
        itemState: ItemState?,
        analysis: AnalysisSnapshot,
        status: PlaybackStatus,
        practice: PracticeSettings,
        settings: UserSettings,
        local: LocalUiSnapshot,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        nextMediaId: MediaId? = null,
    ): PlayerUiState {
        val resolvedDurationMs = when {
            status.durationMs > 0L -> status.durationMs
            item != null && item.durationMs > 0L -> item.durationMs
            analysis.waveform != null -> analysis.waveform.durationMs
            else -> 0L
        }
        val chunkUis = analysis.chunks.map { chunk ->
            val transcript = analysis.transcripts[chunk.id]
            val isBookmarked = analysis.bookmarks.any { bookmark ->
                (bookmark.positionMs >= chunk.startMs && bookmark.positionMs < chunk.endMs) ||
                    bookmark.positionMs == chunk.startMs
            }
            val hasPendingLanguageCommit = pendingLanguageCommitJobs[chunk.id]?.isActive == true
            val resolvedLanguage = if (
                chunk.id in local.languageOverrides &&
                (hasPendingLanguageCommit || transcript?.text.isNullOrBlank())
            ) {
                local.languageOverrides[chunk.id]
            } else {
                transcript?.language ?: local.languageOverrides[chunk.id]
            }
            val isClearing = chunk.id in local.clearingChunkIds
            ChunkUi(
                id = chunk.id,
                startMs = chunk.startMs,
                endMs = chunk.endMs,
                text = if (isClearing) null else transcript?.text?.takeIf { it.isNotBlank() },
                language = resolvedLanguage,
                isBookmarked = isBookmarked,
                isRepeatChecked = chunk.id in practice.repeatChunkIds,
                isSkipped = chunk.isSkipped,
                translatedText = if (isClearing) null else transcript?.translatedText?.takeIf { it.isNotBlank() },
                translatedLanguage = if (isClearing) null else transcript?.translatedLanguage,
                textVariants = if (isClearing) emptyMap() else transcript?.textVariants.orEmpty(),
                words = if (isClearing) emptyList() else transcript?.words.orEmpty(),
                origin = chunk.origin,
            )
        }
        val activeIndex = status.currentChunkId?.let { activeId ->
            chunkUis.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
                ?: run {
                    val posMs = playbackController.position.currentPositionMs().coerceAtLeast(0L)
                    chunkUis.indexOfFirst { posMs in it.startMs until it.endMs }.takeIf { it >= 0 }
                        ?: chunkUis.indexOfLast { it.startMs <= posMs }.takeIf { it >= 0 }
                }
        }
        val resolvedOrigin = analysis.chunks.firstOrNull()?.origin
            ?: itemState?.chunkSource
            ?: ChunkOrigin.AUTO
        val effectiveQueuedIds = (analysis.lookaheadStatus.queuedChunkIds + local.clearingChunkIds) -
            setOfNotNull(analysis.lookaheadStatus.inFlightChunkId)
        val effectiveLookaheadStatus = analysis.lookaheadStatus.copy(
            enabled = local.isLookaheadEnabled && analysis.lookaheadStatus.enabled,
            queuedChunkIds = effectiveQueuedIds,
            skippedEmptyChunkIds = analysis.lookaheadStatus.skippedEmptyChunkIds - local.manuallyClearedChunkIds,
            recognitionModelIssues = analysis.lookaheadStatus.recognitionModelIssues.filterKeys { language ->
                val pair = item?.languagePair
                matchesLanguageRole(language, pair?.target) || matchesLanguageRole(language, pair?.base)
            },
        )
        val isTranslationSupported = item?.languagePair?.let { pair ->
            analysisRepository.isTranslationSupported(pair.target, pair.base)
        } ?: false
        val resolvedCustomSpeed = local.customSpeedOverride
            ?: settings.defaultSpeed.takeIf { kotlin.math.abs(it - 1.0f) >= 0.01f }
            ?: status.speed.takeIf { kotlin.math.abs(it - 1.0f) >= 0.01f }
            ?: PlayerUiState.DEFAULT_CUSTOM_SPEED

        val persistedCustomSeg = itemState?.customSegmentationParams
        val useGlobalSegmentation = local.useGlobalSegmentationOverride ?: (persistedCustomSeg == null)
        val resolvedSegmentationParams = if (useGlobalSegmentation) {
            settings.defaultSegmentationParams
        } else {
            local.customSegmentationParams ?: persistedCustomSeg ?: settings.defaultSegmentationParams
        }

        return PlayerUiState(
            mediaId = mediaId ?: status.mediaId ?: item?.id,
            title = item?.title.orEmpty(),
            course = item?.course,
            languagePair = item?.languagePair,
            isPlaying = status.isPlaying,
            durationMs = resolvedDurationMs,
            speed = status.speed,
            customSpeed = resolvedCustomSpeed,
            seekBackMs = settings.seekBackMs,
            seekForwardMs = settings.seekForwardMs,
            practice = practice,
            autoPauseEndsAtMs = status.autoPauseEndsAtMs,
            waveform = analysis.waveform,
            chunks = chunkUis,
            bookmarks = analysis.bookmarks,
            activeChunkIndex = activeIndex,
            analysis = analysis.status,
            error = status.error,
            blindMode = local.blindMode,
            revealedChunkIds = local.revealedChunkIds,
            chineseScript = explicitChineseScriptForTag(item?.languagePair?.target?.value)
                ?: explicitChineseScriptForTag(item?.languagePair?.base?.value)
                ?: settings.chineseScript,
            cantoneseScript = settings.cantoneseScript,
            showTargetRomanization = settings.showTargetRomanization,
            showTargetTranslation = local.showTargetTranslation,
            isTranslationSupported = isTranslationSupported,
            translatingChunkId = local.translatingChunkId,
            failedTranslationChunkIds = local.failedTranslationChunkIds,
            isTranscriptionProgressDismissed = local.isTranscriptionProgressDismissed,
            chunkSource = resolvedOrigin,
            availableChunkOrigins = analysis.availableOrigins,
            segmentationParams = resolvedSegmentationParams,
            useGlobalSegmentationParams = useGlobalSegmentation,
            transcriptImportBanner = local.transcriptImportBanner,
            transcriptExportBanner = local.transcriptExportBanner,
            transcriptOffsetMs = local.transcriptOffsetMs,
            lookaheadStatus = effectiveLookaheadStatus,
            localTranscriptExported = local.localTranscriptExported,
            detailedTranscriptionDialog = local.detailedTranscriptionDialog,
            itemSttOverride = itemSttOverride,
            sttEngineByLanguage = settings.sttEngineByLanguage,
            languageModelInfos = local.languageModelInfos,
            enabledLanguages = settings.enabledLanguages.toList(),
            nextMediaId = nextMediaId,
            backgroundSpeechRecognizerWarning = local.backgroundSpeechRecognizerWarning,
            isLanguageFilterFallbackHighlighted = local.isLanguageFilterFallbackHighlighted,
            autoFollowResetToken = local.autoFollowResetToken,
        )
    }

    /**
     * [PracticeSettings] を更新し、もし `languageFilter == LanguageFilter.CHECKED_ONLY` の状態で
     * `repeatChunkIds` がすべて外れた場合は `LanguageFilter.ALL` へ自動フォールバックし、
     * 言語フィルタバッジのハイライト演出を発火する。
     */
    private fun updatePracticeWithCheckedFallback(transform: (PracticeSettings) -> PracticeSettings) {
        var didFallback = false
        playbackController.updatePractice { current ->
            val updated = transform(current)
            if (updated.languageFilter == LanguageFilter.CHECKED_ONLY && updated.repeatChunkIds.isEmpty()) {
                didFallback = true
                updated.copy(languageFilter = LanguageFilter.ALL)
            } else {
                updated
            }
        }
        if (didFallback) {
            triggerLanguageFilterFallbackHighlight()
        }
    }

    private fun triggerLanguageFilterFallbackHighlight() {
        languageFilterHighlightJob?.cancel()
        localUiState.update { it.copy(isLanguageFilterFallbackHighlighted = true) }
        languageFilterHighlightJob = viewModelScope.launch {
            delay(LANGUAGE_FILTER_FALLBACK_HIGHLIGHT_MS)
            localUiState.update { it.copy(isLanguageFilterFallbackHighlighted = false) }
        }
    }

    private fun isValidLanguageTag(tag: LanguageTag): Boolean {
        val cleaned = tag.value.trim()
        return cleaned.isNotEmpty() && !cleaned.equals("und", ignoreCase = true)
    }

    private fun compareFileNamesNatural(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = ia
                while (ia < a.length && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < b.length && b[ib].isDigit()) ib++
                val numA = BigInteger(a.substring(startA, ia))
                val numB = BigInteger(b.substring(startB, ib))
                val numCmp = numA.compareTo(numB)
                if (numCmp != 0) return numCmp
                val lenCmp = (ia - startA).compareTo(ib - startB)
                if (lenCmp != 0) return lenCmp
            } else {
                val lowerA = ca.lowercaseChar()
                val lowerB = cb.lowercaseChar()
                if (lowerA != lowerB) return lowerA.compareTo(lowerB)
                ia++
                ib++
            }
        }
        return (a.length - ia).compareTo(b.length - ib)
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
