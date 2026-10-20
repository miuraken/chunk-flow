/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/hct/Hct.java` と、HctSolver 導入前の探索ベースの解法（`CamSolver`）の純 Kotlin への移植。
 *
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.miuraken.chunkflow.core.designsystem.theme.colorutil

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * HCT 色空間（Hue / Chroma / Tone）の色。
 *
 * 色相と彩度は CAM16（[Cam16]）、トーンは L*（[ColorUtils.lstarFromArgb]）から取る。
 * sRGB の色域に収まらない組み合わせを指定すると、トーンと色相を保ったまま彩度を下げた色になる。
 *
 * **近似についての注記**: `material-color-utilities` の現行版は `HctSolver`（解析的な解法と大きな定数表）で
 * ARGB を求めるが、ここではそれ以前の版と同じ**探索ベースの解法**（彩度の二分探索 × 明度の二分探索）を移植している。
 * 結果は多くの入力で同一か ±1 階調程度の差に収まるが、完全一致は保証しない。
 */
class Hct private constructor(argb: Int) {
    /** 色相（0.0〜360.0）。 */
    var hue: Double = 0.0
        private set

    /** 彩度（0 以上）。 */
    var chroma: Double = 0.0
        private set

    /** トーン（L*、0.0〜100.0）。 */
    var tone: Double = 0.0
        private set

    private var argb: Int = 0

    init {
        setInternalState(argb)
    }

    /** 不透明な ARGB。 */
    fun toInt(): Int = argb

    /** 色相を変えた新しい色（彩度・トーンは可能な範囲で保つ）。 */
    fun withHue(newHue: Double): Hct = from(newHue, chroma, tone)

    /** 彩度を変えた新しい色（色相・トーンは可能な範囲で保つ）。 */
    fun withChroma(newChroma: Double): Hct = from(hue, newChroma, tone)

    /** トーンを変えた新しい色（色相・彩度は可能な範囲で保つ）。 */
    fun withTone(newTone: Double): Hct = from(hue, chroma, newTone)

    private fun setInternalState(argb: Int) {
        this.argb = argb
        val cam = Cam16.fromInt(argb)
        hue = cam.hue
        chroma = cam.chroma
        tone = ColorUtils.lstarFromArgb(argb)
    }

    override fun equals(other: Any?): Boolean = other is Hct && other.argb == argb

    override fun hashCode(): Int = argb

    override fun toString(): String = "Hct(hue=$hue, chroma=$chroma, tone=$tone, argb=0x${argb.toUInt().toString(16)})"

    companion object {
        private const val CHROMA_SEARCH_ENDPOINT = 0.4
        private const val LIGHTNESS_SEARCH_ENDPOINT = 0.01
        private const val DE_MAX = 1.0
        private const val DL_MAX = 0.2

        /**
         * 色相 [hue]・彩度 [chroma]・トーン [tone] から色を作る。
         *
         * 色域外の組み合わせでは、トーンと色相を保ったまま彩度を下げる。
         */
        fun from(hue: Double, chroma: Double, tone: Double): Hct = Hct(solveToInt(hue, chroma, tone))

        /** ARGB から色を作る。 */
        fun fromInt(argb: Int): Hct = Hct(argb)

        /** 指定の HCT に最も近い sRGB 色域内の ARGB を探索で求める。 */
        internal fun solveToInt(hue: Double, chroma: Double, tone: Double): Int {
            if (chroma < 1.0 || tone.roundToInt() <= 0 || tone.roundToInt() >= 100) {
                return ColorUtils.argbFromLstar(tone)
            }
            val sanitizedHue = MathUtils.sanitizeDegreesDouble(hue)

            var high = chroma
            var mid = chroma
            var low = 0.0
            var isFirstLoop = true
            var answer: Cam16? = null

            while (abs(low - high) >= CHROMA_SEARCH_ENDPOINT) {
                val possibleAnswer = findCamByJ(sanitizedHue, mid, tone)
                if (isFirstLoop) {
                    if (possibleAnswer != null) {
                        return possibleAnswer.viewed(ViewingConditions.DEFAULT)
                    } else {
                        isFirstLoop = false
                        mid = low + (high - low) / 2.0
                        continue
                    }
                }
                if (possibleAnswer == null) {
                    high = mid
                } else {
                    answer = possibleAnswer
                    low = mid
                }
                mid = low + (high - low) / 2.0
            }
            return answer?.viewed(ViewingConditions.DEFAULT) ?: ColorUtils.argbFromLstar(tone)
        }

        /** 色相・彩度を固定し、トーンに合う明度 J を二分探索する。色域に収まらなければ null。 */
        private fun findCamByJ(hue: Double, chroma: Double, tone: Double): Cam16? {
            var low = 0.0
            var high = 100.0
            var mid: Double
            var bestdL = 1000.0
            var bestdE = 1000.0
            var bestCam: Cam16? = null
            while (abs(low - high) > LIGHTNESS_SEARCH_ENDPOINT) {
                mid = low + (high - low) / 2
                val camBeforeClip = Cam16.fromJch(mid, chroma, hue)
                val clipped = camBeforeClip.toInt()
                val clippedLstar = ColorUtils.lstarFromArgb(clipped)
                val dL = abs(tone - clippedLstar)
                if (dL < DL_MAX) {
                    val camClipped = Cam16.fromInt(clipped)
                    val dE = camClipped.distance(Cam16.fromJch(camClipped.j, camClipped.chroma, hue))
                    if (dE <= DE_MAX && dE <= bestdE) {
                        bestdL = dL
                        bestdE = dE
                        bestCam = camClipped
                    }
                }
                if (bestdL == 0.0 && bestdE == 0.0) {
                    break
                }
                if (clippedLstar < tone) {
                    low = mid
                } else {
                    high = mid
                }
            }
            return bestCam
        }
    }
}
