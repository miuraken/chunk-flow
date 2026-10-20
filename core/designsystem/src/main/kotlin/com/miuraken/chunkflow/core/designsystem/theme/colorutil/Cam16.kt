/*
 * 出典: Google material-color-utilities（https://github.com/material-foundation/material-color-utilities ）
 * `java/hct/Cam16.java` の純 Kotlin への移植。
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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CAM16 色の見えモデルによる色の表現。
 *
 * 色相 [hue]・彩度 [chroma]・明度 [j] に加え、CAM16-UCS の座標（[jstar]、[astar]、[bstar]）を持つ。
 * [Hct] の色相と彩度はこのモデルから取る。
 */
class Cam16 private constructor(
    /** 色相（0.0〜360.0）。 */
    val hue: Double,
    /** 彩度（0 以上。上限は色相と明度によって変わる）。 */
    val chroma: Double,
    /** 明度（J、0.0〜100.0）。 */
    val j: Double,
    /** 輝度（Q）。 */
    val q: Double,
    /** 色の濃さ（M）。 */
    val m: Double,
    /** 飽和度（s）。 */
    val s: Double,
    /** CAM16-UCS の J*。 */
    val jstar: Double,
    /** CAM16-UCS の a*。 */
    val astar: Double,
    /** CAM16-UCS の b*。 */
    val bstar: Double,
) {
    /** CAM16-UCS 上の色差（ΔE）。 */
    fun distance(other: Cam16): Double {
        val dJ = jstar - other.jstar
        val dA = astar - other.astar
        val dB = bstar - other.bstar
        val dEPrime = sqrt(dJ * dJ + dA * dA + dB * dB)
        return 1.41 * dEPrime.pow(0.63)
    }

    /** 既定の観察条件で ARGB に変換する。 */
    fun toInt(): Int = viewed(ViewingConditions.DEFAULT)

    /** 観察条件 [viewingConditions] で ARGB に変換する。 */
    fun viewed(viewingConditions: ViewingConditions): Int {
        val xyz = xyzInViewingConditions(viewingConditions)
        return ColorUtils.argbFromXyz(xyz[0], xyz[1], xyz[2])
    }

    /** 観察条件 [viewingConditions] で XYZ に変換する。 */
    fun xyzInViewingConditions(viewingConditions: ViewingConditions): DoubleArray {
        val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / sqrt(j / 100.0)

        val t = (alpha / (1.64 - 0.29.pow(viewingConditions.n)).pow(0.73)).pow(1.0 / 0.9)
        val hRad = Math.toRadians(hue)

        val eHue = 0.25 * (cos(hRad + 2.0) + 3.8)
        val ac = viewingConditions.aw * (j / 100.0).pow(1.0 / viewingConditions.c / viewingConditions.z)
        val p1 = eHue * (50000.0 / 13.0) * viewingConditions.nc * viewingConditions.ncb
        val p2 = ac / viewingConditions.nbb

        val hSin = sin(hRad)
        val hCos = cos(hRad)

        val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
        val a = gamma * hCos
        val b = gamma * hSin
        val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
        val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
        val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0

        val rCBase = max(0.0, (27.13 * abs(rA)) / (400.0 - abs(rA)))
        val rC = MathUtils.signum(rA) * (100.0 / viewingConditions.fl) * rCBase.pow(1.0 / 0.42)
        val gCBase = max(0.0, (27.13 * abs(gA)) / (400.0 - abs(gA)))
        val gC = MathUtils.signum(gA) * (100.0 / viewingConditions.fl) * gCBase.pow(1.0 / 0.42)
        val bCBase = max(0.0, (27.13 * abs(bA)) / (400.0 - abs(bA)))
        val bC = MathUtils.signum(bA) * (100.0 / viewingConditions.fl) * bCBase.pow(1.0 / 0.42)
        val rF = rC / viewingConditions.rgbD[0]
        val gF = gC / viewingConditions.rgbD[1]
        val bF = bC / viewingConditions.rgbD[2]

        val matrix = CAM16RGB_TO_XYZ
        val x = (rF * matrix[0][0]) + (gF * matrix[0][1]) + (bF * matrix[0][2])
        val y = (rF * matrix[1][0]) + (gF * matrix[1][1]) + (bF * matrix[1][2])
        val z = (rF * matrix[2][0]) + (gF * matrix[2][1]) + (bF * matrix[2][2])
        return doubleArrayOf(x, y, z)
    }

    companion object {
        /** XYZ → CAM16 RGB の変換行列。 */
        internal val XYZ_TO_CAM16RGB = arrayOf(
            doubleArrayOf(0.401288, 0.650173, -0.051461),
            doubleArrayOf(-0.250268, 1.204414, 0.045854),
            doubleArrayOf(-0.002079, 0.048952, 0.953127),
        )

        /** CAM16 RGB → XYZ の変換行列。 */
        internal val CAM16RGB_TO_XYZ = arrayOf(
            doubleArrayOf(1.8620678, -1.0112547, 0.14918678),
            doubleArrayOf(0.38752654, 0.62144744, -0.00897398),
            doubleArrayOf(-0.01584150, -0.03412294, 1.0499644),
        )

        /** ARGB から既定の観察条件で CAM16 を作る。 */
        fun fromInt(argb: Int): Cam16 = fromIntInViewingConditions(argb, ViewingConditions.DEFAULT)

        /** ARGB から観察条件 [viewingConditions] で CAM16 を作る。 */
        fun fromIntInViewingConditions(argb: Int, viewingConditions: ViewingConditions): Cam16 {
            val red = (argb and 0x00ff0000) shr 16
            val green = (argb and 0x0000ff00) shr 8
            val blue = argb and 0x000000ff
            val redL = ColorUtils.linearized(red)
            val greenL = ColorUtils.linearized(green)
            val blueL = ColorUtils.linearized(blue)
            val x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL
            val y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL
            val z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL
            return fromXyzInViewingConditions(x, y, z, viewingConditions)
        }

        /** XYZ から観察条件 [viewingConditions] で CAM16 を作る。 */
        fun fromXyzInViewingConditions(x: Double, y: Double, z: Double, viewingConditions: ViewingConditions): Cam16 {
            val matrix = XYZ_TO_CAM16RGB
            val rT = (x * matrix[0][0]) + (y * matrix[0][1]) + (z * matrix[0][2])
            val gT = (x * matrix[1][0]) + (y * matrix[1][1]) + (z * matrix[1][2])
            val bT = (x * matrix[2][0]) + (y * matrix[2][1]) + (z * matrix[2][2])

            val rD = viewingConditions.rgbD[0] * rT
            val gD = viewingConditions.rgbD[1] * gT
            val bD = viewingConditions.rgbD[2] * bT

            val rAF = (viewingConditions.fl * abs(rD) / 100.0).pow(0.42)
            val gAF = (viewingConditions.fl * abs(gD) / 100.0).pow(0.42)
            val bAF = (viewingConditions.fl * abs(bD) / 100.0).pow(0.42)
            val rA = MathUtils.signum(rD) * 400.0 * rAF / (rAF + 27.13)
            val gA = MathUtils.signum(gD) * 400.0 * gAF / (gAF + 27.13)
            val bA = MathUtils.signum(bD) * 400.0 * bAF / (bAF + 27.13)

            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            val b = (rA + gA - 2.0 * bA) / 9.0

            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

            val atan2 = atan2(b, a)
            val atanDegrees = Math.toDegrees(atan2)
            val hue = when {
                atanDegrees < 0 -> atanDegrees + 360.0
                atanDegrees >= 360 -> atanDegrees - 360.0
                else -> atanDegrees
            }
            val hueRadians = Math.toRadians(hue)

            val ac = p2 * viewingConditions.nbb

            val j = 100.0 * (ac / viewingConditions.aw).pow(viewingConditions.c * viewingConditions.z)
            val q = 4.0 / viewingConditions.c * sqrt(j / 100.0) * (viewingConditions.aw + 4.0) * viewingConditions.flRoot

            val huePrime = if (hue < 20.14) hue + 360 else hue
            val eHue = 0.25 * (cos(Math.toRadians(huePrime) + 2.0) + 3.8)
            val p1 = 50000.0 / 13.0 * eHue * viewingConditions.nc * viewingConditions.ncb
            val t = p1 * hypot(a, b) / (u + 0.305)
            val alpha = (1.64 - 0.29.pow(viewingConditions.n)).pow(0.73) * t.pow(0.9)
            val c = alpha * sqrt(j / 100.0)
            val m = c * viewingConditions.flRoot
            val s = 50.0 * sqrt((alpha * viewingConditions.c) / (viewingConditions.aw + 4.0))

            val jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
            val mstar = 1.0 / 0.0228 * ln(1.0 + 0.0228 * m)
            val astar = mstar * cos(hueRadians)
            val bstar = mstar * sin(hueRadians)

            return Cam16(hue, c, j, q, m, s, jstar, astar, bstar)
        }

        /** 明度 [j]・彩度 [c]・色相 [h] から既定の観察条件で CAM16 を作る。 */
        fun fromJch(j: Double, c: Double, h: Double): Cam16 = fromJchInViewingConditions(j, c, h, ViewingConditions.DEFAULT)

        /** 明度 [j]・彩度 [c]・色相 [h] から観察条件 [viewingConditions] で CAM16 を作る。 */
        fun fromJchInViewingConditions(j: Double, c: Double, h: Double, viewingConditions: ViewingConditions): Cam16 {
            val q = 4.0 / viewingConditions.c * sqrt(j / 100.0) * (viewingConditions.aw + 4.0) * viewingConditions.flRoot
            val m = c * viewingConditions.flRoot
            val alpha = c / sqrt(j / 100.0)
            val s = 50.0 * sqrt((alpha * viewingConditions.c) / (viewingConditions.aw + 4.0))

            val hueRadians = h * PI / 180.0
            val jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
            val mstar = 1.0 / 0.0228 * ln(1.0 + 0.0228 * m)
            val astar = mstar * cos(hueRadians)
            val bstar = mstar * sin(hueRadians)
            return Cam16(h, c, j, q, m, s, jstar, astar, bstar)
        }
    }
}
