/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/utils/ColorUtils.java` の純 Kotlin への移植。
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

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * sRGB（ARGB の Int）↔ XYZ ↔ L*a*b* の変換（`android.*` / `androidx.*` に依存しない）。
 *
 * `material-color-utilities` の `ColorUtils` に相当する最小集合。色は `0xAARRGGBB` の Int で表す。
 */
object ColorUtils {
    private val SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )

    private val XYZ_TO_SRGB = arrayOf(
        doubleArrayOf(3.2413774792388685, -1.5376652402851851, -0.49885366846268053),
        doubleArrayOf(-0.9691452513005321, 1.8758853451067872, 0.04156585616912061),
        doubleArrayOf(0.05562093689691305, -0.20395524564742123, 1.0571799111220335),
    )

    /** D65 白色点（XYZ、Y = 100 基準）。 */
    val WHITE_POINT_D65: DoubleArray = doubleArrayOf(95.047, 100.0, 108.883)

    /** 0〜255 の RGB 成分から不透明な ARGB を作る。 */
    fun argbFromRgb(red: Int, green: Int, blue: Int): Int = (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)

    /** 線形 RGB（0.0〜100.0）から ARGB を作る。 */
    fun argbFromLinrgb(linrgb: DoubleArray): Int {
        val r = delinearized(linrgb[0])
        val g = delinearized(linrgb[1])
        val b = delinearized(linrgb[2])
        return argbFromRgb(r, g, b)
    }

    /** ARGB のアルファ成分（0〜255）。 */
    fun alphaFromArgb(argb: Int): Int = (argb shr 24) and 255

    /** ARGB の赤成分（0〜255）。 */
    fun redFromArgb(argb: Int): Int = (argb shr 16) and 255

    /** ARGB の緑成分（0〜255）。 */
    fun greenFromArgb(argb: Int): Int = (argb shr 8) and 255

    /** ARGB の青成分（0〜255）。 */
    fun blueFromArgb(argb: Int): Int = argb and 255

    /** 完全に不透明かどうか。 */
    fun isOpaque(argb: Int): Boolean = alphaFromArgb(argb) >= 255

    /** XYZ から ARGB を作る。 */
    fun argbFromXyz(x: Double, y: Double, z: Double): Int {
        val matrix = XYZ_TO_SRGB
        val linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z
        val linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z
        val linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z
        val r = delinearized(linearR)
        val g = delinearized(linearG)
        val b = delinearized(linearB)
        return argbFromRgb(r, g, b)
    }

    /** ARGB から XYZ を求める。 */
    fun xyzFromArgb(argb: Int): DoubleArray {
        val r = linearized(redFromArgb(argb))
        val g = linearized(greenFromArgb(argb))
        val b = linearized(blueFromArgb(argb))
        return MathUtils.matrixMultiply(doubleArrayOf(r, g, b), SRGB_TO_XYZ)
    }

    /** L*a*b* から ARGB を作る。 */
    fun argbFromLab(l: Double, a: Double, b: Double): Int {
        val whitePoint = WHITE_POINT_D65
        val fy = (l + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        val xNormalized = labInvf(fx)
        val yNormalized = labInvf(fy)
        val zNormalized = labInvf(fz)
        val x = xNormalized * whitePoint[0]
        val y = yNormalized * whitePoint[1]
        val z = zNormalized * whitePoint[2]
        return argbFromXyz(x, y, z)
    }

    /** ARGB から L*a*b* を求める（`[L, a, b]`）。 */
    fun labFromArgb(argb: Int): DoubleArray {
        val linearR = linearized(redFromArgb(argb))
        val linearG = linearized(greenFromArgb(argb))
        val linearB = linearized(blueFromArgb(argb))
        val matrix = SRGB_TO_XYZ
        val x = matrix[0][0] * linearR + matrix[0][1] * linearG + matrix[0][2] * linearB
        val y = matrix[1][0] * linearR + matrix[1][1] * linearG + matrix[1][2] * linearB
        val z = matrix[2][0] * linearR + matrix[2][1] * linearG + matrix[2][2] * linearB
        val whitePoint = WHITE_POINT_D65
        val xNormalized = x / whitePoint[0]
        val yNormalized = y / whitePoint[1]
        val zNormalized = z / whitePoint[2]
        val fx = labF(xNormalized)
        val fy = labF(yNormalized)
        val fz = labF(zNormalized)
        val l = 116.0 * fy - 16
        val a = 500.0 * (fx - fy)
        val b = 200.0 * (fy - fz)
        return doubleArrayOf(l, a, b)
    }

    /** L*（0.0〜100.0）からグレーの ARGB を作る。 */
    fun argbFromLstar(lstar: Double): Int {
        val y = yFromLstar(lstar)
        val component = delinearized(y)
        return argbFromRgb(component, component, component)
    }

    /** ARGB の L*（0.0〜100.0）。 */
    fun lstarFromArgb(argb: Int): Double {
        val y = xyzFromArgb(argb)[1]
        return 116.0 * labF(y / 100.0) - 16.0
    }

    /** L* から Y（0.0〜100.0）を求める。 */
    fun yFromLstar(lstar: Double): Double = 100.0 * labInvf((lstar + 16.0) / 116.0)

    /** Y（0.0〜100.0）から L* を求める。 */
    fun lstarFromY(y: Double): Double = labF(y / 100.0) * 116.0 - 16.0

    /** sRGB 成分（0〜255）を線形値（0.0〜100.0）にする。 */
    fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) {
            normalized / 12.92 * 100.0
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4) * 100.0
        }
    }

    /** 線形値（0.0〜100.0）を sRGB 成分（0〜255）にする。 */
    fun delinearized(rgbComponent: Double): Int {
        val normalized = rgbComponent / 100.0
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * normalized.pow(1.0 / 2.4) - 0.055
        }
        return MathUtils.clampInt(0, 255, (delinearized * 255.0).roundToInt())
    }

    /** D65 の白色点。 */
    fun whitePointD65(): DoubleArray = WHITE_POINT_D65

    private fun labF(t: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (t > e) {
            cbrt(t)
        } else {
            (kappa * t + 16) / 116
        }
    }

    private fun labInvf(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) {
            ft3
        } else {
            (116 * ft - 16) / kappa
        }
    }
}
