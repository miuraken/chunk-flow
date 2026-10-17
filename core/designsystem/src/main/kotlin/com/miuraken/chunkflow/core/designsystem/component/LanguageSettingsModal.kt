package com.miuraken.chunkflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.util.Locale

/**
 * 言語タグに対応する表示名（「日本語 (ja)」など）を現在のロケールに応じて返す。
 *
 * @param tag 対象の言語タグ
 * @param currentLocale 現在のシステムロケール
 */
fun languageSettingDisplayName(
    tag: LanguageTag,
    currentLocale: Locale,
): String {
    val code = tag.value.lowercase(Locale.ROOT)
    val entry = SupportedLanguageCatalog.findByCode(code)
    val isJapanese = currentLocale.language.equals("ja", ignoreCase = true)
    val displayName = if (isJapanese) {
        entry?.japaneseName
    } else {
        entry?.englishName
    } ?: Locale.forLanguageTag(code).getDisplayLanguage(currentLocale)
        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    return if (displayName != null) {
        "$displayName ($code)"
    } else {
        code
    }
}

/**
 * 検索欄付きの有効な言語一覧（おすすめ・すべての言語）を表示する共通コンテンツ。
 *
 * @param enabledLanguages 現在有効になっている言語タグの集合
 * @param onToggleLanguage 言語の有効/無効を切り替えるコールバック
 * @param modifier レイアウト修飾子
 */
@Composable
fun LanguageSettingsListContent(
    enabledLanguages: Set<LanguageTag>,
    onToggleLanguage: (LanguageTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
    val currentLocale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val allEntries = LanguageSettings.SELECTABLE_LANGUAGES

    val filteredEntries = remember(normalizedQuery, allEntries) {
        if (normalizedQuery.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.code.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    entry.englishName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    entry.japaneseName.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
        }
    }

    val pinnedEntries = remember(filteredEntries) {
        filteredEntries.filter { entry -> LanguageSettings.isPinned(entry.code) }
    }
    val unpinnedEntries = remember(filteredEntries) {
        filteredEntries.filterNot { entry -> LanguageSettings.isPinned(entry.code) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = ChunkFlowIcons.Search,
                    contentDescription = null,
                )
            },
            placeholder = {
                Text(text = "言語を検索")
            },
        )

        if (filteredEntries.isEmpty()) {
            Text(
                text = "一致する言語が見つかりません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            if (pinnedEntries.isNotEmpty()) {
                Text(
                    text = "おすすめ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                pinnedEntries.forEach { entry ->
                    val language = LanguageTag(entry.code)
                    val isEnabled = enabledLanguages.contains(language)
                    val canToggle = !isEnabled || enabledLanguages.size > 1
                    LanguageSettingCheckboxRow(
                        label = languageSettingDisplayName(language, currentLocale),
                        checked = isEnabled,
                        enabled = canToggle,
                        onToggle = { onToggleLanguage(language) },
                    )
                }
            }

            if (unpinnedEntries.isNotEmpty()) {
                Text(
                    text = "すべての言語",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                unpinnedEntries.forEach { entry ->
                    val language = LanguageTag(entry.code)
                    val isEnabled = enabledLanguages.contains(language)
                    val canToggle = !isEnabled || enabledLanguages.size > 1
                    LanguageSettingCheckboxRow(
                        label = languageSettingDisplayName(language, currentLocale),
                        checked = isEnabled,
                        enabled = canToggle,
                        onToggle = { onToggleLanguage(language) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSettingCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onToggle,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 言語設定（または初回起動時の言語選択オンボーディング）をモーダル表示するダイアログ。
 *
 * @param enabledLanguages 現在有効な言語タグの集合
 * @param onToggleLanguage 言語の有効/無効を切り替えるコールバック
 * @param onDismiss ダイアログを閉じるコールバック
 * @param isFirstLaunchOnboarding 初回起動時のオンボーディング表示かどうか
 */
@Composable
fun LanguageSettingsModalDialog(
    enabledLanguages: Set<LanguageTag>,
    onToggleLanguage: (LanguageTag) -> Unit,
    onDismiss: () -> Unit,
    isFirstLaunchOnboarding: Boolean = false,
) {
    val title = if (isFirstLaunchOnboarding) {
        "使用する言語の選択"
    } else {
        "言語設定"
    }
    val description = if (isFirstLaunchOnboarding) {
        "学習する言語とベース言語（母国語）にチェックを入れてください（最低1つは選択が必要です）。\nこの設定は後から「全体設定 > 言語設定」でいつでも変更できます。"
    } else {
        "教材の学習言語・ベース言語として選択できる言語を設定します（最低1つは選択が必要です）。"
    }
    val confirmLabel = if (isFirstLaunchOnboarding) {
        "完了"
    } else {
        "閉じる"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LanguageSettingsListContent(
                    enabledLanguages = enabledLanguages,
                    onToggleLanguage = onToggleLanguage,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = confirmLabel)
            }
        },
    )
}

/**
 * 対象となる言語が選択肢にない場合に「全体設定>言語設定」モーダルを開くリンク付き案内テキスト。
 *
 * @param onOpenLanguageSettingsModal 「全体設定>言語設定」リンクタップ時のコールバック
 * @param modifier レイアウト修飾子
 */
@Composable
fun MissingLanguageHintWithModalLink(
    onOpenLanguageSettingsModal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(linkColor, onOpenLanguageSettingsModal) {
        buildAnnotatedString {
            append("対象となる言語がない場合は、")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "language_settings",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = { onOpenLanguageSettingsModal() },
                ),
            ) {
                append("全体設定>言語設定")
            }
            append("から設定してください")
        }
    }
    Text(
        text = annotatedText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
