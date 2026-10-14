package com.miuraken.chunkflow.spike.audio

import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** 検出したクリックの時刻と正解（clicks.json）を突き合わせる。 */
object ClickMatch {
    private const val MAX_DISTANCE_S = 0.2

    /**
     * 各正解に最も近い検出（±0.2 秒以内）を対応させ、ずれ（検出 − 正解、ミリ秒）を集計する。
     */
    fun summarize(detectedS: List<Double>, expectedS: List<Double>): JSONObject {
        val deltas = ArrayList<Double>()
        var missed = 0
        for (e in expectedS) {
            val d = detectedS.minByOrNull { abs(it - e) }
            if (d == null || abs(d - e) > MAX_DISTANCE_S) missed++ else deltas.add((d - e) * 1000)
        }
        val o = JSONObject()
        o.put("matched", deltas.size)
        o.put("missed", missed)
        o.put("detected", detectedS.size)
        if (deltas.isNotEmpty()) {
            o.put("deltaMsMin", deltas.min().round(3))
            o.put("deltaMsMax", deltas.max().round(3))
            o.put("deltaMsMean", deltas.average().round(3))
            o.put("deltaMsFirst", deltas.first().round(3))
            o.put("deltaMsLast", deltas.last().round(3))
        }
        return o
    }

    /** 音声と同じフォルダの clicks.json から正解の時刻（秒）を読む。なければ空。 */
    fun loadExpected(audio: File): List<Double> {
        val f = File(audio.parentFile, "clicks.json")
        if (!f.isFile || f.length() > 1_000_000) return emptyList()
        val arr: JSONArray = JSONObject(f.readText()).getJSONArray("clicksS")
        return List(arr.length()) { arr.getDouble(it) }
    }
}
