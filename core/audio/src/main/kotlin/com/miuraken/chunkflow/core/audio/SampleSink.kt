package com.miuraken.chunkflow.core.audio

/** ストリーミング処理でサンプルを受け取る側。[samples] の内容は呼び出しの間だけ有効（呼び出し側が再利用する）。 */
fun interface SampleSink {
    /** [samples] の先頭 [count] 個を受け取る。 */
    fun onSamples(samples: FloatArray, count: Int)
}
