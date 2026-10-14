package com.miuraken.chunkflow.core.datastore

import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * アプリ設定（[UserSettings]）の単一の情報源（§2 F9.1、§6.4 DataStore）。
 *
 * ViewModel や 재생・取り込み処理はこのインターフェースに依存し、テストでは `FakeUserSettingsRepository` を使う。
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
}
