package com.miuraken.chunkflow.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val LINK_LABEL = "全体設定（言語）"

/** 歯車（インラインコンテンツの代わりの文字）+ WORD JOINER + リンクの文字。 */
private const val LINK_TEXT = "⚙\u2060$LINK_LABEL"

/** 言語の案内文のリンク（[buildMissingLanguageHintText]）の組み立てを固定する。 */
class MissingLanguageHintWithSettingsLinkTest {
    private val linkColor = Color.Magenta

    private fun build(sentence: String, onLinkClick: () -> Unit = {}): AnnotatedString = buildMissingLanguageHintText(
        sentence = sentence,
        linkLabel = LINK_LABEL,
        linkColor = linkColor,
        onLinkClick = onLinkClick,
    )

    private fun AnnotatedString.singleLink(): AnnotatedString.Range<LinkAnnotation> = getLinkAnnotations(0, length).single()

    private fun AnnotatedString.singleClickableLink(): LinkAnnotation.Clickable = singleLink().item as LinkAnnotation.Clickable

    @Test
    fun `文字列リソースの書式でスロットを渡す_スロットの位置に歯車とリンクの文字を差し込む`() {
        val sentence = "設定したい言語が見つからないときは、%1\$sから追加してください。".format(MISSING_LANGUAGE_HINT_LINK_SLOT)

        val text = build(sentence)

        assertEquals("設定したい言語が見つからないときは、${LINK_TEXT}から追加してください。", text.text)
        val link = text.singleLink()
        assertEquals(LINK_TEXT, text.text.substring(link.start, link.end))
    }

    @Test
    fun `リンクの先頭_歯車のインラインコンテンツを置く`() {
        val text = build("A${MISSING_LANGUAGE_HINT_LINK_SLOT}B")

        val link = text.singleLink()
        val icon = text.getStringAnnotations(0, text.length).single()
        assertEquals(LANGUAGE_SETTINGS_ICON_ID, icon.item)
        assertEquals(link.start, icon.start)
        assertEquals("⚙", text.text.substring(icon.start, icon.end))
    }

    @Test
    fun `スロットがない_リンクを文の最後に付ける`() {
        val text = build("言語を追加してください。")

        assertEquals("言語を追加してください。$LINK_TEXT", text.text)
        assertEquals(text.length, text.singleLink().end)
    }

    @Test
    fun `スロットが2つ以上ある_最初のスロットだけをリンクにして残りは取り除く`() {
        val text = build("A${MISSING_LANGUAGE_HINT_LINK_SLOT}B${MISSING_LANGUAGE_HINT_LINK_SLOT}C")

        assertEquals("A${LINK_TEXT}BC", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun `リンクの見た目_primaryの色とMediumの太さで_フォーカスと押下とホバーのときだけ下線を引く`() {
        val link = build("A${MISSING_LANGUAGE_HINT_LINK_SLOT}B").singleClickableLink()

        assertEquals(LANGUAGE_SETTINGS_LINK_TAG, link.tag)
        val styles = requireNotNull(link.styles)
        assertEquals(linkColor, styles.style?.color)
        assertEquals(FontWeight.Medium, styles.style?.fontWeight)
        assertNull(styles.style?.textDecoration)
        assertEquals(TextDecoration.Underline, styles.focusedStyle?.textDecoration)
        assertEquals(TextDecoration.Underline, styles.hoveredStyle?.textDecoration)
        assertEquals(TextDecoration.Underline, styles.pressedStyle?.textDecoration)
    }

    @Test
    fun `リンクを押す_コールバックを1回呼ぶ`() {
        var clicks = 0
        val link = build("A${MISSING_LANGUAGE_HINT_LINK_SLOT}B", onLinkClick = { clicks++ }).singleClickableLink()

        requireNotNull(link.linkInteractionListener).onClick(link)

        assertEquals(1, clicks)
    }
}
