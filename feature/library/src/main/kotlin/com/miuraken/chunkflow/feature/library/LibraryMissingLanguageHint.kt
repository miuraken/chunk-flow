package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/** 案内文の文字列リソースで、リンクを差し込む位置に渡す文字（翻訳の本文には現れない文字にする）。 */
private const val LINK_SLOT = "\uFFFC"

/** 歯車とリンクの文字のあいだで改行させないための文字（WORD JOINER。幅がなく、読み上げられない）。 */
private const val WORD_JOINER = "\u2060"

/** リンクの前に置く歯車のアイコン（インラインコンテンツ）の ID。 */
private const val SETTINGS_ICON_ID = "languageSettingsIcon"

/** 案内文のリンクのタグ。 */
private const val LANGUAGE_SETTINGS_LINK_TAG = "openLanguageSettings"

/**
 * 設定したい言語が選択肢にないときの案内文。
 *
 * 「設定したい言語が見つからないときは、⚙ 全体設定（言語） から追加してください。」のように、文中の
 * 「⚙ 全体設定（言語）」を primary 色のリンクにし、押すと [onOpenLanguageSettings]（全体設定の言語の画面を開く）を呼ぶ。
 * リンクは [LinkAnnotation.Clickable] で作るので、TalkBack でもリンクとして扱われる。
 * 歯車は `ChunkFlowIcons.Settings` を文中に置くインラインのアイコンで、文字の大きさに合わせて大きくなる
 * （読み上げでは記号「⚙」として扱われる）。
 *
 * 取り込み・新しい仮想フォルダ・ファイルの設定・フォルダの設定のシートの言語の欄（`LanguagePairDropdownSection`）で使う。
 */
@Composable
internal fun MissingLanguageHintWithSettingsLink(
    onOpenLanguageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sentence = stringResource(R.string.library_missing_language_hint_with_link, LINK_SLOT)
    val linkLabel = stringResource(R.string.library_open_global_language_settings)
    val linkColor = MaterialTheme.colorScheme.primary
    val currentOnOpenLanguageSettings by rememberUpdatedState(onOpenLanguageSettings)
    val text = remember(sentence, linkLabel, linkColor) {
        buildAnnotatedString {
            append(sentence.substringBefore(LINK_SLOT))
            withLink(
                LinkAnnotation.Clickable(
                    tag = LANGUAGE_SETTINGS_LINK_TAG,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium),
                        focusedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                        hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                        pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                    ),
                    linkInteractionListener = { currentOnOpenLanguageSettings() },
                ),
            ) {
                appendInlineContent(SETTINGS_ICON_ID, alternateText = "⚙")
                append(WORD_JOINER)
                append(linkLabel)
            }
            append(sentence.substringAfter(LINK_SLOT, missingDelimiterValue = ""))
        }
    }
    val inlineContent = remember(linkColor) {
        mapOf(
            SETTINGS_ICON_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = 1.35.em,
                    height = 1.15.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                // アイコンを左に寄せ、右の余りをリンクの文字とのすき間にする
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Icon(
                        imageVector = ChunkFlowIcons.Settings,
                        contentDescription = null,
                        tint = linkColor,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f),
                    )
                }
            },
        )
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@ChunkFlowPreviews
@Composable
private fun MissingLanguageHintWithSettingsLinkPreview() {
    ChunkFlowTheme {
        Surface {
            MissingLanguageHintWithSettingsLink(
                onOpenLanguageSettings = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
