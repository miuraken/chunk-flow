package com.miuraken.chunkflow.core.designsystem.component.language

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.util.Locale

/**
 * 言語バッジが表す役割（§7.5.5: 色とテキストの両方で役割を示す）。
 *
 * 色は [chunkFlowLanguagePalette] の母語 / 学習言語の色、または `colorScheme` から取る。
 */
enum class LanguageBadgeRole {
    /** 母語（base）。 */
    BASE,

    /** 学習言語（target）。 */
    TARGET,

    /** 言語は判明しているが、母語 / 学習言語のどちらでもない。 */
    OTHER,

    /** 言語が未検出（`und` や空）。 */
    UNKNOWN,
}

/** 言語バッジの既定の内側余白。 */
val LanguageBadgeDefaultPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 2.dp)

/** 言語バッジのコンパクトな内側余白（チャンク表示設定行の言語フィルタチップで使う）。 */
val LanguageBadgeCompactPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 1.dp)

/**
 * 言語コードを役割の色で示す小さなバッジ。
 *
 * ライブラリの行の言語ペア、チャンク一覧の各行、チャンク表示設定行の言語フィルタで共有する。
 *
 * @param code 表示する言語コード（呼び出し側で小文字化済み。未検出なら `-`）。
 * @param role 役割（色の選択に使う）。
 * @param modifier 一番外側の [Surface] に適用する Modifier。
 * @param contentPadding 文字の周りの余白。既定は [LanguageBadgeDefaultPadding]。
 */
@Composable
fun LanguageRoleBadge(
    code: String,
    role: LanguageBadgeRole,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LanguageBadgeDefaultPadding,
) {
    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val containerColor = when (role) {
        LanguageBadgeRole.TARGET -> langPalette.targetBadgeContainer
        LanguageBadgeRole.BASE -> langPalette.baseBadgeContainer
        LanguageBadgeRole.OTHER -> colorScheme.secondaryContainer
        LanguageBadgeRole.UNKNOWN -> colorScheme.surfaceVariant
    }
    val contentColor = when (role) {
        LanguageBadgeRole.TARGET -> langPalette.onTargetBadgeContainer
        LanguageBadgeRole.BASE -> langPalette.onBaseBadgeContainer
        LanguageBadgeRole.OTHER -> colorScheme.onSecondaryContainer
        LanguageBadgeRole.UNKNOWN -> colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/**
 * 言語ペア（母語 + 学習言語）を 2 つの [LanguageRoleBadge] で横に並べるバッジ。母語が無いときは学習言語だけを出す。
 *
 * @param pair 表示する言語ペア。
 * @param modifier 一番外側の要素に適用する Modifier。
 */
@Composable
fun LanguagePairBadge(
    pair: LanguagePair,
    modifier: Modifier = Modifier,
) {
    val targetCode = pair.target.value.lowercase(Locale.ROOT)
    val baseCode = pair.base?.value?.lowercase(Locale.ROOT)

    if (baseCode != null) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LanguageRoleBadge(code = baseCode, role = LanguageBadgeRole.BASE)
            LanguageRoleBadge(code = targetCode, role = LanguageBadgeRole.TARGET)
        }
    } else {
        LanguageRoleBadge(
            code = targetCode,
            role = LanguageBadgeRole.TARGET,
            modifier = modifier,
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LanguageRoleBadgePreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageRoleBadge(code = "ja", role = LanguageBadgeRole.BASE)
                    LanguageRoleBadge(code = "ko", role = LanguageBadgeRole.TARGET)
                    LanguageRoleBadge(code = "en", role = LanguageBadgeRole.OTHER)
                    LanguageRoleBadge(code = "-", role = LanguageBadgeRole.UNKNOWN)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageRoleBadge(
                        code = "ja",
                        role = LanguageBadgeRole.BASE,
                        contentPadding = LanguageBadgeCompactPadding,
                    )
                    LanguageRoleBadge(
                        code = "ko",
                        role = LanguageBadgeRole.TARGET,
                        contentPadding = LanguageBadgeCompactPadding,
                    )
                }
                LanguagePairBadge(pair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")))
                LanguagePairBadge(pair = LanguagePair(target = LanguageTag("en"), base = null))
            }
        }
    }
}
