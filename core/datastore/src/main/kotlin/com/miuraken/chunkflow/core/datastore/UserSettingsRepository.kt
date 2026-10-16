package com.miuraken.chunkflow.core.datastore

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * アプリ設定（[UserSettings]）の単一の情報源（§2 F9.1、§6.4 DataStore）。
 *
 * ViewModel や再生・取り込み処理はこのインターフェースに依存し、テストでは `FakeUserSettingsRepository` を使う。
 */
interface UserSettingsRepository {
    /** 現在のアプリ設定を購読する。 */
    val settings: Flow<UserSettings>

    /** 新しく取り込む教材の既定の言語ペアを更新する。 */
    suspend fun setDefaultLanguagePair(pair: LanguagePair)

    /** 中国語（普通話、`zh`）の表示字体を更新する。 */
    suspend fun setChineseScript(script: ChineseScript)

    /** 広東語（`yue`）の表示字体を更新する。 */
    suspend fun setCantoneseScript(script: ChineseScript)

    /** 既定の再生速度（0.5..2.0）を更新する。 */
    suspend fun setDefaultSpeed(speed: Float)

    /** 巻き戻しのミリ秒数（正の値）を更新する。 */
    suspend fun setSeekBackMs(seekBackMs: Long)

    /** 早送りのミリ秒数（正の値）を更新する。 */
    suspend fun setSeekForwardMs(seekForwardMs: Long)

    /** 既定の自動ポーズ設定を更新する（F6.3、F9.1）。 */
    suspend fun setDefaultAutoPause(autoPause: AutoPause) {}

    /** 既定の自動ポーズ対象言語範囲を更新する（F6.3、F6.5、F9.1）。 */
    suspend fun setDefaultAutoPauseScope(scope: AutoPauseScope) {}

    /** 端末内の自動チャンク分割の既定パラメータを更新する（§7.3.3、F5.2、F9.1）。 */
    suspend fun setDefaultSegmentationParams(params: SegmentationParams) {}

    /** 設定画面やダイアログの言語選択肢として表示する言語のセットを更新する。 */
    suspend fun setEnabledLanguages(languages: Set<LanguageTag>) = Unit

    /** 対象言語にアルファベットによる読み方（ローマ字・Pinyin・IAST 等）を付加して表示するかどうかを更新する。 */
    suspend fun setShowTargetRomanization(enabled: Boolean) = Unit

    /** 先読み文字起こしの有効・無効を更新する（既定は true）。 */
    suspend fun setLookaheadTranscriptionEnabled(enabled: Boolean) = Unit

    /** 端末内文字起こしで使用する音声認識ライブラリ（エンジン）を更新する。 */
    suspend fun setTranscriptionEngine(engine: OnDeviceTranscriptionEngine) = Unit
}
