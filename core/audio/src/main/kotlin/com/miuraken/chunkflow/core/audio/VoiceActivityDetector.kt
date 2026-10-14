package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.SegmentationParams

/**
 * 音声区間検出（VAD）のフレーム単位の出力（§7.3.3）。
 *
 * @property smoothedEnergyDb 平滑化後のフレームエネルギー（dBFS）。
 * @property thresholdDb 発話終了の判定に用いた適応閾値 T（dBFS。発話開始は `T + 3dB`）。
 * @property speechFrames 各フレームが発話区間であれば `true` の配列（[smoothedEnergyDb] と同じ長さ）。
 */
class VadResult(
    val smoothedEnergyDb: FloatArray,
    val thresholdDb: Float,
    val speechFrames: BooleanArray,
) {
    init {
        require(smoothedEnergyDb.size == speechFrames.size) {
            "smoothedEnergyDb.size (${smoothedEnergyDb.size}) != speechFrames.size (${speechFrames.size})"
        }
    }
}

/**
 * フレーム特徴量から発話フレームを検出する差し替え可能なインターフェース（§7.3.3、D6）。
 *
 * 既定の実装は [EnergyVoiceActivityDetector]（適応閾値 + ヒステリシス）。
 */
fun interface VoiceActivityDetector {
    /**
     * フレームごとの対数エネルギー [logEnergyDb]（dBFS）と分割パラメータ [params] から発話フレームを判定する。
     */
    fun detect(
        logEnergyDb: FloatArray,
        params: SegmentationParams,
    ): VadResult
}
