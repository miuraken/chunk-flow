/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/utils/MathUtils.java` の純 Kotlin への移植。
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

/**
 * 色の計算で使う数学ユーティリティ（`android.*` / `androidx.*` に依存しない）。
 *
 * `material-color-utilities` の `MathUtils` に相当する最小集合。
 */
object MathUtils {
    /** [value] の符号（負: -1、0: 0、正: 1）。 */
    fun signum(value: Double): Int = when {
        value < 0 -> -1
        value == 0.0 -> 0
        else -> 1
    }

    /** [start] から [stop] へ [amount]（0.0〜1.0）だけ線形補間する。 */
    fun lerp(start: Double, stop: Double, amount: Double): Double = (1.0 - amount) * start + amount * stop

    /** [input] を [min]〜[max] に収める。 */
    fun clampInt(min: Int, max: Int, input: Int): Int = input.coerceIn(min, max)

    /** [input] を [min]〜[max] に収める。 */
    fun clampDouble(min: Double, max: Double, input: Double): Double = input.coerceIn(min, max)

    /** 角度を 0 以上 360 未満に正規化する。 */
    fun sanitizeDegreesInt(degrees: Int): Int {
        var d = degrees % 360
        if (d < 0) d += 360
        return d
    }

    /** 角度を 0.0 以上 360.0 未満に正規化する。 */
    fun sanitizeDegreesDouble(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * [from] から [to] へ最短で回るときの回転方向（+1.0: 角度が増える向き、-1.0: 減る向き）。
     */
    fun rotationDirection(from: Double, to: Double): Double {
        val increasingDifference = sanitizeDegreesDouble(to - from)
        return if (increasingDifference <= 180.0) 1.0 else -1.0
    }

    /** 2 つの角度の差（0.0〜180.0）。 */
    fun differenceDegrees(a: Double, b: Double): Double = 180.0 - abs(abs(a - b) - 180.0)

    /** 3 要素のベクトルと 3×3 行列の積。 */
    fun matrixMultiply(row: DoubleArray, matrix: Array<DoubleArray>): DoubleArray {
        val a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2]
        val b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2]
        val c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2]
        return doubleArrayOf(a, b, c)
    }
}
