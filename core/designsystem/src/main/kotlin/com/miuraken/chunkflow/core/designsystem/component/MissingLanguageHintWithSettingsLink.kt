package com.miuraken.chunkflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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

/**
 * [MissingLanguageHintWithSettingsLink] の案内文で、リンクを差し込む位置に置く文字。
 *
 * 案内文の文字列リソースでリンクの位置を `%1$s` にしておき、
 * `stringResource(R.string.xxx, MISSING_LANGUAGE_HINT_LINK_SLOT)` のようにこの文字を渡して `sentence` を作る。
 * 翻訳の本文に現れない文字（U+FFFC OBJECT REPLACEMENT CHARACTER）にしてある。
 */
const val MISSING_LANGUAGE_HINT_LINK_SLOT: String = "\uFFFC"

/** 歯車とリンクの文字のあいだで改行させないための文字（WORD JOINER。幅がなく、読み上げられない）。 */
private const val WORD_JOINER = "\u2060"

/** 歯車のアイコンの代わりの文字（読み上げやコピーでは、アイコンはこの記号として扱われる）。 */
private const val SETTINGS_ICON_ALTERNATE_TEXT = "⚙"

/** リンクの前に置く歯車のアイコン（インラインコンテンツ）の ID。 */
internal const val LANGUAGE_SETTINGS_ICON_ID = "languageSettingsIcon"

/** 案内文のリンクのタグ。 */
internal const val LANGUAGE_SETTINGS_LINK_TAG = "openLanguageSettings"

/**
 * 設定したい言語が選択肢にないときの案内文。文中の「⚙ 全体設定（言語）」を押せるリンクにして表示する。
 *
 * 「設定したい言語が見つからないときは、⚙ 全体設定（言語）から追加してください。」のように、[sentence] の
 * [MISSING_LANGUAGE_HINT_LINK_SLOT] の位置に、歯車のアイコンと [linkLabel] からなるリンクを差し込む。
 * リンクを押すと [onOpenLanguageSettings]（全体設定の言語のタブを開く）を呼ぶ。
 *
 * - 見た目: 案内文は `bodySmall` の `onSurfaceVariant`（[MissingLanguageHintText] と同じ）。リンクは `primary` の色で
 *   `Medium` の太さにし、フォーカス・押下・ホバーのあいだは下線を引く。色だけでなく、太さと歯車でもリンクだとわかる。
 * - 歯車（`ChunkFlowIcons.Settings`）は文中に置くインラインのアイコンで、文字の大きさ（`em`）に合わせて大きくなる。
 *   歯車もリンクに含まれる（押すと開く）。読み上げでは記号「⚙」として扱われる。
 * - 歯車とリンクの文字のあいだに WORD JOINER（U+2060）を入れ、改行で歯車とリンクの文字が別の行に分かれないようにしている。
 * - リンクは [LinkAnnotation.Clickable] で作るので、TalkBack でもリンクとして扱われ、キーボードでもフォーカスできる。
 *   文中のリンクなので、押せる範囲はリンクの文字の範囲になる（タップ領域 48dp の例外。WCAG 2.5.8 の「インライン」に当たる）。
 *
 * 使い方: 文字列は呼び出し側（feature）のリソースに置き、リンクの位置を `%1$s` にする。
 * ```
 * <!-- %1$s には「⚙ 全体設定（言語）」のリンクが入る（歯車は部品が付けるので書かない） -->
 * <string name="xxx_missing_language_hint_with_link">設定したい言語が見つからないときは、%1$sから追加してください。</string>
 * <string name="xxx_open_global_language_settings">全体設定（言語）</string>
 * ```
 * ```
 * MissingLanguageHintWithSettingsLink(
 *     sentence = stringResource(R.string.xxx_missing_language_hint_with_link, MISSING_LANGUAGE_HINT_LINK_SLOT),
 *     linkLabel = stringResource(R.string.xxx_open_global_language_settings),
 *     onOpenLanguageSettings = onOpenLanguageSettings,
 * )
 * ```
 *
 * @param sentence 案内文。リンクを差し込む位置に [MISSING_LANGUAGE_HINT_LINK_SLOT] を 1 つ含める。
 *   含まないときはリンクを文の最後に付け、2 つ目以降は取り除く。
 * @param linkLabel リンクの文字（例: 「全体設定（言語）」）。歯車のアイコンは部品が前に付けるので含めない。
 * @param onOpenLanguageSettings リンクを押したときに呼ぶ（全体設定の言語のタブを開く）。
 * @param modifier 案内文の [Text] に適用する Modifier。
 */
