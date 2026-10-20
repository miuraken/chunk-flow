package com.miuraken.chunkflow.core.designsystem.theme

import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Blend
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.MathUtils

/**
 * `primary` の色相に調和させた、言語役割カラーと拡張色の色相（計画書 §3.2）。
 *
 * 基準色相（[ChunkFlowHues.BASE_HUE] など）を `primary` の色相へ部分的に寄せる（`Blend.harmonize` 相当、
 * 最大 [Blend.MAX_HARMONIZE_DEGREES] 度）。学習言語だけは `primary` と見分けられるように、
 * 調和後の色相差が [ChunkFlowHues.MIN_TARGET_PRIMARY_DISTANCE] 度を下回るときは `primary` から逃がす。
 *
 * すべて純 Kotlin（`android.*` / `androidx.*` に依存しない）で、単体テストで制約を固定する。
 *
 * @property base 母語（ベース言語）の色相。
 * @property target 学習言語（対象言語）の色相。
 * @property completed 既習（聴き終えた教材）・成功・文字起こし完了を表す緑系の色相。
 * @property favorite お気に入りを表す琥珀系の色相。
 * @property primaryChroma `primary` の彩度。無彩色に近い `primary`（調和を行わない）の判定に使う。
 */
internal data class ChunkFlowHues(
    val base: Double,
    val target: Double,
    val completed: Double,
    val favorite: Double,
    val primaryChroma: Double,
) {
    companion object {
        /** 母語の基準色相（青）。 */
        const val BASE_HUE: Double = 250.0

        /** 学習言語の基準色相（ピンク）。 */
        const val TARGET_HUE: Double = 350.0

        /** 既習・成功・完了の基準色相（緑）。 */
        const val COMPLETED_HUE: Double = 145.0

        /** お気に入りの基準色相（琥珀）。 */
        const val FAVORITE_HUE: Double = 45.0

        /** 学習言語と `primary` の色相差の下限（度）。 */
        const val MIN_TARGET_PRIMARY_DISTANCE: Double = 30.0

        /** 母語と学習言語の色相差の下限（度）。設計上の保証値で、テストで固定する。 */
        const val MIN_BASE_TARGET_DISTANCE: Double = 60.0

        /** この彩度を下回る `primary`（グレー系）には調和を行わず、基準色相をそのまま使う。 */
        const val MIN_PRIMARY_CHROMA_FOR_HARMONIZATION: Double = 5.0

        /** `primary` の ARGB から色相の組を作る。 */
        fun from(primaryArgb: Int): ChunkFlowHues {
            val primary = Hct.fromInt(primaryArgb)
            return fromPrimaryHue(primary.hue, primary.chroma)
        }

        /** `primary` の色相と彩度から色相の組を作る。 */
        fun fromPrimaryHue(primaryHue: Double, primaryChroma: Double): ChunkFlowHues {
            if (primaryChroma < MIN_PRIMARY_CHROMA_FOR_HARMONIZATION) {
                return ChunkFlowHues(
                    base = BASE_HUE,
                    target = TARGET_HUE,
                    completed = COMPLETED_HUE,
                    favorite = FAVORITE_HUE,
                    primaryChroma = primaryChroma,
                )
            }
            val base = Blend.harmonizeHue(BASE_HUE, primaryHue)
            val target = targetHueAwayFromPrimary(
                harmonizedTarget = Blend.harmonizeHue(TARGET_HUE, primaryHue),
                primaryHue = primaryHue,
                baseHue = base,
            )
            return ChunkFlowHues(
                base = base,
                target = target,
                completed = Blend.harmonizeHue(COMPLETED_HUE, primaryHue),
                favorite = Blend.harmonizeHue(FAVORITE_HUE, primaryHue),
                primaryChroma = primaryChroma,
            )
        }

        /**
         * 調和後の学習言語の色相が `primary` に近すぎる（[MIN_TARGET_PRIMARY_DISTANCE] 未満）ときは、
         * `primary` から [MIN_TARGET_PRIMARY_DISTANCE] 度離れた 2 候補のうち、基準色相（ピンク）に近いほうを優先し、
         * それが母語と [MIN_BASE_TARGET_DISTANCE] 度以上離れていなければ、母語から遠いほうへ逃がす。
         */
        private fun targetHueAwayFromPrimary(harmonizedTarget: Double, primaryHue: Double, baseHue: Double): Double {
            if (MathUtils.differenceDegrees(harmonizedTarget, primaryHue) >= MIN_TARGET_PRIMARY_DISTANCE) {
                return harmonizedTarget
            }
            val candidates = listOf(
                MathUtils.sanitizeDegreesDouble(primaryHue + MIN_TARGET_PRIMARY_DISTANCE),
                MathUtils.sanitizeDegreesDouble(primaryHue - MIN_TARGET_PRIMARY_DISTANCE),
            ).sortedBy { MathUtils.differenceDegrees(it, TARGET_HUE) }
            return candidates.firstOrNull { MathUtils.differenceDegrees(it, baseHue) >= MIN_BASE_TARGET_DISTANCE }
                ?: candidates.maxBy { MathUtils.differenceDegrees(it, baseHue) }
        }
    }
}
