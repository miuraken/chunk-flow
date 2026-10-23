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
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.FileEndAction
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
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
import com.miuraken.chunkflow.core.model.sortableFileName
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.lifecycle.HiltViewModel
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

            else -> info.statusOf(toSttEngine(targetEngine))
        }
    }

    /**
     * 詳細文字起こしダイアログで、言語 [language] に対してエンジン [targetEngine] の選択肢を有効（選択可能）にするかどうかを返す。
     *
     * いずれのエンジンも、端末・言語で対応が確認されている（[OnDeviceLanguageModelStatus.UNSUPPORTED] でも
     * [OnDeviceLanguageModelStatus.CHECKING] でもない）ときだけ有効にする。
     * クラウド API は API キーが設定済み（[OnDeviceLanguageModelStatus.INSTALLED]）のときだけ有効にする。
     */
    fun isEngineEnabled(
        language: LanguageTag?,
        targetEngine: OnDeviceTranscriptionEngine,
    ): Boolean {
        val status = engineStatus(language, targetEngine)
        val sttEngine = toSttEngine(targetEngine)
        if (sttEngine.isCloudApi) {
            return status == OnDeviceLanguageModelStatus.INSTALLED
        }
        return status != OnDeviceLanguageModelStatus.UNSUPPORTED &&
            status != OnDeviceLanguageModelStatus.CHECKING
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
        /** 詳細文字起こしで選択可能なエンジンの並び順。 */
        val ENGINE_OPTIONS: List<OnDeviceTranscriptionEngine> = listOf(
            OnDeviceTranscriptionEngine.ML_KIT,
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

            OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE -> SttEngine.SHERPA_ONNX_SENSE_VOICE

            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE -> SttEngine.SHERPA_ONNX_WHISPER_BASE

            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL -> SttEngine.SHERPA_ONNX_WHISPER_SMALL

            OnDeviceTranscriptionEngine.MOONSHINE_TINY -> SttEngine.MOONSHINE_TINY

            OnDeviceTranscriptionEngine.MOONSHINE_BASE -> SttEngine.MOONSHINE_BASE

            OnDeviceTranscriptionEngine.OPENAI_API -> SttEngine.OPENAI_API

            OnDeviceTranscriptionEngine.GROQ_API -> SttEngine.GROQ_API

            OnDeviceTranscriptionEngine.GEMINI_API -> SttEngine.GEMINI_API
        }

        /**
         * 指定した言語 [language] で「より詳しく文字起こし」ダイアログのエンジン選択肢として表示する
         * キュレーション済みエンジン一覧を返す。
         */
        fun fromSttEngine(engine: SttEngine): OnDeviceTranscriptionEngine = when (engine) {
            SttEngine.ML_KIT -> OnDeviceTranscriptionEngine.ML_KIT
            SttEngine.SPEECH_RECOGNIZER -> OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER
            SttEngine.SHERPA_ONNX -> OnDeviceTranscriptionEngine.SHERPA_ONNX
            SttEngine.SHERPA_ONNX_SENSE_VOICE -> OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE
            SttEngine.SHERPA_ONNX_WHISPER_BASE -> OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE
            SttEngine.SHERPA_ONNX_WHISPER_SMALL -> OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL
            SttEngine.MOONSHINE_TINY -> OnDeviceTranscriptionEngine.MOONSHINE_TINY
            SttEngine.MOONSHINE_BASE -> OnDeviceTranscriptionEngine.MOONSHINE_BASE
            SttEngine.OPENAI_API -> OnDeviceTranscriptionEngine.OPENAI_API
            SttEngine.GROQ_API -> OnDeviceTranscriptionEngine.GROQ_API
            SttEngine.GEMINI_API -> OnDeviceTranscriptionEngine.GEMINI_API
        }

        internal fun enrichWithCatalog(
            info: OnDeviceLanguageModelInfo,
            language: LanguageTag,
        ): OnDeviceLanguageModelInfo = info.enrichedWithCatalog(language)
    }

    /**
     * 言語 [language] に対して「より詳しく文字起こし」ダイアログで提示するキュレーション済みエンジンの一覧を返す。
     */
    fun curatedEnginesForLanguage(language: LanguageTag?): List<OnDeviceTranscriptionEngine> {
        val info = modelInfoFor(language)
        return SttEngineSelection.detailedTrialEnginesForLanguage(info).map { fromSttEngine(it) }
    }
}

/**
 * ファイルを開いて音声認識を開始した際、その言語で既定モデルより明確に高速・高精度なおすすめモデルが
 * 未ダウンロードのときに表示する推奨ダウンロードダイアログの状態。
 *
 * @property language 対象の言語タグ。
 * @property engine 推奨される文字起こしエンジン。
 * @property sizeMb 推定ダウンロードサイズ（MB）。
 * @property isDownloading 現在ダウンロードを実行中かどうか。
 * @property isFailed 直前のダウンロード試行が失敗したかどうか。
 */
data class RecommendedSttModelDialogState(
    val language: LanguageTag,
    val engine: SttEngine,
    val sizeMb: Int = DownloadableSttModel.fromEngineOrNull(engine)?.approxSizeMbForLanguage(language) ?: 0,
    val isDownloading: Boolean = false,
    val isFailed: Boolean = false,
)

/**
 * 詳細文字起こしの試行結果が空だったときに、モデル状態・エンジン・マイク権限に応じた具体的な理由メッセージを返す。
 */
