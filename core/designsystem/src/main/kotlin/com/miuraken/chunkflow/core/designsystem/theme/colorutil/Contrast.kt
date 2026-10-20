/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/contrast/Contrast.java` の純 Kotlin への移植（コントラスト比の計算のみ）。
 *
 * Copyright 2022 Google LLC
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

import kotlin.math.max
import kotlin.math.min

/**
 * WCAG のコントラスト比（1.0〜21.0）。
 */
object Contrast {
    /** 2 つのトーン（L*）のコントラスト比。 */
    fun ratioOfTones(toneA: Double, toneB: Double): Double {
        val a = MathUtils.clampDouble(0.0, 100.0, toneA)
        val b = MathUtils.clampDouble(0.0, 100.0, toneB)
        return ratioOfYs(ColorUtils.yFromLstar(a), ColorUtils.yFromLstar(b))
    }

    /** 2 つの不透明な ARGB のコントラスト比。 */
    fun ratioOfArgbs(argbA: Int, argbB: Int): Double = ratioOfYs(ColorUtils.xyzFromArgb(argbA)[1], ColorUtils.xyzFromArgb(argbB)[1])

    private fun ratioOfYs(y1: Double, y2: Double): Double {
        val lighter = max(y1, y2)
        val darker = if (lighter == y2) y1 else y2
        return (lighter + 5.0) / (darker + 5.0)
    }
}
