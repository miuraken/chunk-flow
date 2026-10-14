package com.miuraken.chunkflow.core.model

/**
 * 中国語（`zh`）・広東語（`yue`）の表示に使う漢字の字体（§7.4.3、§7.5.5、F9.1）。
 *
 * @property iso15924 [Transcript.textVariants] のキーに対応する ISO 15924 コード。
 */
enum class ChineseScript(val iso15924: String) {
    /** 簡体字（中国語 `zh` の既定）。 */
    HANS("Hans"),

    /** 繁体字（広東語 `yue` の既定）。 */
    HANT("Hant"),
}

/**
 * アプリ全体の設定（§2 F9.1、§6.4 DataStore）。
 *
 * @property defaultLanguagePair 新しく取り込む教材の既定の言語ペア。
 * @property chineseScript 中国語（普通話、`zh`）の表示字体（既定は簡体字 [ChineseScript.HANS]）。
 * @property cantoneseScript 広東語（`yue`）の表示字体（既定は繁体字 [ChineseScript.HANT]）。
 * @property defaultSpeed 既定の再生速度（0.5..2.0）。
 * @property seekBackMs 巻き戻しのミリ秒数（正の値、既定 3,000ms）。
 * @property seekForwardMs 早送りのミリ秒数（正の値、既定 5,000ms）。
 */
data class UserSettings(
    val defaultLanguagePair: LanguagePair = LanguagePair(
        target = LanguageTag("en"),
        base = LanguageTag("ja"),
    ),
    val chineseScript: ChineseScript = ChineseScript.HANS,
    val cantoneseScript: ChineseScript = ChineseScript.HANT,
    val defaultSpeed: Float = 1.0f,
    val seekBackMs: Long = DEFAULT_SEEK_BACK_MS,
    val seekForwardMs: Long = DEFAULT_SEEK_FORWARD_MS,
) {
    init {
        require(defaultSpeed in ItemState.MIN_SPEED..ItemState.MAX_SPEED) {
            "defaultSpeed must be in ${ItemState.MIN_SPEED}..${ItemState.MAX_SPEED}: $defaultSpeed"
        }
        require(seekBackMs > 0L) { "seekBackMs must be > 0: $seekBackMs" }
        require(seekForwardMs > 0L) { "seekForwardMs must be > 0: $seekForwardMs" }
    }

    companion object {
        /** 巻き戻しの既定値（3 秒、F3.3）。 */
        const val DEFAULT_SEEK_BACK_MS: Long = 3_000L

        /** 早送りの既定値（5 秒、F3.3）。 */
        const val DEFAULT_SEEK_FORWARD_MS: Long = 5_000L
    }
}