internal fun resolveDetailedTrialEmptyMessage(
    targetLanguage: LanguageTag,
    engine: OnDeviceTranscriptionEngine,
    modelStatus: OnDeviceLanguageModelStatus,
    hasRecordAudioPermission: Boolean = true,
): String = when {
    (
        engine == OnDeviceTranscriptionEngine.OPENAI_API ||
            engine == OnDeviceTranscriptionEngine.GROQ_API ||
            engine == OnDeviceTranscriptionEngine.GEMINI_API
        ) && modelStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ->
        "API キーが設定されていません。設定画面の「クラウド文字起こし API キー」で入力し、「保存」を押してから再試行してください。"

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

            SttEngine.SPEECH_RECOGNIZER ->
                preferredStatus != OnDeviceLanguageModelStatus.UNSUPPORTED

            SttEngine.SHERPA_ONNX,
            SttEngine.SHERPA_ONNX_SENSE_VOICE,
            SttEngine.SHERPA_ONNX_WHISPER_BASE,
            SttEngine.SHERPA_ONNX_WHISPER_SMALL,
            SttEngine.MOONSHINE_TINY,
            SttEngine.MOONSHINE_BASE,
            SttEngine.OPENAI_API,
            SttEngine.GROQ_API,
            SttEngine.GEMINI_API,
            ->
                preferredStatus == OnDeviceLanguageModelStatus.INSTALLED
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
    val recommendedSttModelDialog: RecommendedSttModelDialogState? = null,
    val itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
    val folderSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
    val sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    val languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    val enabledLanguages: List<LanguageTag> = UserSettings.DEFAULT_ENABLED_LANGUAGES.toList(),
    val nextMediaId: MediaId? = null,
    val nextMediaTitle: String? = null,
    val backgroundSpeechRecognizerWarning: BackgroundSpeechRecognizerWarningState? = null,
    val isLanguageFilterFallbackHighlighted: Boolean = false,
    val isTranscriptionBadgeTooltipVisible: Boolean = false,
    val autoFollowResetToken: Long = 0L,
    val isOverviewWaveformRenderComplete: Boolean = false,
    val isZoomedWaveformRenderComplete: Boolean = false,
    val baseWaveformChunks: List<ChunkUi> = emptyList(),
    val waveformRenderGeneration: Long = 0L,
    val baseLanguageSpeedEnabled: Boolean = false,
    val baseLanguageSpeed: Float = 1.0f,
    val isChunkBoundaryEditMode: Boolean = false,
    val pendingOpenMediaId: MediaId? = null,
    val returnToLibraryFolderPath: String? = null,
) {
    /** 全体波形・拡大波形の両方のレンダリングが完成しているかどうか。 */
    val isWaveformRenderComplete: Boolean
        get() = isOverviewWaveformRenderComplete && isZoomedWaveformRenderComplete

    /** 範囲変更モードが有効で、かつチャンクが 1 つだけ選択されているときの対象チャンク。 */
    val editingBoundaryChunk: ChunkUi?
        get() {
            if (!isChunkBoundaryEditMode) return null
            if (practice.loop is LoopMode.AbRange) return null
            val selected = chunks.filter { it.isRepeatChecked }
            return selected.singleOrNull()
        }

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

    /** 現在位置より前にチェック済みの有効チャンクがあるかどうか。 */
    val hasPreviousCheckedChunk: Boolean
        get() {
            val idx = activeChunkIndex ?: 0
            if (idx <= 0 || chunks.isEmpty()) return false
            return chunks.subList(0, idx.coerceAtMost(chunks.size)).any { chunk ->
                chunk.isRepeatChecked && !chunk.isSkipped
            }
        }

    /** 現在位置より後ろにチェック済みの有効チャンクがあるかどうか。 */
    val hasNextCheckedChunk: Boolean
        get() {
            if (chunks.isEmpty()) return false
            val startIdx = (activeChunkIndex ?: -1) + 1
            if (startIdx !in chunks.indices) return false
            return chunks.subList(startIdx, chunks.size).any { chunk ->
                chunk.isRepeatChecked && !chunk.isSkipped
            }
        }

    /** 現在位置より前にしおり（ブックマーク）付きの有効チャンクがあるかどうか。 */
    val hasPreviousBookmarkedChunk: Boolean
        get() {
            val idx = activeChunkIndex ?: 0
            if (idx <= 0 || chunks.isEmpty()) return false
            return chunks.subList(0, idx.coerceAtMost(chunks.size)).any { chunk ->
                chunk.isBookmarked && !chunk.isSkipped
            }
        }

    /** 現在位置より後ろにしおり（ブックマーク）付きの有効チャンクがあるかどうか。 */
    val hasNextBookmarkedChunk: Boolean
        get() {
            if (chunks.isEmpty()) return false
            val startIdx = (activeChunkIndex ?: -1) + 1
            if (startIdx !in chunks.indices) return false
            return chunks.subList(startIdx, chunks.size).any { chunk ->
                chunk.isBookmarked && !chunk.isSkipped
            }
        }

    companion object {
        /** 指定速度バッジの初期倍率（1.5 倍）。 */
        const val DEFAULT_CUSTOM_SPEED: Float = 1.5f

        /** 再生速度の微調整の刻み幅（0.05 倍、F3.2）。 */
        const val SPEED_STEP: Float = 0.05f

        /** 再生速度のプリセット一覧（F3.2、1.0x を除く）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

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

    /** ベース言語のみ別の速度で再生するかどうかを切り替える。 */
    data class SetBaseLanguageSpeedEnabled(val enabled: Boolean) : PlayerEvent

    /** ベース言語の再生速度を変更する。 */
    data class SetBaseLanguageSpeed(val speed: Float) : PlayerEvent

    /** リピートモードを変更する（F6.1、F6.2）。 */
    data class SetLoopMode(val loopMode: LoopMode) : PlayerEvent

    /** 下部再生コントロールの「リピート」トグル（on / *off）を切り替える。 */
    data object ToggleRepeat : PlayerEvent

    /** リピートバッジの長押し確認ダイアログで「はい」が選ばれたときにファイル全体リピートを有効にする（チャンク選択は変更しない）。 */
    data object EnableWholeFileRepeat : PlayerEvent

    /** ファイルの最後まで再生したときの挙動（リピート / 停止 / 次のファイル / シャッフル）を変更する。 */
    data class SetFileEndAction(val action: FileEndAction) : PlayerEvent

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

    /** 1 つのチャンクを選択しているときの「範囲変更」モードを切り替える。 */
    data object ToggleChunkBoundaryEditMode : PlayerEvent

    /** 1 つのチャンクを選択しているときに、開始または終了の境界を [deltaMs]（例: `-100L` / `+100L`）だけ調整する。 */
    data class NudgeSelectedChunkBoundary(
        val isStart: Boolean,
        val deltaMs: Long,
    ) : PlayerEvent

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

    /** 言語判定完了時に表示される「文字起こし」バッジの案内ツールチップを閉じる。 */
    data object DismissTranscriptionBadgeTooltip : PlayerEvent

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

    /** ファイル設定の対象言語・ベース言語の文字起こしエンジン上書きを解除し、全体設定に従う状態へ戻す。 */
    data object ClearItemSttOverride : PlayerEvent

    /** ファイル設定の音声認識モデル管理から指定言語・エンジンのモデル取得を要求する。 */
    data class RequestItemSttModelDownload(
        val language: LanguageTag,
        val engine: com.miuraken.chunkflow.core.model.SttEngine,
    ) : PlayerEvent

    /** ファイル設定の音声認識モデル管理から指定言語の sherpa-onnx Whisper Tiny モデルを削除する。 */
    data class DeleteItemSherpaModel(val language: LanguageTag) : PlayerEvent

    /** ファイル設定の音声認識モデル管理から指定言語の言語別ローカルモデルを削除する。 */
    data class DeleteItemLanguageSttModel(
        val language: LanguageTag,
        val model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ) : PlayerEvent

    /** ファイル設定の音声認識モデル管理から多言語共通のローカルモデルを削除（パージ）する。 */
    data class PurgeItemSttModel(
        val model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ) : PlayerEvent

    /** 巻き戻し秒数（ミリ秒）を変更する。 */
    data class SetSeekBackMs(val durationMs: Long) : PlayerEvent

    /** 早送り秒数（ミリ秒）を変更する。 */
    data class SetSeekForwardMs(val durationMs: Long) : PlayerEvent

    /** ファイルの先頭（最初の有効チャンクまたは 0ms）へ移動する。 */
    data object SeekToFileStart : PlayerEvent

    /** 現在位置より前のチェック項目（選択チャンク）へ移動する。 */
    data object SeekToPreviousCheckedChunk : PlayerEvent

    /** 現在位置より後ろのチェック項目（選択チャンク）へ移動する。 */
    data object SeekToNextCheckedChunk : PlayerEvent

    /** 現在位置より前のしおり（ブックマーク）付きチャンクへ移動する。 */
    data object SeekToPreviousBookmarkedChunk : PlayerEvent

    /** 現在位置より後ろのしおり（ブックマーク）付きチャンクへ移動する。 */
    data object SeekToNextBookmarkedChunk : PlayerEvent

    /** 同じフォルダ（または再生前ファイル一覧）内の次の教材ファイルを開いて再生する。 */
    data object OpenNextMediaItem : PlayerEvent

    /** 自動遷移で次のファイルへ切り替わった通知を消費する。 */
    data object ConsumePendingOpenMedia : PlayerEvent

    /** 次のファイルがないときにライブラリの該当フォルダへ戻る通知を消費する。 */
    data object ConsumeReturnToLibraryFolder : PlayerEvent

    /** 全体波形のプログレッシブ更新レンダリングが完了したことを記録する。 */
    data object MarkOverviewWaveformRenderComplete : PlayerEvent

    /** 拡大波形のプログレッシブ更新レンダリングが完了したことを記録する。 */
    data object MarkZoomedWaveformRenderComplete : PlayerEvent

    /** おすすめ STT モデルのダウンロード確認ダイアログでダウンロードを開始し、完了後にその言語の既定エンジンへ設定する。 */
    data object ConfirmRecommendedSttModelDownload : PlayerEvent

    /** おすすめ STT モデルのダウンロード確認ダイアログを閉じる。 */
    data object DismissRecommendedSttModelDownload : PlayerEvent
}

/** 端末内音声認識モデルの問題が残っている間、ダウンロードの完了を OS に問い合わせる間隔。 */
private const val RECOGNITION_MODEL_REFRESH_INTERVAL_MS: Long = 30_000L

/** 言語バッジの最後のトグルから、文字起こしのやり直しを始めるまでの待ち時間（誤タップ対策）。 */
internal const val LANGUAGE_TOGGLE_COMMIT_DELAY_MS: Long = 1_000L

/** 「☑ のみ」からすべてのチェックが外れて「両方」へ自動復帰したときに言語フィルタバッジを強調表示する時間（ミリ秒）。 */
internal const val LANGUAGE_FILTER_FALLBACK_HIGHLIGHT_MS: Long = 3_000L

/** 自動文字起こしがオフのとき、言語判定完了後に「文字起こし」バッジへ表示するツールチップの表示時間（ミリ秒）。 */
internal const val TRANSCRIPTION_BADGE_TOOLTIP_DURATION_MS: Long = 5_000L

internal data class ChunkVisualKey(
    val id: ChunkId,
    val startMs: Long,
    val endMs: Long,
    val language: String?,
    val isSkipped: Boolean,
    val isTranscribed: Boolean,
)

internal data class WaveformRenderSignature(
    val binCount: Int,
    val durationMs: Long,
    val chunkKeys: List<ChunkVisualKey>,
)

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
    val recommendedSttModelDialog: RecommendedSttModelDialogState? = null,
    val hasRecordAudioPermission: Boolean = true,
    val backgroundSpeechRecognizerWarning: BackgroundSpeechRecognizerWarningState? = null,
    val isLookaheadEnabled: Boolean = false,
    val isLanguageFilterFallbackHighlighted: Boolean = false,
    val isTranscriptionBadgeTooltipVisible: Boolean = false,
    val autoFollowResetToken: Long = 0L,
    val completedOverviewSignature: WaveformRenderSignature? = null,
    val completedZoomedSignature: WaveformRenderSignature? = null,
    val baseWaveformChunks: List<ChunkUi> = emptyList(),
    val waveformRenderGeneration: Long = 0L,
    val isChunkBoundaryEditMode: Boolean = false,
    val pendingOpenMediaId: MediaId? = null,
    val returnToLibraryFolderPath: String? = null,
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
    private val analyticsTracker: com.miuraken.chunkflow.core.model.UsageAnalyticsTracker,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        analysisRepository: AnalysisRepository,
        playbackController: PlaybackController,
        userSettingsRepository: UserSettingsRepository,
        itemSttOverrideRepository: com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository,
    ) : this(
        libraryRepository = libraryRepository,
        analysisRepository = analysisRepository,
        playbackController = playbackController,
        userSettingsRepository = userSettingsRepository,
        itemSttOverrideRepository = itemSttOverrideRepository,
        analyticsTracker = com.miuraken.chunkflow.core.model.NoOpUsageAnalyticsTracker,
    )

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
        analyticsTracker = com.miuraken.chunkflow.core.model.NoOpUsageAnalyticsTracker,
    )

    private val currentMediaId = MutableStateFlow<MediaId?>(null)
    private val localUiState = MutableStateFlow(LocalUiSnapshot())
    private val playlistMediaIdsFlow = MutableStateFlow<List<MediaId>>(emptyList())
    private val nextShuffledMediaIdFlow = MutableStateFlow<MediaId?>(null)
    private val playedShuffleMediaIds = mutableSetOf<MediaId>()
    private val perMediaLookaheadEnabled = MutableStateFlow<Map<MediaId, Boolean>>(emptyMap())
    private val prioritizedMediaIds = MutableStateFlow<Set<MediaId>>(emptySet())
    private var lastOpenedMediaId: MediaId? = null
    private val completedOverviewSignaturesByMedia = mutableMapOf<MediaId, WaveformRenderSignature>()
    private val completedZoomedSignaturesByMedia = mutableMapOf<MediaId, WaveformRenderSignature>()
    private val lastRenderedChunksByMedia = mutableMapOf<MediaId, List<ChunkUi>>()
    private var nextWaveformRenderGeneration: Long = 1L

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
    private var transcriptionBadgeTooltipJob: Job? = null
    private val languageDetectionObservedRunningMediaIds = MutableStateFlow<Set<MediaId>>(emptySet())
    private val shownTranscriptionBadgeTooltipMediaIds = mutableSetOf<MediaId>()
    private val promptedRecommendedModelLanguages = mutableSetOf<String>()

    private fun isLookaheadActivelyRunningInRepo(status: LookaheadTranscriptionStatus): Boolean = status.enabled &&
        (status.backgroundEnabled || status.inFlightChunkId != null || status.queuedChunkIds.isNotEmpty())

    private fun resolveNextShuffleCandidate(
        currentId: MediaId,
        candidates: List<AudioItem>,
        cachedNextId: MediaId?,
    ): AudioItem? {
        playedShuffleMediaIds.add(currentId)
        val unplayed = candidates.filter { it.id != currentId && it.id !in playedShuffleMediaIds }
        if (unplayed.isEmpty()) return null
        val cached = cachedNextId?.let { cid -> unplayed.firstOrNull { it.id == cid } }
        if (cached != null) return cached
        val chosen = unplayed.random()
        nextShuffledMediaIdFlow.value = chosen.id
        return chosen
    }

    /** 高頻度で変わる再生位置のプロバイダ（§6.2）。 */
    val positionProvider: PositionProvider
        get() = playbackController.position

    private fun observeAnalysisSnapshot(id: MediaId) = combine(
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

    private val currentAnalysisSnapshot = currentMediaId.flatMapLatest { id ->
        if (id == null) {
            flowOf(AnalysisSnapshot())
        } else {
            observeAnalysisSnapshot(id)
        }
    }

    private data class MediaStateInfo(
        val mediaId: MediaId?,
        val item: AudioItem?,
        val itemState: ItemState?,
        val analysis: AnalysisSnapshot,
        val itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride =
            com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        val folderSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride =
            com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        val nextMediaId: MediaId? = null,
        val nextMediaTitle: String? = null,
    )

    private val mediaAndAnalysisFlow = currentMediaId.flatMapLatest { id ->
        if (id == null) {
            flowOf(MediaStateInfo(mediaId = null, item = null, itemState = null, analysis = AnalysisSnapshot()))
        } else {
            val playlistAndShuffleStateFlow = combine(
                playlistMediaIdsFlow,
                playbackController.practice.map { it.fileEndAction }.distinctUntilChanged(),
                nextShuffledMediaIdFlow,
            ) { playlistIds, fileEndAction, nextShuffledId ->
                Triple(playlistIds, fileEndAction, nextShuffledId)
            }
            val itemAndNextFlow = combine(
                libraryRepository.observeItem(id),
                libraryRepository.observeItems(),
                libraryRepository.observeCourseFolders(),
                playlistAndShuffleStateFlow,
            ) { item, allItems, courseFolders, (playlistIds, fileEndAction, nextShuffledId) ->
                val nextItem = if (item == null) {
                    null
                } else {
                    val candidates = resolveOrderedCandidateItems(
                        current = item,
                        allItems = allItems,
                        playlistMediaIds = playlistIds,
                    )
                    if (fileEndAction == FileEndAction.SHUFFLE) {
                        resolveNextShuffleCandidate(
                            currentId = id,
                            candidates = candidates,
                            cachedNextId = nextShuffledId,
                        )
                    } else {
                        val idx = candidates.indexOfFirst { it.id == id }
                        if (idx >= 0) candidates.getOrNull(idx + 1) else null
                    }
                }
                // 内包するフォルダの音声認識エンジンの上書き（近いフォルダを言語ごとに優先。ファイル個別設定は含まない）
                val folderOverride = if (item == null) {
                    com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                } else {
                    LibraryCourseFolder.resolveSttOverride(item.course, item.languagePair, courseFolders)
                }
                Triple(item, nextItem, folderOverride)
            }
            combine(
                itemAndNextFlow,
                libraryRepository.observeItemState(id),
                observeAnalysisSnapshot(id),
                itemSttOverrideRepository.observe(id),
            ) { (item, nextItem, folderOverride), itemState, analysis, override ->
                MediaStateInfo(
                    mediaId = id,
                    item = item,
                    itemState = itemState,
                    analysis = analysis,
                    itemSttOverride = override,
                    folderSttOverride = folderOverride,
                    nextMediaId = nextItem?.id,
                    nextMediaTitle = nextItem?.let { it.title.ifBlank { it.sortableFileName() } },
                )
            }
        }
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
            folderSttOverride = mediaState.folderSttOverride,
            nextMediaId = mediaState.nextMediaId,
            nextMediaTitle = mediaState.nextMediaTitle,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    init {
        // ファイル単位の「文字起こし」トグル（既定は OFF、ただしバックグラウンド文字起こし等で既に有効な場合は引き継ぐ）と開いている教材・チャンク読み込みを同期する
        viewModelScope.launch {
            combine(
                currentMediaId,
                perMediaLookaheadEnabled,
                currentAnalysisSnapshot
                    .map { (it.chunks.firstOrNull()?.id) to isLookaheadActivelyRunningInRepo(it.lookaheadStatus) }
                    .distinctUntilChanged(),
            ) { mediaId, enabledMap, (firstChunkId, repoActivelyRunning) ->
                val explicit = if (mediaId != null) enabledMap[mediaId] else null
                val enabled = explicit ?: repoActivelyRunning
                val shouldSync = explicit != null || repoActivelyRunning
                Quad(mediaId, shouldSync, enabled, if (enabled) firstChunkId else null)
            }.distinctUntilChanged().collect { (mediaId, shouldSync, enabled, _) ->
                if (mediaId != null && shouldSync) {
                    if (enabled && perMediaLookaheadEnabled.value[mediaId] != true) {
                        perMediaLookaheadEnabled.update { it + (mediaId to true) }
                        if (lastOpenedMediaId == mediaId) {
                            localUiState.update { it.copy(isLookaheadEnabled = true) }
                        }
                    }
                    syncLookaheadState(mediaId, enabled)
                }
            }
        }

        // 言語判定が実行中になった教材を記録する
        viewModelScope.launch {
            combine(
                currentMediaId,
                currentAnalysisSnapshot.map { it.lookaheadStatus.isLanguageDetectionRunning }.distinctUntilChanged(),
            ) { mediaId, isLidRunning ->
                mediaId to isLidRunning
            }.collect { (mediaId, isLidRunning) ->
                if (mediaId != null && isLidRunning) {
                    languageDetectionObservedRunningMediaIds.update { it + mediaId }
                }
            }
        }

        // 自動文字起こしがオフのとき、言語判定が完了したら「文字起こし」バッジに 5 秒間ツールチップを表示する
        viewModelScope.launch {
            combine(
                currentMediaId,
                perMediaLookaheadEnabled,
                languageDetectionObservedRunningMediaIds,
                userSettingsRepository.settings.map { it.autoStartTranscriptionOnOpen }.distinctUntilChanged(),
                currentAnalysisSnapshot,
            ) { mediaId, perMediaMap, observedRunningSet, autoStartOnOpen, snapshot ->
                val isAnalysisIdle = snapshot.chunks.isNotEmpty() &&
                    snapshot.status[AnalysisStage.FEATURES] !is StageStatus.Running &&
                    snapshot.status[AnalysisStage.SEGMENTATION] !is StageStatus.Running &&
                    !snapshot.lookaheadStatus.isLanguageDetectionRunning
                val isLookaheadActive = (mediaId != null && perMediaMap[mediaId] == true) ||
                    isLookaheadActivelyRunningInRepo(snapshot.lookaheadStatus)
                val shouldTrigger = mediaId != null &&
                    lastOpenedMediaId == mediaId &&
                    !autoStartOnOpen &&
                    mediaId in observedRunningSet &&
                    mediaId !in shownTranscriptionBadgeTooltipMediaIds &&
                    isAnalysisIdle &&
                    !isLookaheadActive
                mediaId to shouldTrigger
            }.collect { (mediaId, shouldTrigger) ->
                if (mediaId != null && shouldTrigger && mediaId !in shownTranscriptionBadgeTooltipMediaIds) {
                    shownTranscriptionBadgeTooltipMediaIds.add(mediaId)
                    localUiState.update { it.copy(isTranscriptionBadgeTooltipVisible = true) }
                    transcriptionBadgeTooltipJob?.cancel()
                    transcriptionBadgeTooltipJob = viewModelScope.launch {
                        delay(TRANSCRIPTION_BADGE_TOOLTIP_DURATION_MS)
                        localUiState.update { it.copy(isTranscriptionBadgeTooltipVisible = false) }
                    }
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

        // ファイルの末尾まで再生し終えたとき、「次のファイルに進む」または「シャッフル」なら自動的に次のファイル（なければフォルダ一覧）へ遷移する
        viewModelScope.launch {
            playbackController.status
                .map { it.mediaId to it.hasReachedFileEnd }
                .distinctUntilChanged()
                .collect { (endedMediaId, hasReachedFileEnd) ->
                    if (!hasReachedFileEnd || endedMediaId == null || endedMediaId != currentMediaId.value) {
                        return@collect
                    }
                    val action = playbackController.practice.value.fileEndAction
                    if (action != FileEndAction.NEXT_FILE && action != FileEndAction.SHUFFLE) {
                        return@collect
                    }
                    val state = uiState.value
                    val nextId = state.nextMediaId ?: resolveNextMediaIdImmediate(endedMediaId, action)
                    if (nextId != null) {
                        open(
                            mediaId = nextId,
                            initialChunkId = null,
                            playlistMediaIds = playlistMediaIdsFlow.value,
                            autoPlayFromStart = true,
                        )
                        localUiState.update { it.copy(pendingOpenMediaId = nextId) }
                    } else {
                        val course = state.course ?: libraryRepository.observeItem(endedMediaId).firstOrNull()?.course
                        val folderPath = normalizedFolderPath(course).orEmpty()
                        localUiState.update { it.copy(returnToLibraryFolderPath = folderPath) }
                    }
                }
        }
    }

    private suspend fun resolveNextMediaIdImmediate(
        currentId: MediaId,
        action: FileEndAction,
    ): MediaId? {
        val item = libraryRepository.observeItem(currentId).firstOrNull() ?: return null
        val allItems = libraryRepository.observeItems().firstOrNull().orEmpty()
        val candidates = resolveOrderedCandidateItems(
            current = item,
            allItems = allItems,
            playlistMediaIds = playlistMediaIdsFlow.value,
        )
        return if (action == FileEndAction.SHUFFLE) {
            resolveNextShuffleCandidate(
                currentId = currentId,
                candidates = candidates,
                cachedNextId = nextShuffledMediaIdFlow.value,
            )?.id
        } else {
            val idx = candidates.indexOfFirst { it.id == currentId }
            if (idx >= 0) candidates.getOrNull(idx + 1)?.id else null
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
                val explicitSetting = perMediaMap[mediaId]
                if (explicitSetting == false) return@combine null
                if (explicitSetting == null && !autoStartOnOpen) return@combine null
                if (mediaId !in prioritizedSet) return@combine null
                if (snapshot.chunks.isEmpty()) return@combine null
                if (snapshot.status[AnalysisStage.FEATURES] is StageStatus.Running) return@combine null
                if (snapshot.status[AnalysisStage.SEGMENTATION] is StageStatus.Running) return@combine null
                if (snapshot.lookaheadStatus.isLanguageDetectionRunning) return@combine null
                if (
                    explicitSetting == true &&
                    (
                        snapshot.lookaheadStatus.inFlightChunkId != null ||
                            snapshot.lookaheadStatus.queuedChunkIds.isNotEmpty()
                        )
                ) {
                    return@combine null
                }
                val hasUntranscribedChunk = snapshot.chunks.any { chunk ->
                    !chunk.isSkipped &&
                        chunk.id !in snapshot.lookaheadStatus.skippedEmptyChunkIds &&
                        snapshot.transcripts[chunk.id]?.text.isNullOrBlank()
                }
                if (!hasUntranscribedChunk) return@combine null
                mediaId to (explicitSetting == true)
            }
                .distinctUntilChanged()
                .collect { readyPair ->
                    val readyMediaId = readyPair?.first ?: return@collect
                    val alreadyExplicit = readyPair.second
                    if (!alreadyExplicit && !perMediaLookaheadEnabled.value.containsKey(readyMediaId)) {
                        perMediaLookaheadEnabled.update { it + (readyMediaId to true) }
                        if (lastOpenedMediaId == readyMediaId) {
                            localUiState.update { it.copy(isLookaheadEnabled = true) }
                            syncLookaheadState(readyMediaId, true)
                        }
                    } else if (
                        alreadyExplicit &&
                        lastOpenedMediaId == readyMediaId &&
                        lookaheadLoopJob?.isActive != true
                    ) {
                        localUiState.update { it.copy(isLookaheadEnabled = true) }
                        syncLookaheadState(readyMediaId, true)
                    }
                }
        }
    }

    /**
     * 指定した教材を開いて再生準備を行う。
     * [initialChunkId] が指定された場合は該当チャンクの先頭へ一時停止状態で移動する。
     * [playlistMediaIds] が指定された場合は、再生開始前にライブラリの「ファイル」一覧に表示されていた順序として保持する。
     */
    fun open(
        mediaId: MediaId,
        initialChunkId: ChunkId? = null,
        playlistMediaIds: List<MediaId> = emptyList(),
        autoPlayFromStart: Boolean = false,
    ) {
        if (playlistMediaIds.isNotEmpty() && playlistMediaIds != playlistMediaIdsFlow.value) {
            playlistMediaIdsFlow.value = playlistMediaIds
            playedShuffleMediaIds.clear()
        }
        playedShuffleMediaIds.add(mediaId)
        val previousId = lastOpenedMediaId
        if (previousId != mediaId) {
            nextShuffledMediaIdFlow.value = null
        }
        if (previousId != null && previousId != mediaId) {
            val prevState = uiState.value
            if (prevState.mediaId == previousId && prevState.chunks.isNotEmpty()) {
                if (prevState.isWaveformRenderComplete || !lastRenderedChunksByMedia.containsKey(previousId)) {
                    lastRenderedChunksByMedia[previousId] = prevState.chunks
                }
                if (!prevState.isWaveformRenderComplete) {
                    completedOverviewSignaturesByMedia.remove(previousId)
                    completedZoomedSignaturesByMedia.remove(previousId)
                }
            }
            analysisRepository.setMediaTranscriptionSessionOpen(previousId, false)
        }
        analysisRepository.setMediaTranscriptionSessionOpen(mediaId, true)
        currentMediaId.value = mediaId
        if (previousId == mediaId && initialChunkId == null && !autoPlayFromStart) {
            val currentState = uiState.value
            val currentSig = computeWaveformRenderSignature(currentState.waveform, currentState.chunks)
            val savedOverviewSig = completedOverviewSignaturesByMedia[mediaId]
            val savedZoomedSig = completedZoomedSignaturesByMedia[mediaId]
            if (
                !currentState.isWaveformRenderComplete ||
                savedOverviewSig == null ||
                savedZoomedSig == null ||
                savedOverviewSig != currentSig ||
                savedZoomedSig != currentSig
            ) {
                val nextGen = nextWaveformRenderGeneration++
                localUiState.update { state ->
                    state.copy(
                        completedOverviewSignature = savedOverviewSig,
                        completedZoomedSignature = savedZoomedSig,
                        baseWaveformChunks = lastRenderedChunksByMedia[mediaId].orEmpty(),
                        waveformRenderGeneration = nextGen,
                    )
                }
            }
            if (lookaheadLoopJob?.isActive != true) {
                val explicit = perMediaLookaheadEnabled.value[mediaId]
                val repoActivelyRunning = isLookaheadActivelyRunningInRepo(currentState.lookaheadStatus)
                val enabled = explicit ?: repoActivelyRunning
                if (explicit != null || enabled) {
                    if (enabled && explicit != true) {
                        perMediaLookaheadEnabled.update { it + (mediaId to true) }
                        localUiState.update { it.copy(isLookaheadEnabled = true) }
                    }
                    syncLookaheadState(mediaId, enabled)
                }
            }
            viewModelScope.launch {
                try {
                    analysisRepository.prioritizeOpenedMediaAnalysis(mediaId)
                } finally {
                    prioritizedMediaIds.update { it + mediaId }
                }
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
        transcriptionBadgeTooltipJob?.cancel()
        transcriptionBadgeTooltipJob = null
        prioritizedMediaIds.update { it - mediaId }
        val explicitEnabled = perMediaLookaheadEnabled.value[mediaId]
        val nextGen = nextWaveformRenderGeneration++
        val prevSnapshot = localUiState.value
        localUiState.value = LocalUiSnapshot(
            isLookaheadEnabled = explicitEnabled ?: false,
            languageModelInfos = prevSnapshot.languageModelInfos,
            hasRecordAudioPermission = prevSnapshot.hasRecordAudioPermission,
            completedOverviewSignature = completedOverviewSignaturesByMedia[mediaId],
            completedZoomedSignature = completedZoomedSignaturesByMedia[mediaId],
            baseWaveformChunks = lastRenderedChunksByMedia[mediaId].orEmpty(),
            waveformRenderGeneration = nextGen,
        )
        if (explicitEnabled != null) {
            syncLookaheadState(mediaId, explicitEnabled)
        } else {
            viewModelScope.launch {
                val currentLookahead = analysisRepository.observeLookaheadStatus(mediaId).firstOrNull()
                if (currentLookahead != null && !isLookaheadActivelyRunningInRepo(currentLookahead)) {
                    val autoStart = userSettingsRepository.settings.firstOrNull()?.autoStartTranscriptionOnOpen ?: true
                    if (!autoStart && perMediaLookaheadEnabled.value[mediaId] == null) {
                        analysisRepository.setLookaheadEnabled(mediaId, false)
                    }
                }
            }
        }
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
                            hasReachedFileEnd = false,
                        )
                    }
                }
            } else if (autoPlayFromStart) {
                libraryRepository.updatePlaybackPosition(
                    id = mediaId,
                    positionMs = 0L,
                    speed = initialSpeed,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                playbackController.open(mediaId)
                playbackController.player.value?.let { player ->
                    player.seekTo(0L)
                    player.play()
                }
                if (playbackController is FakePlaybackController) {
                    playbackController.position.positionMs = 0L
                    playbackController.setSpeed(initialSpeed)
                    playbackController.statusState.update { status ->
                        status.copy(
                            mediaId = mediaId,
                            isPlaying = true,
                            currentChunkId = null,
                            autoPauseEndsAtMs = null,
                            hasReachedFileEnd = false,
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
            val item = libraryRepository.observeItem(mediaId).firstOrNull()
            val pair = item?.languagePair
            val queryLangs = buildDetailedQueryLanguages(pair, pair?.target)
            val queried = runCatching { analysisRepository.queryLanguageModelInfos(queryLangs) }.getOrNull().orEmpty()
            if (queried.isNotEmpty()) {
                val infoMap = queried.associateBy { it.language }
                localUiState.update { state ->
                    state.copy(languageModelInfos = state.languageModelInfos + infoMap)
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
        val currentState = uiState.value
        if (targetId != null && currentState.mediaId == targetId && currentState.chunks.isNotEmpty()) {
            if (currentState.isWaveformRenderComplete || !lastRenderedChunksByMedia.containsKey(targetId)) {
                lastRenderedChunksByMedia[targetId] = currentState.chunks
            }
            if (!currentState.isWaveformRenderComplete) {
                completedOverviewSignaturesByMedia.remove(targetId)
                completedZoomedSignaturesByMedia.remove(targetId)
            }
        }
        val isStillCurrentMedia = targetId == null || currentMediaId.value == targetId
        if (isStillCurrentMedia) {
            lookaheadLoopJob?.cancel()
            lookaheadLoopJob = null
            stopTranslationLoop()
            stopDetailedChunkPreview(pausePlayer = false)
            chunkSeekPrioritizeJob?.cancel()
            chunkSeekPrioritizeJob = null
        }
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

            is PlayerEvent.SetBaseLanguageSpeedEnabled -> {
                viewModelScope.launch {
                    userSettingsRepository.setBaseLanguageSpeedEnabled(event.enabled)
                }
            }

            is PlayerEvent.SetBaseLanguageSpeed -> {
                val rounded = roundSpeed(event.speed)
                analyticsTracker.logPlaybackSpeedChange(speed = rounded, isBaseLanguageSpeed = true)
                viewModelScope.launch {
                    userSettingsRepository.setBaseLanguageSpeed(rounded)
                }
            }

            PlayerEvent.ToggleChunkBoundaryEditMode -> {
                val selectedIds = playbackController.practice.value.repeatChunkIds
                if (selectedIds.size == 1 && playbackController.practice.value.loop !is LoopMode.AbRange) {
                    localUiState.update { it.copy(isChunkBoundaryEditMode = !it.isChunkBoundaryEditMode) }
                } else {
                    localUiState.update { it.copy(isChunkBoundaryEditMode = false) }
                }
            }

            is PlayerEvent.NudgeSelectedChunkBoundary -> {
                val id = resolveActiveMediaId() ?: return
                val targetChunk = uiState.value.chunks.filter { it.isRepeatChecked }.singleOrNull() ?: return
                val chunks = uiState.value.chunks
                val targetIndex = chunks.indexOfFirst { it.id == targetChunk.id }
                if (targetIndex < 0) return
                val durationMs = uiState.value.durationMs.takeIf { it > 0L } ?: chunks.lastOrNull()?.endMs ?: return
                val minChunkLengthMs = 100L
                val prevChunk = chunks.getOrNull(targetIndex - 1)
                val nextChunk = chunks.getOrNull(targetIndex + 1)
                val minAllowedStartMs = when {
                    prevChunk == null -> 0L
                    prevChunk.endMs == targetChunk.startMs -> prevChunk.startMs + minChunkLengthMs
                    else -> prevChunk.endMs
                }
                val maxAllowedEndMs = when {
                    nextChunk == null -> durationMs
                    nextChunk.startMs == targetChunk.endMs -> (nextChunk.endMs - minChunkLengthMs).coerceAtMost(durationMs)
                    else -> nextChunk.startMs
                }
                val newStartMs: Long
                val newEndMs: Long
                if (event.isStart) {
                    val maxStart = (targetChunk.endMs - minChunkLengthMs).coerceAtLeast(minAllowedStartMs)
                    newStartMs = (targetChunk.startMs + event.deltaMs).coerceIn(minAllowedStartMs, maxStart)
                    newEndMs = targetChunk.endMs
                } else {
                    val minEnd = (targetChunk.startMs + minChunkLengthMs).coerceAtMost(maxAllowedEndMs)
                    newStartMs = targetChunk.startMs
                    newEndMs = (targetChunk.endMs + event.deltaMs).coerceIn(minEnd, maxAllowedEndMs)
                }
                if (newStartMs != targetChunk.startMs || newEndMs != targetChunk.endMs) {
                    moveChunkBoundaryPreservingSelection(
                        mediaId = id,
                        chunkId = targetChunk.id,
                        newStartMs = newStartMs,
                        newEndMs = newEndMs,
                    )
                }
            }

            is PlayerEvent.SetLoopMode -> {
                if (event.loopMode is LoopMode.AbRange || event.loopMode is LoopMode.Off) {
                    localUiState.update { it.copy(isChunkBoundaryEditMode = false) }
                }
                when (val mode = event.loopMode) {
                    is LoopMode.SingleChunk -> analyticsTracker.logRepeatCountChange(mode.times ?: -1)
                    is LoopMode.ChunkRange -> analyticsTracker.logRepeatCountChange(mode.times ?: -1)
                    is LoopMode.AbRange -> analyticsTracker.logAbRepeatSet(chunkSpan = 1)
                    LoopMode.Off -> Unit
                }
                updatePracticeWithCheckedFallback {
                    val nextRepeatWholeFile = if (event.loopMode is LoopMode.Off) false else it.repeatWholeFile
                    it.copy(
                        loop = event.loopMode,
                        repeatChunkIds = if (event.loopMode is LoopMode.Off) emptySet() else it.repeatChunkIds,
                        repeatCheckedOnly = if (event.loopMode is LoopMode.Off) false else it.repeatCheckedOnly,
                        temporaryRepeatChunkId = if (event.loopMode is LoopMode.Off) null else it.temporaryRepeatChunkId,
                        repeatWholeFile = nextRepeatWholeFile,
                        fileEndAction = if (event.loopMode is LoopMode.Off && it.fileEndAction == FileEndAction.REPEAT_FILE) {
                            FileEndAction.STOP_AFTER_FILE
                        } else {
                            it.fileEndAction
                        },
                    )
                }
            }

            PlayerEvent.EnableWholeFileRepeat -> {
                analyticsTracker.logFileEndActionChange(FileEndAction.REPEAT_FILE.name.lowercase(Locale.ROOT))
                playbackController.updatePractice { current ->
                    current.copy(
                        repeatWholeFile = true,
                        fileEndAction = FileEndAction.REPEAT_FILE,
                    )
                }
            }

            is PlayerEvent.SetFileEndAction -> {
                analyticsTracker.logFileEndActionChange(event.action.name.lowercase(Locale.ROOT))
                if (event.action == FileEndAction.SHUFFLE) {
                    playedShuffleMediaIds.clear()
                    resolveActiveMediaId()?.let { playedShuffleMediaIds.add(it) }
                    nextShuffledMediaIdFlow.value = null
                }
                playbackController.updatePractice { current ->
                    current.copy(
                        fileEndAction = event.action,
                        repeatWholeFile = event.action == FileEndAction.REPEAT_FILE,
                    )
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
                        current.copy(
                            repeatWholeFile = false,
                            fileEndAction = if (current.fileEndAction == FileEndAction.REPEAT_FILE) {
                                FileEndAction.STOP_AFTER_FILE
                            } else {
                                current.fileEndAction
                            },
                        )
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
                val modeName = when (event.autoPause) {
                    AutoPause.Off -> "off"
                    is AutoPause.Fixed -> "fixed"
                    is AutoPause.Proportional -> "proportional"
                    is AutoPause.OriginalSilence -> "original_silence"
                }
                val nextScope = if (event.autoPause is AutoPause.Off) {
                    AutoPauseScope.ALL_CHUNKS
                } else {
                    playbackController.practice.value.autoPauseScope
                }
                analyticsTracker.logAutoPauseChange(
                    mode = modeName,
                    scope = nextScope.name.lowercase(Locale.ROOT),
                )
                playbackController.updatePractice { current ->
                    val resolvedScope = if (event.autoPause is AutoPause.Off) {
                        AutoPauseScope.ALL_CHUNKS
                    } else {
                        current.autoPauseScope
                    }
                    current.copy(autoPause = event.autoPause, autoPauseScope = resolvedScope)
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
                        val modeName = when (nextAutoPause) {
                            AutoPause.Off -> "off"
                            is AutoPause.Fixed -> "fixed"
                            is AutoPause.Proportional -> "proportional"
                            is AutoPause.OriginalSilence -> "original_silence"
                        }
                        analyticsTracker.logAutoPauseChange(
                            mode = modeName,
                            scope = event.scope.name.lowercase(Locale.ROOT),
                        )
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
                            analyticsTracker.logAutoPauseChange(
                                mode = when (nextAutoPause) {
                                    AutoPause.Off -> "off"
                                    is AutoPause.Fixed -> "fixed"
                                    is AutoPause.Proportional -> "proportional"
                                    is AutoPause.OriginalSilence -> "original_silence"
                                },
                                scope = AutoPauseScope.AFTER_BASE_LANGUAGE.name.lowercase(Locale.ROOT),
                            )
                            current.copy(
                                autoPause = nextAutoPause,
                                autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
                            )
                        } else {
                            analyticsTracker.logAutoPauseChange(
                                mode = when (current.autoPause) {
                                    AutoPause.Off -> "off"
                                    is AutoPause.Fixed -> "fixed"
                                    is AutoPause.Proportional -> "proportional"
                                    is AutoPause.OriginalSilence -> "original_silence"
                                },
                                scope = AutoPauseScope.ALL_CHUNKS.name.lowercase(Locale.ROOT),
                            )
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
                analyticsTracker.logLanguageFilterChange(event.filter.name.lowercase(Locale.ROOT))
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
                val targetChunk = uiState.value.chunks.firstOrNull { it.id == event.chunkId }
                if (targetChunk?.isSkipped == true) return
                viewModelScope.launch {
                    val added = analysisRepository.toggleChunkBookmark(id, event.chunkId)
                    if (!added) {
                        val remainingOtherBookmarks = uiState.value.chunks.any {
                            it.id != event.chunkId && it.isBookmarked && !it.isSkipped
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
                localUiState.update { it.copy(isChunkBoundaryEditMode = false) }
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
                localUiState.update { it.copy(isChunkBoundaryEditMode = false) }
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
                localUiState.update { it.copy(isChunkBoundaryEditMode = false) }
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
                            analyticsTracker.logChunkBoundaryEdit("merge")
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
                            analyticsTracker.logChunkBoundaryEdit("merge")
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
                analyticsTracker.logBlindModeToggle(event.blindMode != BlindMode.OFF)
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
                val pair = uiState.value.languagePair
                analyticsTracker.logTranslationToggle(
                    enabled = event.enabled,
                    sourceLanguage = pair?.target?.value.orEmpty(),
                    targetLanguage = pair?.base?.value.orEmpty(),
                )
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
                analyticsTracker.logChunkBoundaryEdit("resegment")
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
                val targetChunk = uiState.value.chunks.firstOrNull { it.id == event.chunkId }
                val chunkLang = targetChunk?.language?.value
                    ?: uiState.value.languagePair?.target?.value
                    ?: "unknown"
                analyticsTracker.logChunkDetailTranscription(
                    language = chunkLang,
                    engine = "default",
                )
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
                analyticsTracker.logChunkBoundaryEdit("merge")
                viewModelScope.launch {
                    analysisRepository.mergeWithNextChunk(id, event.chunkId)
                }
            }

            is PlayerEvent.MoveChunkBoundary -> {
                val id = resolveActiveMediaId() ?: return
                moveChunkBoundaryPreservingSelection(
                    mediaId = id,
                    chunkId = event.chunkId,
                    newStartMs = event.newStartMs,
                    newEndMs = event.newEndMs,
                )
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
                            state.copy(
                                languageOverrides = state.languageOverrides - event.chunkId,
                                isChunkBoundaryEditMode = false,
                            )
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
                } else {
                    val pair = uiState.value.languagePair
                    val targetLang = pair?.target?.value.orEmpty()
                    val baseLang = pair?.base?.value ?: "none"
                    val effectiveOverride = uiState.value.itemSttOverride.withFolderFallback(uiState.value.folderSttOverride)
                    val targetEngine = effectiveOverride.targetEngine?.name?.lowercase(Locale.ROOT) ?: "default"
                    val baseEngine = effectiveOverride.baseEngine?.name?.lowercase(Locale.ROOT) ?: "default"
                    analyticsTracker.logFullTranscriptionStart(
                        targetLanguage = targetLang,
                        baseLanguage = baseLang,
                        targetEngine = targetEngine,
                        baseEngine = baseEngine,
                    )
                    analyticsTracker.logLookaheadTranscription(
                        language = targetLang,
                        engine = targetEngine,
                    )
                }
                transcriptionBadgeTooltipJob?.cancel()
                transcriptionBadgeTooltipJob = null
                localUiState.update {
                    it.copy(
                        isLookaheadEnabled = event.enabled,
                        isTranscriptionProgressDismissed = false,
                        isTranscriptionBadgeTooltipVisible = false,
                    )
                }
                perMediaLookaheadEnabled.update { it + (id to event.enabled) }
                syncLookaheadState(id, event.enabled)
            }

            PlayerEvent.DismissTranscriptionBadgeTooltip -> {
                transcriptionBadgeTooltipJob?.cancel()
                transcriptionBadgeTooltipJob = null
                localUiState.update { it.copy(isTranscriptionBadgeTooltipVisible = false) }
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
                            val rawItemOverride = itemSttOverrideRepository.get(mediaId)
                                .takeIf { it != com.miuraken.chunkflow.core.model.ItemSttOverride.NONE }
                                ?: uiState.value.itemSttOverride
                            // ファイル個別設定 ＞ フォルダ設定 ＞ 全体設定
                            val itemOverride = rawItemOverride.withFolderFallback(uiState.value.folderSttOverride)
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
                analyticsTracker.logSttModelDownload(
                    modelId = "recognition_default",
                    language = event.language.value,
                )
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
                    if (exported) {
                        analyticsTracker.logTranscriptExport("chunkflow_json")
                    }
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
                    if (success) {
                        analyticsTracker.logTranscriptExport(event.format.name.lowercase(Locale.ROOT))
                    }
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
                    val success = libraryRepository.resetTranscriptsAndDeleteFiles(id)
                    analysisRepository.clearTranscriptsForMedia(id)
                    analysisRepository.startLanguageDetectionOnly(id)
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
                            val nextEngine = if (
                                current.engine == fallbackDialog.engine &&
                                current.languageOverride == fallbackDialog.languageOverride &&
                                !current.isRunning &&
                                !current.hasAttempted
                            ) {
                                withInfos.selectDefaultEngineForLanguage(lang)
                            } else {
                                current.engine
                            }
                            state.copy(
                                languageModelInfos = mergedInfos,
                                detailedTranscriptionDialog = withInfos.copy(
                                    engine = nextEngine,
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
                analyticsTracker.logSttModelDownload(
                    modelId = sttEngine.name.lowercase(Locale.ROOT),
                    language = event.language.value,
                )
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

            PlayerEvent.ClearItemSttOverride -> {
                val mediaId = uiState.value.mediaId ?: return
                viewModelScope.launch {
                    itemSttOverrideRepository.set(mediaId, com.miuraken.chunkflow.core.model.ItemSttOverride.NONE)
                }
            }

            is PlayerEvent.RequestItemSttModelDownload -> {
                analyticsTracker.logSttModelDownload(
                    modelId = event.engine.name.lowercase(Locale.ROOT),
                    language = event.language.value,
                )
                val normLang = LanguageSettings.normalize(event.language)
                val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(event.language.value))
                localUiState.update { state ->
                    val existingInfo = state.languageModelInfos[event.language]
                        ?: state.languageModelInfos[normLang]
                        ?: state.languageModelInfos[mgmtLang]
                        ?: OnDeviceLanguageModelInfo(language = event.language)
                    val downloadingInfo = updateModelInfoStatus(
                        info = existingInfo,
                        engine = event.engine,
                        status = OnDeviceLanguageModelStatus.DOWNLOADING,
                    )
                    val nextMap = state.languageModelInfos +
                        (event.language to downloadingInfo) +
                        (normLang to downloadingInfo.copy(language = normLang)) +
                        (mgmtLang to downloadingInfo.copy(language = mgmtLang))
                    state.copy(languageModelInfos = nextMap)
                }
                viewModelScope.launch {
                    val ok = analysisRepository.requestSttModelDownload(event.language, event.engine)
                    val queryLanguages = buildDetailedQueryLanguages(uiState.value.languagePair, event.language)
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val queriedMap = queried.associateBy { it.language }.toMutableMap()
                    if (!ok) {
                        val existing = queriedMap[event.language]
                            ?: queriedMap[normLang]
                            ?: queriedMap[mgmtLang]
                            ?: OnDeviceLanguageModelInfo(language = event.language)
                        val failedInfo = updateModelInfoStatus(
                            info = existing,
                            engine = event.engine,
                            status = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED,
                        )
                        queriedMap[event.language] = failedInfo
                        queriedMap[normLang] = failedInfo.copy(language = normLang)
                        queriedMap[mgmtLang] = failedInfo.copy(language = mgmtLang)
                    }
                    localUiState.update { state ->
                        state.copy(languageModelInfos = state.languageModelInfos + queriedMap)
                    }
                }
            }

            is PlayerEvent.DeleteItemSherpaModel -> {
                val normLang = LanguageSettings.normalize(event.language)
                val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(event.language.value))
                viewModelScope.launch {
                    analysisRepository.deleteSherpaModel(event.language)
                    val mediaId = uiState.value.mediaId
                    if (mediaId != null) {
                        val pair = uiState.value.languagePair
                        val currentOverride = itemSttOverrideRepository.get(mediaId)
                        val nextTarget = if (pair != null &&
                            matchesLanguageRole(event.language, pair.target) &&
                            currentOverride.targetEngine == SttEngine.SHERPA_ONNX
                        ) {
                            null
                        } else {
                            currentOverride.targetEngine
                        }
                        val nextBase = if (pair?.base != null &&
                            matchesLanguageRole(event.language, pair.base) &&
                            currentOverride.baseEngine == SttEngine.SHERPA_ONNX
                        ) {
                            null
                        } else {
                            currentOverride.baseEngine
                        }
                        if (nextTarget != currentOverride.targetEngine || nextBase != currentOverride.baseEngine) {
                            itemSttOverrideRepository.set(
                                mediaId,
                                currentOverride.copy(targetEngine = nextTarget, baseEngine = nextBase),
                            )
                        }
                    }
                    val queryLanguages = buildDetailedQueryLanguages(uiState.value.languagePair, event.language)
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val queriedMap = queried.associateBy { it.language }.toMutableMap()
                    for (langKey in listOf(event.language, normLang, mgmtLang)) {
                        val existing = queriedMap[langKey]
                            ?: localUiState.value.languageModelInfos[langKey]
                            ?: OnDeviceLanguageModelInfo(language = langKey)
                        queriedMap[langKey] = existing.copy(
                            sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                            canDeleteSherpaModel = false,
                        )
                    }
                    localUiState.update { state ->
                        state.copy(languageModelInfos = state.languageModelInfos + queriedMap)
                    }
                }
            }

            is PlayerEvent.DeleteItemLanguageSttModel -> {
                val normLang = LanguageSettings.normalize(event.language)
                val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(event.language.value))
                viewModelScope.launch {
                    analysisRepository.deleteSttModelForLanguage(event.language, event.model)
                    val mediaId = uiState.value.mediaId
                    if (mediaId != null) {
                        val pair = uiState.value.languagePair
                        val currentOverride = itemSttOverrideRepository.get(mediaId)
                        val nextTarget = if (pair != null &&
                            matchesLanguageRole(event.language, pair.target) &&
                            currentOverride.targetEngine == event.model.sttEngine
                        ) {
                            null
                        } else {
                            currentOverride.targetEngine
                        }
                        val nextBase = if (pair?.base != null &&
                            matchesLanguageRole(event.language, pair.base) &&
                            currentOverride.baseEngine == event.model.sttEngine
                        ) {
                            null
                        } else {
                            currentOverride.baseEngine
                        }
                        if (nextTarget != currentOverride.targetEngine || nextBase != currentOverride.baseEngine) {
                            itemSttOverrideRepository.set(
                                mediaId,
                                currentOverride.copy(targetEngine = nextTarget, baseEngine = nextBase),
                            )
                        }
                    }
                    val queryLanguages = buildDetailedQueryLanguages(uiState.value.languagePair, event.language)
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val queriedMap = queried.associateBy { it.language }.toMutableMap()
                    for (langKey in listOf(event.language, normLang, mgmtLang)) {
                        val existing = queriedMap[langKey]
                            ?: localUiState.value.languageModelInfos[langKey]
                            ?: OnDeviceLanguageModelInfo(language = langKey)
                        queriedMap[langKey] = existing.copy(
                            extraEngineStatuses = existing.extraEngineStatuses +
                                (event.model.sttEngine to OnDeviceLanguageModelStatus.NOT_INSTALLED),
                        )
                    }
                    localUiState.update { state ->
                        state.copy(languageModelInfos = state.languageModelInfos + queriedMap)
                    }
                }
            }

            is PlayerEvent.PurgeItemSttModel -> {
                viewModelScope.launch {
                    analysisRepository.purgeSttModel(event.model)
                    val mediaId = uiState.value.mediaId
                    if (mediaId != null) {
                        val currentOverride = itemSttOverrideRepository.get(mediaId)
                        val nextTarget = if (currentOverride.targetEngine == event.model.sttEngine) null else currentOverride.targetEngine
                        val nextBase = if (currentOverride.baseEngine == event.model.sttEngine) null else currentOverride.baseEngine
                        if (nextTarget != currentOverride.targetEngine || nextBase != currentOverride.baseEngine) {
                            itemSttOverrideRepository.set(
                                mediaId,
                                currentOverride.copy(targetEngine = nextTarget, baseEngine = nextBase),
                            )
                        }
                    }
                    val queryLanguages = buildDetailedQueryLanguages(
                        uiState.value.languagePair,
                        uiState.value.languagePair?.target,
                    )
                    val queried = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    val queriedMap = queried.associateBy { it.language }.toMutableMap()
                    val updatedInfos = (localUiState.value.languageModelInfos + queriedMap).mapValues { (_, info) ->
                        if (event.model.isLanguageSupported(info.language)) {
                            updateModelInfoStatus(
                                info = info,
                                engine = event.model.sttEngine,
                                status = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                            )
                        } else {
                            info
                        }
                    }
                    localUiState.update { state ->
                        state.copy(languageModelInfos = updatedInfos)
                    }
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
                analyticsTracker.logChunkDetailTranscription(
                    language = resolvedLanguage.value,
                    engine = event.engine.name.lowercase(Locale.ROOT),
                )
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
                                trialResult?.errorMessage?.takeIf { it.isNotBlank() } ?: run {
                                    val modelStatus = currentDialog.engineStatus(resolvedLanguage, event.engine)
                                    resolveDetailedTrialEmptyMessage(
                                        targetLanguage = resolvedLanguage,
                                        engine = event.engine,
                                        modelStatus = modelStatus,
                                        hasRecordAudioPermission = event.hasRecordAudioPermission,
                                    )
                                }
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

            PlayerEvent.SeekToPreviousBookmarkedChunk -> seekToBookmarkedChunk(direction = -1)

            PlayerEvent.SeekToNextBookmarkedChunk -> seekToBookmarkedChunk(direction = 1)

            PlayerEvent.OpenNextMediaItem -> {
                val nextId = uiState.value.nextMediaId ?: return
                open(
                    mediaId = nextId,
                    initialChunkId = null,
                    playlistMediaIds = playlistMediaIdsFlow.value,
                    autoPlayFromStart = playbackController.status.value.isPlaying,
                )
            }

            PlayerEvent.ConsumePendingOpenMedia -> {
                localUiState.update { it.copy(pendingOpenMediaId = null) }
            }

            PlayerEvent.ConsumeReturnToLibraryFolder -> {
                localUiState.update { it.copy(returnToLibraryFolderPath = null) }
            }

            PlayerEvent.MarkOverviewWaveformRenderComplete -> {
                val state = uiState.value
                val id = state.mediaId ?: currentMediaId.value ?: return
                val sig = computeWaveformRenderSignature(state.waveform, state.chunks) ?: return
                completedOverviewSignaturesByMedia[id] = sig
                if (completedZoomedSignaturesByMedia[id] == sig && state.chunks.isNotEmpty()) {
                    lastRenderedChunksByMedia[id] = state.chunks
                }
                localUiState.update { current ->
                    val bothDone = current.completedZoomedSignature == sig
                    current.copy(
                        completedOverviewSignature = sig,
                        baseWaveformChunks = if (bothDone) state.chunks else current.baseWaveformChunks,
                    )
                }
            }

            PlayerEvent.MarkZoomedWaveformRenderComplete -> {
                val state = uiState.value
                val id = state.mediaId ?: currentMediaId.value ?: return
                val sig = computeWaveformRenderSignature(state.waveform, state.chunks) ?: return
                completedZoomedSignaturesByMedia[id] = sig
                if (completedOverviewSignaturesByMedia[id] == sig && state.chunks.isNotEmpty()) {
                    lastRenderedChunksByMedia[id] = state.chunks
                }
                localUiState.update { current ->
                    val bothDone = current.completedOverviewSignature == sig
                    current.copy(
                        completedZoomedSignature = sig,
                        baseWaveformChunks = if (bothDone) state.chunks else current.baseWaveformChunks,
                    )
                }
            }

            PlayerEvent.ConfirmRecommendedSttModelDownload -> {
                val dialog = localUiState.value.recommendedSttModelDialog ?: return
                if (dialog.isDownloading) return
                analyticsTracker.logSttModelDownload(
                    modelId = dialog.engine.name.lowercase(Locale.ROOT),
                    language = dialog.language.value,
                )
                localUiState.update { current ->
                    current.copy(
                        recommendedSttModelDialog = current.recommendedSttModelDialog?.copy(isDownloading = true),
                    )
                }
                viewModelScope.launch {
                    val downloaded = analysisRepository.requestSttModelDownload(dialog.language, dialog.engine)
                    if (downloaded) {
                        userSettingsRepository.setSttEngineForLanguage(dialog.language, dialog.engine)
                    }
                    val queryLanguages = buildDetailedQueryLanguages(
                        languagePair = uiState.value.languagePair,
                        extraLanguage = dialog.language,
                    )
                    val refreshedInfos = analysisRepository.queryLanguageModelInfos(queryLanguages)
                    localUiState.update { current ->
                        current.copy(
                            recommendedSttModelDialog = null,
                            languageModelInfos = current.languageModelInfos + refreshedInfos.associateBy { it.language },
                        )
                    }
                }
            }

            PlayerEvent.DismissRecommendedSttModelDownload -> {
                localUiState.update { current ->
                    current.copy(recommendedSttModelDialog = null)
                }
            }

            PlayerEvent.Back -> close()
        }
    }

    override fun onCleared() {
        close()
    }

    private suspend fun maybePromptRecommendedSttModelOnRecognitionStart(mediaId: MediaId) {
        val pair = uiState.value.languagePair
            ?: libraryRepository.observeItem(mediaId).firstOrNull()?.languagePair
            ?: return
        val settings = userSettingsRepository.settings.firstOrNull()
        val candidates = listOfNotNull(pair.target, pair.base).distinct()
        for (lang in candidates) {
            val recommended = SttEngineSelection.recommendedDownloadEngineForLanguage(lang) ?: continue
            val normCode = SupportedLanguageCatalog.normalizeToModelManagementCode(lang.value)
            if (normCode in promptedRecommendedModelLanguages) continue
            val normalizedTag = LanguageSettings.normalize(lang)
            if (settings != null &&
                (settings.sttEngineByLanguage[normalizedTag] != null || settings.sttEngineByLanguage[LanguageTag(normCode)] != null)
            ) {
                continue
            }
            val queryTags = listOf(lang, LanguageTag(normCode)).distinct()
            val infos = analysisRepository.queryLanguageModelInfos(queryTags)
            val info = infos.firstOrNull()
            val status = info?.statusOf(recommended)
            if (status == OnDeviceLanguageModelStatus.NOT_INSTALLED) {
                promptedRecommendedModelLanguages.add(normCode)
                localUiState.update { current ->
                    if (current.recommendedSttModelDialog != null) {
                        current
                    } else {
                        current.copy(
                            recommendedSttModelDialog = RecommendedSttModelDialogState(
                                language = LanguageTag(normCode),
                                engine = recommended,
                            ),
                        )
                    }
                }
                break
            }
        }
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
        else -> info.copy(extraEngineStatuses = info.extraEngineStatuses + (engine to status))
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

            SttEngine.SHERPA_ONNX_SENSE_VOICE,
            SttEngine.SHERPA_ONNX_WHISPER_BASE,
            SttEngine.SHERPA_ONNX_WHISPER_SMALL,
            SttEngine.MOONSHINE_TINY,
            SttEngine.MOONSHINE_BASE,
            ->
                enriched.statusOf(engine) != OnDeviceLanguageModelStatus.UNSUPPORTED

            SttEngine.OPENAI_API,
            SttEngine.GROQ_API,
            SttEngine.GEMINI_API,
            ->
                enriched.statusOf(engine) == OnDeviceLanguageModelStatus.INSTALLED
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
                    } else {
                        analyticsTracker.logTranslationRun(
                            sourceLanguage = pair.target.value,
                            targetLanguage = baseLang.value,
                            chunkCount = 1,
                        )
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
                if (split) {
                    analyticsTracker.logChunkBoundaryEdit("split")
                }
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

    private fun isChunkUndetected(
        chunk: ChunkUi,
        skippedEmptyChunkIds: Set<ChunkId> = uiState.value.lookaheadStatus.skippedEmptyChunkIds,
    ): Boolean = !chunk.isSkipped &&
        chunk.id !in skippedEmptyChunkIds &&
        (
            chunk.text.isNullOrBlank() ||
                chunk.language == null ||
                chunk.language.value.isBlank() ||
                chunk.language.value.equals("und", ignoreCase = true)
            )

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
                maybePromptRecommendedSttModelOnRecognitionStart(mediaId)
                val requestedStartIds = mutableSetOf<ChunkId?>()
                val initialChunks = if (uiState.value.mediaId == mediaId) uiState.value.chunks else emptyList()
                val statusNow = playbackController.status.value
                val validCurrentChunkId = statusNow.currentChunkId?.takeIf { candidateId ->
                    statusNow.mediaId == mediaId &&
                        (initialChunks.isEmpty() || initialChunks.any { it.id == candidateId })
                }
                val initialChunkId = validCurrentChunkId
                    ?: initialChunks.firstOrNull { isChunkUndetected(it) }?.id
                    ?: initialChunks.firstOrNull()?.id
                    ?: run {
                        val repoChunks = analysisRepository.observeChunks(mediaId).firstOrNull().orEmpty()
                        val repoTranscripts = analysisRepository.observeTranscripts(mediaId).firstOrNull().orEmpty()
                        val repoSkippedEmpty = analysisRepository.observeLookaheadStatus(mediaId)
                            .firstOrNull()?.skippedEmptyChunkIds.orEmpty()
                        statusNow.currentChunkId?.takeIf { candidateId ->
                            repoChunks.any { it.id == candidateId }
                        } ?: repoChunks.firstOrNull { chunk ->
                            if (chunk.isSkipped || chunk.id in repoSkippedEmpty) {
                                false
                            } else {
                                val transcript = repoTranscripts[chunk.id]
                                transcript?.text.isNullOrBlank() ||
                                    transcript.language == null ||
                                    transcript.language?.value.isNullOrBlank() ||
                                    transcript.language?.value.equals("und", ignoreCase = true)
                            }
                        }?.id ?: repoChunks.firstOrNull { !it.isSkipped && it.id !in repoSkippedEmpty }?.id
                    }
                if (initialChunkId != null) {
                    requestedStartIds.add(initialChunkId)
                    analysisRepository.triggerLookaheadAt(mediaId, initialChunkId)
                }

                while (isActive) {
                    val currentState = uiState.value
                    if (
                        !currentState.lookaheadStatus.enabled ||
                        currentState.mediaId != mediaId ||
                        currentState.lookaheadStatus.isLanguageDetectionRunning ||
                        currentState.analysis[AnalysisStage.FEATURES] is StageStatus.Running ||
                        currentState.analysis[AnalysisStage.SEGMENTATION] is StageStatus.Running
                    ) {
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
                    if (
                        !refreshedState.lookaheadStatus.enabled ||
                        refreshedState.mediaId != mediaId ||
                        refreshedState.lookaheadStatus.isLanguageDetectionRunning ||
                        refreshedState.analysis[AnalysisStage.FEATURES] is StageStatus.Running ||
                        refreshedState.analysis[AnalysisStage.SEGMENTATION] is StageStatus.Running
                    ) {
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
        if (!isCurrentlyPlaying) {
            val pair = uiState.value.languagePair
            analyticsTracker.logPlaybackStart(
                targetLanguage = pair?.target?.value.orEmpty(),
                baseLanguage = pair?.base?.value ?: "none",
            )
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
            ?: uiState.value.chunks.firstOrNull { !it.isSkipped }
            ?: uiState.value.chunks.firstOrNull()
        if (playbackController is FakePlaybackController) {
            playbackController.position.positionMs = clampedMs
            playbackController.statusState.update {
                it.copy(
                    currentChunkId = matchingChunk?.id,
                    autoPauseEndsAtMs = null,
                )
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
        seekTo(0L)
    }

    private fun moveChunkBoundaryPreservingSelection(
        mediaId: MediaId,
        chunkId: ChunkId,
        newStartMs: Long,
        newEndMs: Long,
    ) {
        analyticsTracker.logChunkBoundaryEdit("adjust")
        viewModelScope.launch {
            withContext(NonCancellable) {
                val currentChunks = uiState.value.chunks
                val currentPractice = playbackController.practice.value
                val currentRepeatIds = currentPractice.repeatChunkIds
                val selectedIndices = if (currentRepeatIds.isNotEmpty()) {
                    currentChunks.mapIndexedNotNull { idx, c ->
                        idx.takeIf { c.id in currentRepeatIds }
                    }.toSet()
                } else {
                    emptySet()
                }
                val tempSelectedIndex = currentPractice.temporaryRepeatChunkId?.let { tempId ->
                    currentChunks.indexOfFirst { it.id == tempId }.takeIf { it >= 0 }
                }
                analysisRepository.moveChunkBoundary(
                    id = mediaId,
                    chunkId = chunkId,
                    newStartMs = newStartMs,
                    newEndMs = newEndMs,
                )
                if (selectedIndices.isNotEmpty()) {
                    val updatedChunks = analysisRepository.observeChunks(mediaId).first()
                    val preservedIds = selectedIndices.mapNotNull { idx ->
                        updatedChunks.getOrNull(idx)?.id
                    }.toSet()
                    val preservedTempId = tempSelectedIndex?.let { idx ->
                        updatedChunks.getOrNull(idx)?.id
                    }?.takeIf { it in preservedIds }
                    updatePracticeWithCheckedFallback { current ->
                        current.copy(
                            repeatChunkIds = preservedIds,
                            temporaryRepeatChunkId = preservedTempId,
                        )
                    }
                }
            }
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
        val target = if (direction < 0) {
            val endExclusive = currentIdx.coerceIn(0, chunks.size)
            chunks.subList(0, endExclusive).lastOrNull { chunk ->
                chunk.isRepeatChecked && !chunk.isSkipped
            }
        } else {
            val startInclusive = (currentIdx + 1).coerceIn(0, chunks.size)
            chunks.subList(startInclusive, chunks.size).firstOrNull { chunk ->
                chunk.isRepeatChecked && !chunk.isSkipped
            }
        }
        if (target != null) {
            seekToChunk(target.id)
        }
    }

    private fun seekToBookmarkedChunk(direction: Int) {
        requestAutoFollowResume()
        val chunks = uiState.value.chunks
        if (chunks.isEmpty()) return
        val posMs = playbackController.position.currentPositionMs()
        val currentIdx = uiState.value.activeChunkIndex
            ?: chunks.indexOfFirst { posMs in it.startMs..it.endMs }.takeIf { it >= 0 }
            ?: if (direction < 0) chunks.size else -1
        val target = if (direction < 0) {
            val endExclusive = currentIdx.coerceIn(0, chunks.size)
            chunks.subList(0, endExclusive).lastOrNull { chunk ->
                chunk.isBookmarked && !chunk.isSkipped
            }
        } else {
            val startInclusive = (currentIdx + 1).coerceIn(0, chunks.size)
            chunks.subList(startInclusive, chunks.size).firstOrNull { chunk ->
                chunk.isBookmarked && !chunk.isSkipped
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
        analyticsTracker.logPlaybackSpeedChange(speed = rounded, isBaseLanguageSpeed = false)
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
        analyticsTracker.logPlaybackSpeedChange(speed = rounded, isBaseLanguageSpeed = false)
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
        folderSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        nextMediaId: MediaId? = null,
        nextMediaTitle: String? = null,
    ): PlayerUiState {
        val requestedMediaId = currentMediaId.value
        val resolvedMediaId = if (requestedMediaId != null) {
            mediaId?.takeIf { it == requestedMediaId }
        } else {
            mediaId ?: status.mediaId ?: item?.id
        }
        val isStatusForOtherMedia = status.mediaId != null &&
            (
                (requestedMediaId != null && status.mediaId != requestedMediaId) ||
                    (resolvedMediaId != null && status.mediaId != resolvedMediaId)
                )
        val effectiveStatusDurationMs = if (isStatusForOtherMedia) 0L else status.durationMs
        val effectiveCurrentChunkId = if (isStatusForOtherMedia) null else status.currentChunkId
        val effectiveIsPlaying = if (isStatusForOtherMedia) false else status.isPlaying
        val effectiveAutoPauseEndsAtMs = if (isStatusForOtherMedia) null else status.autoPauseEndsAtMs
        val effectiveError = if (isStatusForOtherMedia) null else status.error

        val resolvedDurationMs = when {
            effectiveStatusDurationMs > 0L -> effectiveStatusDurationMs
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
        val activeIndex = effectiveCurrentChunkId?.let { activeId ->
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
        val effectiveLookaheadEnabled = if (mediaId != null && perMediaLookaheadEnabled.value.containsKey(mediaId)) {
            local.isLookaheadEnabled && analysis.lookaheadStatus.enabled
        } else {
            isLookaheadActivelyRunningInRepo(analysis.lookaheadStatus)
        }
        val effectiveLookaheadStatus = analysis.lookaheadStatus.copy(
            enabled = effectiveLookaheadEnabled,
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

        val currentWaveformSignature = computeWaveformRenderSignature(analysis.waveform, chunkUis)
        val isWaveformBuilding = analysis.status[AnalysisStage.FEATURES] is StageStatus.Running ||
            analysis.status[AnalysisStage.SEGMENTATION] is StageStatus.Running ||
            effectiveLookaheadStatus.isLanguageDetectionRunning
        val isOverviewWaveformRenderComplete = currentWaveformSignature != null &&
            !isWaveformBuilding &&
            local.completedOverviewSignature == currentWaveformSignature
        val isZoomedWaveformRenderComplete = currentWaveformSignature != null &&
            !isWaveformBuilding &&
            local.completedZoomedSignature == currentWaveformSignature
        val candidateBaseChunks = local.baseWaveformChunks.ifEmpty {
            resolvedMediaId?.let { lastRenderedChunksByMedia[it] }.orEmpty()
        }
        val candidateBaseSignature = computeWaveformRenderSignature(analysis.waveform, candidateBaseChunks)
        val resolvedBaseWaveformChunks = if (
            candidateBaseChunks.isNotEmpty() &&
            candidateBaseSignature != currentWaveformSignature
        ) {
            candidateBaseChunks
        } else {
            chunkUis.map { it.copy(language = null, text = null) }
        }

        val isChunkBoundaryEditMode = local.isChunkBoundaryEditMode &&
            practice.repeatChunkIds.size == 1 &&
            practice.loop !is LoopMode.AbRange

        return PlayerUiState(
            mediaId = resolvedMediaId,
            title = item?.title.orEmpty(),
            course = item?.course,
            languagePair = item?.languagePair,
            isPlaying = effectiveIsPlaying,
            durationMs = resolvedDurationMs,
            speed = status.speed,
            customSpeed = resolvedCustomSpeed,
            baseLanguageSpeedEnabled = settings.baseLanguageSpeedEnabled,
            baseLanguageSpeed = settings.baseLanguageSpeed,
            seekBackMs = settings.seekBackMs,
            seekForwardMs = settings.seekForwardMs,
            practice = practice,
            autoPauseEndsAtMs = effectiveAutoPauseEndsAtMs,
            waveform = analysis.waveform,
            chunks = chunkUis,
            bookmarks = analysis.bookmarks,
            activeChunkIndex = activeIndex,
            analysis = analysis.status,
            error = effectiveError,
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
            recommendedSttModelDialog = local.recommendedSttModelDialog,
            itemSttOverride = itemSttOverride,
            folderSttOverride = folderSttOverride,
            sttEngineByLanguage = settings.sttEngineByLanguage,
            languageModelInfos = local.languageModelInfos,
            enabledLanguages = settings.enabledLanguages.toList(),
            nextMediaId = nextMediaId,
            nextMediaTitle = nextMediaTitle,
            backgroundSpeechRecognizerWarning = local.backgroundSpeechRecognizerWarning,
            isLanguageFilterFallbackHighlighted = local.isLanguageFilterFallbackHighlighted,
            isTranscriptionBadgeTooltipVisible = local.isTranscriptionBadgeTooltipVisible,
            autoFollowResetToken = local.autoFollowResetToken,
            isOverviewWaveformRenderComplete = isOverviewWaveformRenderComplete,
            isZoomedWaveformRenderComplete = isZoomedWaveformRenderComplete,
            baseWaveformChunks = resolvedBaseWaveformChunks,
            waveformRenderGeneration = local.waveformRenderGeneration,
            isChunkBoundaryEditMode = isChunkBoundaryEditMode,
            pendingOpenMediaId = local.pendingOpenMediaId,
            returnToLibraryFolderPath = local.returnToLibraryFolderPath,
        )
    }

    private fun computeWaveformRenderSignature(
        waveform: Waveform?,
        chunks: List<ChunkUi>,
    ): WaveformRenderSignature? {
        if (waveform == null || chunks.isEmpty()) return null
        return WaveformRenderSignature(
            binCount = waveform.peak.size,
            durationMs = waveform.durationMs,
            chunkKeys = chunks.map { chunk ->
                ChunkVisualKey(
                    id = chunk.id,
                    startMs = chunk.startMs,
                    endMs = chunk.endMs,
                    language = chunk.language?.value?.trim()?.lowercase(Locale.ROOT),
                    isSkipped = chunk.isSkipped,
                    isTranscribed = !chunk.text.isNullOrBlank(),
                )
            },
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

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
