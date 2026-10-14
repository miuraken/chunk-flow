package com.miuraken.chunkflow.core.audio

/**
 * インターリーブされた PCM をモノラルにする（§7.3.1 の「モノラル化」）。
 *
 * 全チャンネルの単純平均を取り、-1.0..1.0 の Float にする。配列の確保はしない（呼び出し側が再利用する）。
 */
object PcmDownmix {
    private const val S16_SCALE = 1f / 32768f

    /**
     * 16bit 整数の PCM をモノラルの Float にする。
     *
     * @param src インターリーブされたサンプル（`frames * channels` 個以上）
     * @param frames フレーム数（1 フレーム = 全チャンネルの 1 サンプル）
     * @param channels チャンネル数（1 以上）
     * @param dst 書き込み先（`frames` 個以上）
     */
    fun downmixS16(src: ShortArray, frames: Int, channels: Int, dst: FloatArray) {
        require(channels >= 1) { "channels=$channels" }
        require(src.size >= frames * channels && dst.size >= frames)
        when (channels) {
            1 -> for (i in 0 until frames) dst[i] = src[i] * S16_SCALE

            2 -> {
                val scale = S16_SCALE * 0.5f
                var j = 0
                for (i in 0 until frames) {
                    dst[i] = (src[j] + src[j + 1]) * scale
                    j += 2
                }
            }

            else -> {
                val scale = S16_SCALE / channels
                var j = 0
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += src[j + c]
                    dst[i] = sum * scale
                    j += channels
                }
            }
        }
    }

    /**
     * Float の PCM（-1.0..1.0）をモノラルにする。
     *
     * @see downmixS16
     */
    fun downmixFloat(src: FloatArray, frames: Int, channels: Int, dst: FloatArray) {
        require(channels >= 1) { "channels=$channels" }
        require(src.size >= frames * channels && dst.size >= frames)
        if (channels == 1) {
            System.arraycopy(src, 0, dst, 0, frames)
            return
        }
        val scale = 1f / channels
        var j = 0
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += src[j + c]
            dst[i] = sum * scale
            j += channels
        }
    }
}
