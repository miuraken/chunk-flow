package com.miuraken.chunkflow.spike.audio

/**
 * クリック（振幅の大きな立ち上がり）の検出。モノラルのサンプルを順に受け取り、
 * [quietSamples] 以上 [threshold] 以下が続いた後で、初めて [threshold] を超えたサンプルの位置（累計の番号）を記録する。
 *
 * テスト音源（scripts/s3_audio.py）のクリックは振幅 0.6、発話の区間は 0.12 程度なので、閾値は 0.3 にする。
 */
class ClickDetector(private val threshold: Float = 0.3f, private val quietSamples: Int) {
    private var index = 0L
    private var quietRun = quietSamples.toLong() // 先頭は静かだったとみなす

    /** 検出したクリックの位置（このストリームのサンプルの累計の番号） */
    val onsets = ArrayList<Long>()

    /** [samples] の先頭 [count] 個を処理する。 */
    fun process(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val v = samples[i]
            val a = if (v < 0) -v else v
            if (a > threshold) {
                if (quietRun >= quietSamples) onsets.add(index + i)
                quietRun = 0
            } else {
                quietRun++
            }
        }
        index += count
    }

    /** 1 サンプル（16bit 整数のフレームの平均など）を処理する。 */
    fun processOne(v: Float) {
        val a = if (v < 0) -v else v
        if (a > threshold) {
            if (quietRun >= quietSamples) onsets.add(index)
            quietRun = 0
        } else {
            quietRun++
        }
        index++
    }

    /** 位置の累計を 0 に戻す（ExoPlayer の出力のフラッシュに合わせる）。検出済みの位置は残す。 */
    fun resetPosition() {
        index = 0
        quietRun = quietSamples.toLong()
    }
}
