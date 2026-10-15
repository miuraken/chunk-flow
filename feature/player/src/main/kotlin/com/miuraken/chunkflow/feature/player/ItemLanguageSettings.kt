package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.util.Locale

/**
 * 言語のプルダウンの選択肢。
 *
 * @property code 保存する言語コード（小文字。ベース言語の「なし」は空文字）。
 * @property label 表示名。
 */
internal data class ItemLanguageOption(
    val code: String,
    val label: String,
)

/**
 * 言語のプルダウンに並べる言語（コードのアルファベット順）。
 *
 * ライブラリの教材設定（`feature:library`）と同じ並び・表記にそろえる（feature モジュール間で依存できないため複製している）。
 */
internal val ITEM_LANGUAGE_OPTIONS: List<ItemLanguageOption> = listOf(
    ItemLanguageOption("de", "de - ドイツ語"),
    ItemLanguageOption("el", "el - ギリシャ語"),
    ItemLanguageOption("en", "en - 英語"),
    ItemLanguageOption("es", "es - スペイン語"),
    ItemLanguageOption("fr", "fr - フランス語"),
    ItemLanguageOption("hi", "hi - ヒンディー語"),
    ItemLanguageOption("id", "id - インドネシア語"),
    ItemLanguageOption("it", "it - イタリア語"),
    ItemLanguageOption("ja", "ja - 日本語"),
    ItemLanguageOption("ko", "ko - 韓国語"),
    ItemLanguageOption("pt", "pt - ポルトガル語"),
    ItemLanguageOption("ru", "ru - ロシア語"),
    ItemLanguageOption("th", "th - タイ語"),
    ItemLanguageOption("vi", "vi - ベトナム語"),
    ItemLanguageOption("yue", "yue - 広東語"),
    ItemLanguageOption("zh-cn", "zh-cn - 簡体中国語"),
    ItemLanguageOption("zh-tw", "zh-tw - 繁体中国語"),
)

/**
 * 言語コードをプルダウンの選択肢のコードにそろえる（小文字にし、`_` を `-` にし、中国語の表記ゆれをまとめる）。
 */
internal fun normalizeItemLanguageCode(rawCode: String): String {
    val cleaned = rawCode.trim().lowercase(Locale.ROOT).replace('_', '-')
    return when (cleaned) {
        "zh", "zh-hans", "zh-cn" -> "zh-cn"
        "zh-hant", "zh-tw", "zh-hk" -> "zh-tw"
        else -> cleaned
    }
}

/**
 * プルダウンの選択肢を作る。いまの言語 [currentCode] が一覧にない場合は追加し、[noneLabel] があれば先頭に「なし」を置く。
 */
internal fun buildItemLanguageOptions(
    currentCode: String,
    noneLabel: String? = null,
): List<ItemLanguageOption> {
    val normalizedCurrent = normalizeItemLanguageCode(currentCode)
    val options = ITEM_LANGUAGE_OPTIONS.toMutableList()
    if (normalizedCurrent.isNotBlank() && options.none { it.code == normalizedCurrent }) {
        options.add(ItemLanguageOption(normalizedCurrent, normalizedCurrent))
        options.sortBy { it.code }
    }
    return if (noneLabel != null) listOf(ItemLanguageOption("", noneLabel)) + options else options
}

/**
 * 学習言語を [targetCode] に変えたあとの言語ペアを返す。変更がない、または無効な場合は `null`。
 *
 * いまのベース言語と同じ言語を学習言語に選んだときは、学習言語とベース言語を入れ替える。
 */
internal fun languagePairWithTarget(
    current: LanguagePair?,
    targetCode: String,
): LanguagePair? {
    val newTarget = normalizeItemLanguageCode(targetCode).ifBlank { return null }
    val currentTarget = current?.target?.value?.let(::normalizeItemLanguageCode).orEmpty()
    val currentBase = current?.base?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
    if (newTarget == currentTarget) return null
    val newBase = if (newTarget == currentBase) currentTarget.takeIf { it.isNotBlank() } else currentBase
    return LanguagePair(target = LanguageTag(newTarget), base = newBase?.let(::LanguageTag))
}

/**
 * ベース言語を [baseCode]（空文字は「なし」）に変えたあとの言語ペアを返す。変更がない、または無効な場合は `null`。
 *
 * いまの学習言語と同じ言語をベース言語に選んだときは、学習言語とベース言語を入れ替える
 * （ベース言語が「なし」で入れ替えられない場合は `null`）。
 */
internal fun languagePairWithBase(
    current: LanguagePair?,
    baseCode: String,
): LanguagePair? {
    val currentTarget = current?.target?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
        ?: return null
    val currentBase = current.base?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
    val newBase = normalizeItemLanguageCode(baseCode).takeIf { it.isNotBlank() }
    if (newBase == currentBase) return null
    if (newBase == currentTarget) {
        val swappedTarget = currentBase ?: return null
        return LanguagePair(target = LanguageTag(swappedTarget), base = LanguageTag(currentTarget))
    }
    return LanguagePair(target = LanguageTag(currentTarget), base = newBase?.let(::LanguageTag))
}

/**
 * プレイヤーの設定シートの「言語」セクション。開いている教材の学習言語とベース言語をプルダウンで選び直す。
 *
 * 選んだ時点で [onChangeLanguages] を呼ぶ（いまの言語と同じものを選んだときは呼ばない）。
 */
@Composable
internal fun ItemLanguageSettingsSection(
    languagePair: LanguagePair?,
    onChangeLanguages: (LanguagePair) -> Unit,
    modifier: Modifier = Modifier,
) {
    val langPalette = chunkFlowLanguagePalette()
    val noneLabel = stringResource(R.string.player_settings_base_lang_none)
    val targetCode = normalizeItemLanguageCode(languagePair?.target?.value.orEmpty())
    val baseCode = normalizeItemLanguageCode(languagePair?.base?.value.orEmpty())
    val targetOptions = remember(targetCode) { buildItemLanguageOptions(currentCode = targetCode) }
    val baseOptions = remember(baseCode, noneLabel) {
        buildItemLanguageOptions(currentCode = baseCode, noneLabel = noneLabel)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.player_settings_language_section_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ItemLanguageDropdown(
            label = stringResource(R.string.player_settings_target_lang_label),
            selectedCode = targetCode,
            options = targetOptions,
            fallbackLabel = targetCode,
            containerColor = langPalette.targetRowBackground,
            accentColor = langPalette.onTargetBadgeContainer,
            onSelect = { code -> languagePairWithTarget(languagePair, code)?.let(onChangeLanguages) },
        )
        ItemLanguageDropdown(
            label = stringResource(R.string.player_settings_base_lang_label),
            selectedCode = baseCode,
            options = baseOptions,
            fallbackLabel = noneLabel,
            containerColor = langPalette.baseRowBackground,
            accentColor = langPalette.onBaseBadgeContainer,
            onSelect = { code -> languagePairWithBase(languagePair, code)?.let(onChangeLanguages) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemLanguageDropdown(
    label: String,
    selectedCode: String,
    options: List<ItemLanguageOption>,
    fallbackLabel: String,
    containerColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.code == selectedCode }?.label ?: fallbackLabel

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(text = label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    focusedLabelColor = accentColor,
                ),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    val isSelected = option.code == selectedCode
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.code)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}
