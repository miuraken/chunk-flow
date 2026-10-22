package com.miuraken.chunkflow.feature.library

import com.miuraken.chunkflow.core.model.LibraryTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** タグの入力と「タグを編集」のチェックの決まり（ADR 0013）。 */
class LibraryTagEditingTest {
    @Test
    fun `addTagFromInput_名前をそろえて末尾に加える`() {
        assertEquals(
            TagInputResult.Added(listOf("N5", "旅行 準備")),
            addTagFromInput(current = listOf("N5"), rawInput = "  旅行\n準備 ", availableTags = emptyList()),
        )
    }

    @Test
    fun `addTagFromInput_大文字小文字や全角半角だけ違う既存のタグはその表記にそろえる`() {
        assertEquals(
            TagInputResult.Added(listOf("News")),
            addTagFromInput(current = emptyList(), rawInput = "ｎｅｗｓ", availableTags = listOf("News")),
        )
    }

    @Test
    fun `addTagFromInput_すでに付いているタグなら一覧を変えない`() {
        assertEquals(
            TagInputResult.Added(listOf("News")),
            addTagFromInput(current = listOf("News"), rawInput = "news", availableTags = listOf("News")),
        )
    }

    @Test
    fun `addTagFromInput_空や長すぎる名前や制御文字は正しくない`() {
        assertEquals(TagInputResult.Invalid, addTagFromInput(emptyList(), "   ", emptyList()))
        assertEquals(TagInputResult.Invalid, addTagFromInput(emptyList(), "a".repeat(LibraryTags.MAX_NAME_LENGTH + 1), emptyList()))
        assertEquals(TagInputResult.Invalid, addTagFromInput(emptyList(), "a\u0000b", emptyList()))
    }

    @Test
    fun `addTagFromInput_上限に達していたら加えない`() {
        val full = (1..LibraryTags.MAX_TAGS_PER_ITEM).map { "t$it" }
        assertEquals(TagInputResult.LimitReached, addTagFromInput(full, "new", emptyList()))
        // 付いているタグを入れ直すだけなら上限でも受け付ける
        assertEquals(TagInputResult.Added(full), addTagFromInput(full, "t1", emptyList()))
    }

    @Test
    fun `resolveTagsForSave_入力途中のタグがあれば加え_なければそのまま`() {
        assertEquals(TagInputResult.Added(listOf("A")), resolveTagsForSave(listOf("A"), "  ", emptyList()))
        assertEquals(TagInputResult.Added(listOf("A", "B")), resolveTagsForSave(listOf("A"), "B", emptyList()))
        assertEquals(TagInputResult.Invalid, resolveTagsForSave(listOf("A"), "x".repeat(40), emptyList()))
    }

    @Test
    fun `initialTagCheckState_すべてならオン_一部なら中間_なしならオフ`() {
        assertEquals(TagCheckState.ON, initialTagCheckState(count = 3, targetCount = 3))
        assertEquals(TagCheckState.MIXED, initialTagCheckState(count = 1, targetCount = 3))
        assertEquals(TagCheckState.OFF, initialTagCheckState(count = 0, targetCount = 3))
        assertEquals(TagCheckState.OFF, initialTagCheckState(count = 0, targetCount = 0))
    }

    @Test
    fun `nextTagCheckState_中間で始まったものだけ中間に戻る`() {
        val fromMixed = generateSequence(TagCheckState.MIXED) { nextTagCheckState(it, initial = TagCheckState.MIXED) }
            .take(4)
            .toList()
        assertEquals(listOf(TagCheckState.MIXED, TagCheckState.ON, TagCheckState.OFF, TagCheckState.MIXED), fromMixed)

        val fromOn = generateSequence(TagCheckState.ON) { nextTagCheckState(it, initial = TagCheckState.ON) }
            .take(3)
            .toList()
        assertEquals(listOf(TagCheckState.ON, TagCheckState.OFF, TagCheckState.ON), fromOn)

        val fromOff = generateSequence(TagCheckState.OFF) { nextTagCheckState(it, initial = TagCheckState.OFF) }
            .take(3)
            .toList()
        assertEquals(listOf(TagCheckState.OFF, TagCheckState.ON, TagCheckState.OFF), fromOff)
    }

    @Test
    fun `tagEditChanges_変わったタグだけを付け外しする`() {
        val initial = mapOf(
            "all" to TagCheckState.ON,
            "some" to TagCheckState.MIXED,
            "none" to TagCheckState.OFF,
            "keep" to TagCheckState.MIXED,
        )
        val current = mapOf(
            "all" to TagCheckState.OFF,
            "some" to TagCheckState.ON,
            "none" to TagCheckState.OFF,
            "keep" to TagCheckState.MIXED,
            "新規" to TagCheckState.ON,
        )
        val changes = tagEditChanges(initial, current)
        assertEquals(setOf("some", "新規"), changes.addTags)
        assertEquals(setOf("all"), changes.removeTags)
        assertEquals(true, tagEditChanges(initial, initial).isEmpty)
    }
}
