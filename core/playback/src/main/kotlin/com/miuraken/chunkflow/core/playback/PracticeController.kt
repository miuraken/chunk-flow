package com.miuraken.chunkflow.core.playback

import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 学習モードの設定（[PracticeSettings]）を保持・更新するコントローラ（§7.2.1、§7.2.5）。
 *
 * 同一プロセス内の ViewModel・[PlaybackController]・[PlaybackService] で共有する。
 */
@Singleton
class PracticeController @Inject constructor() {
    private val _settings = MutableStateFlow(PracticeSettings())

    /** 現在の学習モードの設定。 */
    val settings: StateFlow<PracticeSettings> = _settings.asStateFlow()

    /** 学習モードの設定を [transform] で更新する。 */
    fun update(transform: (PracticeSettings) -> PracticeSettings) {
        _settings.update(transform)
    }

    /** 学習モードの設定を置き換える。 */
    fun setSettings(newSettings: PracticeSettings) {
        _settings.value = newSettings
    }

    /** リピート（[LoopMode]）を解除する（§7.2.5: レジューム再開時はループを解除する）。 */
    fun resetLoop() {
        _settings.update { it.copy(loop = LoopMode.Off) }
    }
}
