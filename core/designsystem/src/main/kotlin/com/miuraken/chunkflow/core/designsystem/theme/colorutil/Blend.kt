/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/blend/Blend.java` の純 Kotlin への移植（`harmonize` のみ）。
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

import kotlin.math.min

/**
 * 色の調和（`Blend.harmonize` 相当）。
 */
object Blend {
    /** 調和で色相を動かす上限（度）。 */
    const val MAX_HARMONIZE_DEGREES: Double = 15.0

    /**
     * [designColor] の色相を [sourceColor] の色相へ部分的に寄せる（最大 [MAX_HARMONIZE_DEGREES] 度）。
     * 彩度とトーンは保つ（色域の制約で彩度が下がることはある）。
     *
     * @return 調和後の ARGB。
     */
    fun harmonize(designColor: Int, sourceColor: Int): Int {
        val fromHct = Hct.fromInt(designColor)
        val toHct = Hct.fromInt(sourceColor)
        val outputHue = harmonizeHue(fromHct.hue, toHct.hue)
        return Hct.from(outputHue, fromHct.chroma, fromHct.tone).toInt()
    }

    /**
     * 色相 [designHue] を色相 [sourceHue] へ部分的に寄せる（差の半分、ただし最大 [MAX_HARMONIZE_DEGREES] 度）。
     *
     * @return 調和後の色相（0.0〜360.0）。
     */
    fun harmonizeHue(designHue: Double, sourceHue: Double): Double {
        val differenceDegrees = MathUtils.differenceDegrees(designHue, sourceHue)
        val rotationDegrees = min(differenceDegrees * 0.5, MAX_HARMONIZE_DEGREES)
        return MathUtils.sanitizeDegreesDouble(
            designHue + rotationDegrees * MathUtils.rotationDirection(designHue, sourceHue),
        )
    }
}