@Composable
fun MissingLanguageHintWithSettingsLink(
    sentence: String,
    linkLabel: String,
    onOpenLanguageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val currentOnOpenLanguageSettings by rememberUpdatedState(onOpenLanguageSettings)
    val text = remember(sentence, linkLabel, linkColor) {
        buildMissingLanguageHintText(
            sentence = sentence,
            linkLabel = linkLabel,
            linkColor = linkColor,
            onLinkClick = { currentOnOpenLanguageSettings() },
        )
    }
    val inlineContent = remember(linkColor) { languageSettingsIconInlineContent(tint = linkColor) }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * [MissingLanguageHintWithSettingsLink] の案内文を組み立てる。
 *
 * [sentence] の最初の [MISSING_LANGUAGE_HINT_LINK_SLOT] を「歯車（インラインコンテンツ）+ WORD JOINER + [linkLabel]」の
 * リンクに置き換える。スロットがなければリンクを最後に付け、2 つ目以降のスロットは（記号として表示されないように）取り除く。
 *
 * @param linkColor リンクの文字の色（`primary`）。
 * @param onLinkClick リンクを押したときに呼ぶ。
 */
internal fun buildMissingLanguageHintText(
    sentence: String,
    linkLabel: String,
    linkColor: Color,
    onLinkClick: () -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(sentence.substringBefore(MISSING_LANGUAGE_HINT_LINK_SLOT))
    withLink(
        LinkAnnotation.Clickable(
            tag = LANGUAGE_SETTINGS_LINK_TAG,
            styles = TextLinkStyles(
                style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium),
                focusedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
            ),
            linkInteractionListener = { onLinkClick() },
        ),
    ) {
        appendInlineContent(LANGUAGE_SETTINGS_ICON_ID, alternateText = SETTINGS_ICON_ALTERNATE_TEXT)
        append(WORD_JOINER)
        append(linkLabel)
    }
    append(
        sentence
            .substringAfter(MISSING_LANGUAGE_HINT_LINK_SLOT, missingDelimiterValue = "")
            .replace(MISSING_LANGUAGE_HINT_LINK_SLOT, ""),
    )
}

/** リンクの前に置く歯車のアイコン。文字の大きさに合わせて大きくなるよう、大きさを `em` で決める。 */
private fun languageSettingsIconInlineContent(tint: Color): Map<String, InlineTextContent> = mapOf(
    LANGUAGE_SETTINGS_ICON_ID to InlineTextContent(
        placeholder = Placeholder(
            width = 1.35.em,
            height = 1.15.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        // アイコンを行の始まりの側に寄せ、残りの幅をリンクの文字とのすき間にする
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Icon(
                imageVector = ChunkFlowIcons.Settings,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            )
        }
    },
)

@ChunkFlowPreviews
@Composable
internal fun MissingLanguageHintWithSettingsLinkPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MissingLanguageHintWithSettingsLink(
                    sentence = "設定したい言語が見つからないときは、${MISSING_LANGUAGE_HINT_LINK_SLOT}から追加してください。",
                    linkLabel = "全体設定（言語）",
                    onOpenLanguageSettings = {},
                )
                MissingLanguageHintWithSettingsLink(
                    sentence = "If the language you want is not listed, add it from $MISSING_LANGUAGE_HINT_LINK_SLOT.",
                    linkLabel = "Global Settings (Languages)",
                    onOpenLanguageSettings = {},
                )
                // 幅が狭く、折り返すとき
                MissingLanguageHintWithSettingsLink(
                    sentence = "設定したい言語が見つからないときは、${MISSING_LANGUAGE_HINT_LINK_SLOT}から追加してください。",
                    linkLabel = "全体設定（言語）",
                    onOpenLanguageSettings = {},
                    modifier = Modifier.width(200.dp),
                )
            }
        }
    }
}
