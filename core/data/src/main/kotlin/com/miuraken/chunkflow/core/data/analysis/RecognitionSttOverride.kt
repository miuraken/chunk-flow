package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings

/**
 * 文字起こしに言語ペア [recognitionLanguages] を渡すときに、一緒に渡す音声認識エンジンの上書きを返す。
 *
 * この上書き（ファイル個別設定とフォルダ設定を合成したもの）は、教材の言語ペア [mediaLanguages] を基準にしている
 * （[ItemSttOverride.targetEngine] は教材の学習対象言語、[ItemSttOverride.baseEngine] は教材のベース言語のエンジン）。
 *
 * - [recognitionLanguages] が 2 言語のペア（教材の言語ペア）か `null` なら、そのまま返す。
 * - 単一言語のペア（`base == null`。チャンクに割り当てた言語だけで認識するとき）では、文字起こしはその言語を学習対象言語
 *   として扱い、[ItemSttOverride.targetEngine] を使う。そこで、割り当てた言語の教材での役割に合わせて写し替える。
 *   - 教材の学習対象言語なら `ItemSttOverride(targetEngine = targetEngine)`
 *   - 教材のベース言語なら `ItemSttOverride(targetEngine = baseEngine)`
 *   - どちらでもない（教材の言語ペアがわからないときを含む）なら [ItemSttOverride.NONE]（言語ごとの全体設定に従う）
 *
 * 言語は [LanguageSettings.normalize] でそろえて比べる。
 */
internal fun ItemSttOverride.forRecognitionLanguages(
    recognitionLanguages: LanguagePair?,
    mediaLanguages: LanguagePair?,
): ItemSttOverride {
    if (recognitionLanguages == null || recognitionLanguages.base != null) return this
    if (mediaLanguages == null) return ItemSttOverride.NONE
    val engine = when (LanguageSettings.normalize(recognitionLanguages.target)) {
        LanguageSettings.normalize(mediaLanguages.target) -> targetEngine
        mediaLanguages.base?.let(LanguageSettings::normalize) -> baseEngine
        else -> null
    }
    return ItemSttOverride(targetEngine = engine)
}
