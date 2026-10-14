package com.miuraken.chunkflow.spike.playback.measure

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

/** `testdata/generate_testdata.py` が書く manifest.json の内容。 */
data class TestManifest(
    val dir: File,
    val sampleRate: Int,
    val durationMs: Long,
    val clickThreshold: Int,
    val clickHoldoffMs: Double,
    val gapBaseMs: Int,
    val gapStepMs: Int,
    val clicks: List<Click>,
    val files: List<AudioFile>,
    val loopFile: String,
    val loopAMs: Long,
    val loopBMs: Long,
) {
    /** クリックの真の時刻（生成した PCM に [ClickDetector] と同じ検出を当てた値）。 */
    data class Click(val index: Int, val truthMs: Double, val gapMs: Double)

    /** 計測対象のファイル。 */
    data class AudioFile(
        val name: String,
        val label: String,
        val isMp3: Boolean,
        /** true なら [IndexSeekDataSource] を通して IndexSeeker でシークさせる。 */
        val indexSeekDataSource: Boolean = false,
    )

    companion object {
        /** `dir/manifest.json` を読む。なければ null。 */
        fun load(dir: File): TestManifest? {
            val file = File(dir, "manifest.json")
            if (!file.isFile) return null
            val o = JSONObject(file.readText())
            val clicks = o.getJSONArray("clicks").let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Click(c.getInt("index"), c.getDouble("truthMs"), c.getDouble("gapMs"))
                }
            }
            val files = o.getJSONArray("files").let { a ->
                (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    AudioFile(f.getString("name"), f.getString("label"), f.getBoolean("mp3"))
                }
            }
            val loop = o.getJSONObject("loop")
            return TestManifest(
                dir = dir,
                sampleRate = o.getInt("sampleRate"),
                durationMs = o.getLong("durationMs"),
                clickThreshold = o.getInt("clickThreshold"),
                clickHoldoffMs = o.getDouble("clickHoldoffMs"),
                gapBaseMs = o.getInt("gapBaseMs"),
                gapStepMs = o.getInt("gapStepMs"),
                clicks = clicks,
                files = files,
                loopFile = loop.getString("file"),
                loopAMs = loop.getLong("aMs"),
                loopBMs = loop.getLong("bMs"),
            )
        }
    }
}

/**
 * 区間の PCM からクリック（2 つのバーストの組）を探す。
 *
 * 規則は生成スクリプトと同じ: 振幅が閾値を超えた最初のサンプルを 1 つ目の立ち上がりとし、
 * ホールドオフの後に再び閾値を超えたサンプルを 2 つ目とする。間隔からクリックの番号を求める。
 */
class ClickDetector(private val manifest: TestManifest) {
    /** 見つかったクリック。[believedMs] はプレイヤーが信じているメディア時刻。 */
    data class Found(val believedMs: Double, val gapMs: Double, val index: Int)

    /**
     * [pcm] の先頭のサンプルのメディア時刻を [firstPtsUs] として、完全に見つかったクリックを順に返す。
     * 2 つ目の立ち上がりの後に [tailMs] 以上のサンプルがない組は返さない（まだ途中かもしれないため）。
     */
    fun find(pcm: ShortArray, firstPtsUs: Long, sampleRate: Int, tailMs: Double = 20.0): List<Found> {
        val holdoff = (sampleRate * manifest.clickHoldoffMs / 1000).toInt()
        val maxGap = (sampleRate * (manifest.gapBaseMs + manifest.gapStepMs * (manifest.clicks.size + 2)) / 1000.0).toInt()
        val tail = (sampleRate * tailMs / 1000).toInt()
        val result = ArrayList<Found>()
        var i = 0
        while (i < pcm.size) {
            if (abs(pcm[i].toInt()) < manifest.clickThreshold) {
                i++
                continue
            }
            val first = i
            var j = first + holdoff
            var second = -1
            while (j < pcm.size && j - first <= maxGap) {
                if (abs(pcm[j].toInt()) >= manifest.clickThreshold) {
                    second = j
                    break
                }
                j++
            }
            if (second < 0) {
                if (j >= pcm.size) break
                i = first + holdoff
                continue
            }
            if (second + tail > pcm.size) break
            val gapMs = (second - first) * 1000.0 / sampleRate
            val index = ((gapMs - manifest.gapBaseMs) / manifest.gapStepMs).roundToInt()
            result += Found(firstPtsUs / 1000.0 + first * 1000.0 / sampleRate, gapMs, index)
            i = second + holdoff
        }
        return result
    }
}
