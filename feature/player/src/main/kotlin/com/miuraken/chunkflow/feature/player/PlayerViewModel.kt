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
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
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
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    fun resolveDisplayText(
        chineseScript: ChineseScript,
        cantoneseScript: ChineseScript,
    ): String? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        return when {
            PhoneticGuideKind.isMandarinChinese(language) ->
                textVariants[chineseScript.iso15924]
                    ?: transliterateChineseScript(raw, chineseScript)
                    ?: raw

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
            PhoneticGuideKind.isMandarinChinese(language) -> "zh-${chineseScript.iso15924}"
            PhoneticGuideKind.isCantonese(language) -> "yue-${cantoneseScript.iso15924}"
            else -> rawTag
        }
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
    val chunkSource: ChunkOrigin = ChunkOrigin.AUTO,
    val availableChunkOrigins: Set<ChunkOrigin> = emptySet(),
    val segmentationParams: SegmentationParams = SegmentationParams(),
    val transcriptImportBanner: TranscriptImportBannerState? = null,
    val transcriptExportBanner: TranscriptExportBannerState? = null,
    val transcriptOffsetMs: Int = 0,
    val lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
    val localTranscriptExported: Boolean = false,
) {
    /** 文字起こしテキストを持つチャンクが 1 件以上あるかどうか。 */
    val hasTranscript: Boolean
        get() = chunks.any { it.text != null }

    /** 学習言語に対する読み方ガイド種別（ラテン文字言語の場合は `null`）。 */
    val targetPhoneticGuideKind: PhoneticGuideKind?
        get() = PhoneticGuideKind.forLanguage(languagePair?.target)

    /** 言語ラベルが未判定（`null` または `"und"`）の文字起こしチャンクが存在するかどうか（F1.4、F7.3）。 */
    val hasUnknownLanguageChunks: Boolean
        get() = chunks.any { chunk ->
            chunk.text != null &&
                (chunk.language == null || chunk.language.value.isBlank() || chunk.language.value == "und")
        }

    companion object {
        /** 再生速度の微調整の刻み幅（0.05 倍、F3.2）。 */
        const val SPEED_STEP: Float = 0.05f

        /** 再生速度のプリセット一覧（F3.2）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
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

    /** リピートモードを変更する（F6.1、F6.2）。 */
    data class SetLoopMode(val loopMode: LoopMode) : PlayerEvent

    /** 自動ポーズの設定を変更する（F6.3）。 */
    data class SetAutoPause(val autoPause: AutoPause) : PlayerEvent

    /** 自動ポーズの範囲（すべてのチャンク / 母語チャンクの後のみ）を変更する（F6.5）。 */
    data class SetAutoPauseScope(val scope: AutoPauseScope) : PlayerEvent

    /** 瞬間作文モード（母語チャンクの後に自動ポーズ）の ON / OFF を切り替える（F6.5）。 */
    data object ToggleInstantComposition : PlayerEvent

    /** 言語フィルタ再生（すべて / 学習言語のみ / 母語のみ）を変更する（F6.4）。 */
    data class SetLanguageFilter(val filter: LanguageFilter) : PlayerEvent

    /** ブックマークのみ再生フィルタ（`PracticeSettings.bookmarksOnly`）の ON / OFF を切り替える（F8.1）。 */
    data object ToggleBookmarksOnly : PlayerEvent

    /** 指定したチャンクのブックマークを追加または解除する（F8.1）。 */
    data class ToggleBookmark(val chunkId: ChunkId) : PlayerEvent

    /** 指定したチャンクの連続無限リピートチェック（`PracticeSettings.repeatChunkIds`）をトグルする（F6.1）。 */
    data class ToggleChunkRepeat(val chunkId: ChunkId) : PlayerEvent

    /** ブラインドモードを変更する（F7.2）。 */
    data class SetBlindMode(val blindMode: BlindMode) : PlayerEvent

    /** ブラインドモード中に特定のチャンクの一時表示を切り替える（F7.2）。 */
    data class ToggleRevealChunk(val chunkId: ChunkId) : PlayerEvent

    /** 学習言語の読み方ガイド（ローマ字 / IAST / Pinyin 等）の表示 ON / OFF を切り替える。 */
    data class SetShowTargetRomanization(val enabled: Boolean) : PlayerEvent

    /** チャンクの出どころ（文字起こし / 自動分割）を切り替える（F5.4）。 */
    data class SetChunkSource(val origin: ChunkOrigin) : PlayerEvent

    /** 分割パラメータを変更して再分割を実行する（F5.2）。 */
    data class Resegment(val params: SegmentationParams) : PlayerEvent

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

    /** 指定チャンク位置からの先読み簡易文字起こしを要求する。 */
    data class TriggerLookahead(val chunkId: ChunkId?) : PlayerEvent

    /** 文字起こしをローカル `.chunkflow.json` に書き出す（Drive 自動同期対象）。 */
    data object ExportLocalTranscriptJson : PlayerEvent

    /** 文字起こし・字幕ファイルを指定フォーマットで SAF の [uri] へ書き出す（F7.5）。 */
    data class ExportTranscriptToUri(val format: TranscriptExportFormat, val uri: Uri) : PlayerEvent

    /** 字幕書き出し通知バナーを閉じる。 */
    data object DismissExportBanner : PlayerEvent

    /** 文字起こし JSON / 字幕ファイルを取り込む（F1.3、F1.4、§7.5.5）。 */
    data class ImportTranscript(val uri: Uri) : PlayerEvent

    /** 文字起こし取り込み結果のバナーを閉じる。 */
    data object DismissTranscriptBanner : PlayerEvent
}

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
    val blindMode: BlindMode = BlindMode.OFF,
    val revealedChunkIds: Set<ChunkId> = emptySet(),
    val customSegmentationParams: SegmentationParams? = null,
    val transcriptImportBanner: TranscriptImportBannerState? = null,
    val transcriptExportBanner: TranscriptExportBannerState? = null,
    val transcriptOffsetMs: Int = 0,
    val localTranscriptExported: Boolean = false,
    val languageOverrides: Map<ChunkId, LanguageTag?> = emptyMap(),
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
    )

    private val currentMediaId = MutableStateFlow<MediaId?>(null)
    private val localUiState = MutableStateFlow(LocalUiSnapshot())
    private var lastOpenedMediaId: MediaId? = null
    private var lookaheadLoopJob: Job? = null

    /** 高頻度で変わる再生位置のプロバイダ（§6.2）。 */
    val positionProvider: PositionProvider
        get() = playbackController.position

    private val currentAudioItem = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItem(id)
    }

    private val currentItemState = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItemState(id)
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

    private val mediaAndAnalysisFlow = combine(
        currentMediaId,
        currentAudioItem,
        currentItemState,
        currentAnalysisSnapshot,
    ) { mediaId, item, itemState, analysis ->
        Quad(mediaId, item, itemState, analysis)
    }

    /** プレイヤー画面の UI 状態。 */
    val uiState: StateFlow<PlayerUiState> = combine(
        mediaAndAnalysisFlow,
        playbackController.status,
        playbackController.practice,
        userSettingsRepository.settings,
        localUiState,
    ) { (mediaId, item, itemState, analysis), status, practice, settings, local ->
        buildUiState(
            mediaId = mediaId,
            item = item,
            itemState = itemState,
            analysis = analysis,
            status = status,
            practice = practice,
            settings = settings,
            local = local,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    init {
        // 設定の先読み文字起こし ON/OFF と開いている教材・チャンク読み込みを同期する
        viewModelScope.launch {
            combine(
                currentMediaId,
                userSettingsRepository.settings
                    .map { it.lookaheadTranscriptionEnabled }
                    .distinctUntilChanged(),
                currentAnalysisSnapshot
                    .map { it.chunks.firstOrNull()?.id }
                    .distinctUntilChanged(),
            ) { mediaId, enabled, firstChunkId ->
                Triple(mediaId, enabled, firstChunkId)
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
            }.distinctUntilChanged().collect { (mediaId, activeChunkId, isPlaying) ->
                if (mediaId != null) {
                    if (activeChunkId != null) {
                        analysisRepository.triggerLookaheadAt(mediaId, activeChunkId)
                    } else if (isPlaying && uiState.value.lookaheadStatus.enabled) {
                        val targetId = uiState.value.chunks.firstOrNull { isChunkUndetected(it) }?.id
                            ?: uiState.value.chunks.firstOrNull()?.id
                        analysisRepository.triggerLookaheadAt(mediaId, targetId)
                    }
                }
            }
        }
    }

    /** 指定した教材を開いて再生準備を行う。 */
    fun open(mediaId: MediaId) {
        currentMediaId.value = mediaId
        if (lastOpenedMediaId == mediaId) return
        lastOpenedMediaId = mediaId
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
        localUiState.value = LocalUiSnapshot()
        viewModelScope.launch {
            val savedState = libraryRepository.observeItemState(mediaId).firstOrNull()
            playbackController.open(mediaId)
            if (playbackController is FakePlaybackController && savedState != null) {
                playbackController.position.positionMs = savedState.positionMs
                playbackController.setSpeed(savedState.speed)
            }
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

            is PlayerEvent.SetLoopMode -> {
                playbackController.updatePractice {
                    it.copy(
                        loop = event.loopMode,
                        repeatChunkIds = if (event.loopMode is LoopMode.Off) emptySet() else it.repeatChunkIds,
                    )
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
                    analysisRepository.toggleChunkBookmark(id, event.chunkId)
                }
            }

            is PlayerEvent.ToggleChunkRepeat -> {
                playbackController.updatePractice { current ->
                    val nextIds = if (event.chunkId in current.repeatChunkIds) {
                        current.repeatChunkIds - event.chunkId
                    } else {
                        current.repeatChunkIds + event.chunkId
                    }
                    current.copy(repeatChunkIds = nextIds)
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

            is PlayerEvent.SetChunkSource -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.setChunkSource(id, event.origin)
                }
            }

            is PlayerEvent.Resegment -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { it.copy(customSegmentationParams = event.params) }
                viewModelScope.launch {
                    analysisRepository.resegment(id, event.params)
                }
            }

            is PlayerEvent.UpdateChunkLanguage -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { state ->
                    state.copy(languageOverrides = state.languageOverrides + (event.chunkId to event.language))
                }
                viewModelScope.launch {
                    analysisRepository.updateChunkLanguage(id, event.chunkId, event.language)
                    val hasTranscript =
                        analysisRepository.observeTranscripts(id).firstOrNull()?.containsKey(event.chunkId) == true
                    if (hasTranscript) {
                        localUiState.update { state ->
                            state.copy(languageOverrides = state.languageOverrides - event.chunkId)
                        }
                    }
                    if (uiState.value.lookaheadStatus.enabled) {
                        analysisRepository.triggerLookaheadAt(id, event.chunkId)
                    }
                    val exported = libraryRepository.exportLocalTranscriptJson(id)
                    if (exported) {
                        localUiState.update { it.copy(localTranscriptExported = true) }
                    }
                }
            }

            is PlayerEvent.RetranscribeChunk -> {
                val id = resolveActiveMediaId() ?: return
                localUiState.update { state ->
                    state.copy(languageOverrides = state.languageOverrides - event.chunkId)
                }
                viewModelScope.launch {
                    analysisRepository.retranscribeChunk(id, event.chunkId)
                    if (uiState.value.lookaheadStatus.enabled) {
                        analysisRepository.triggerLookaheadAt(id, event.chunkId)
                    }
                    val exported = libraryRepository.exportLocalTranscriptJson(id)
                    if (exported) {
                        localUiState.update { it.copy(localTranscriptExported = true) }
                    }
                }
            }

            is PlayerEvent.UpdateChunkText -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.updateChunkText(id, event.chunkId, event.text)
                    val exported = libraryRepository.exportLocalTranscriptJson(id)
                    if (exported) {
                        localUiState.update { it.copy(localTranscriptExported = true) }
                    }
                }
            }

            is PlayerEvent.SplitChunk -> {
                val id = resolveActiveMediaId() ?: return
                viewModelScope.launch {
                    analysisRepository.splitChunk(id, event.chunkId, event.splitPositionMs)
                }
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
                    analysisRepository.bulkSetUnknownTranscriptLanguage(id, event.language)
                    val exported = libraryRepository.exportLocalTranscriptJson(id)
                    if (exported) {
                        localUiState.update { it.copy(localTranscriptExported = true) }
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
                viewModelScope.launch {
                    userSettingsRepository.setLookaheadTranscriptionEnabled(event.enabled)
                }
                syncLookaheadState(id, event.enabled)
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
                viewModelScope.launch {
                    val result = libraryRepository.importTranscript(event.uri)
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

            PlayerEvent.DismissTranscriptBanner -> {
                localUiState.update { it.copy(transcriptImportBanner = null) }
            }

            PlayerEvent.Back -> Unit
        }
    }

    override fun onCleared() {
        lookaheadLoopJob?.cancel()
        lookaheadLoopJob = null
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

    private fun togglePlayPause() {
        val player = playbackController.player.value
        if (player != null) {
            if (playbackController.status.value.isPlaying || player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { it.copy(isPlaying = !it.isPlaying, autoPauseEndsAtMs = null) }
        }
    }

    private fun seekTo(targetPositionMs: Long) {
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
        if (playbackController is FakePlaybackController) {
            playbackController.position.positionMs = clampedMs
            val matchingChunk = uiState.value.chunks.firstOrNull { clampedMs in it.startMs..it.endMs }
            if (matchingChunk != null) {
                playbackController.statusState.update { it.copy(currentChunkId = matchingChunk.id) }
            }
        }
    }

    private fun seekToChunk(chunkId: ChunkId) {
        val wasPlaying = playbackController.status.value.isPlaying
        playbackController.seekToChunk(chunkId)
        if (playbackController is FakePlaybackController) {
            val targetChunk = uiState.value.chunks.firstOrNull { it.id == chunkId }
            if (targetChunk != null) {
                playbackController.position.positionMs = targetChunk.startMs
            }
            if (!wasPlaying) {
                playbackController.statusState.update {
                    it.copy(
                        isPlaying = true,
                        currentChunkId = chunkId,
                        autoPauseEndsAtMs = null,
                    )
                }
            }
        }
        val id = resolveActiveMediaId()
        if (id != null) {
            viewModelScope.launch {
                analysisRepository.triggerLookaheadAt(id, chunkId)
            }
        }
    }

    private fun seekToAdjacentChunk(direction: Int) {
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

    private fun setSpeed(rawSpeed: Float) {
        val clamped = rawSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val rounded = (clamped * 20f).roundToInt() / 20f
        playbackController.setSpeed(rounded.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED))
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
            val resolvedLanguage = if (chunk.id in local.languageOverrides) {
                local.languageOverrides[chunk.id]
            } else {
                transcript?.language
            }
            ChunkUi(
                id = chunk.id,
                startMs = chunk.startMs,
                endMs = chunk.endMs,
                text = transcript?.text?.takeIf { it.isNotBlank() },
                language = resolvedLanguage,
                isBookmarked = isBookmarked,
                isRepeatChecked = chunk.id in practice.repeatChunkIds,
                textVariants = transcript?.textVariants.orEmpty(),
                words = transcript?.words.orEmpty(),
                origin = chunk.origin,
            )
        }
        val activeIndex = status.currentChunkId?.let { activeId ->
            chunkUis.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
        }
        val resolvedOrigin = analysis.chunks.firstOrNull()?.origin
            ?: itemState?.chunkSource
            ?: ChunkOrigin.AUTO

        return PlayerUiState(
            mediaId = mediaId ?: status.mediaId ?: item?.id,
            title = item?.title.orEmpty(),
            course = item?.course,
            languagePair = item?.languagePair,
            isPlaying = status.isPlaying,
            durationMs = resolvedDurationMs,
            speed = status.speed,
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
            chineseScript = settings.chineseScript,
            cantoneseScript = settings.cantoneseScript,
            showTargetRomanization = settings.showTargetRomanization,
            chunkSource = resolvedOrigin,
            availableChunkOrigins = analysis.availableOrigins,
            segmentationParams = local.customSegmentationParams ?: settings.defaultSegmentationParams,
            transcriptImportBanner = local.transcriptImportBanner,
            transcriptExportBanner = local.transcriptExportBanner,
            transcriptOffsetMs = local.transcriptOffsetMs,
            lookaheadStatus = analysis.lookaheadStatus,
            localTranscriptExported = local.localTranscriptExported,
        )
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
