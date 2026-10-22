package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryCourseFolderSttOverrideTest {
    private fun folder(path: String, override: ItemSttOverride = ItemSttOverride.NONE) = LibraryCourseFolder(
        coursePath = path,
        kind = LibraryFolderKind.VIRTUAL_DIRECTORY,
        languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
        sttOverride = override,
    )

    @Test
    fun `selfAndAncestorPaths_自分から親へ近い順に並べ空の区切りと前後の区切りを無視する`() {
        assertEquals(listOf("A/B/C", "A/B", "A"), LibraryCourseFolder.selfAndAncestorPaths("A/B/C"))
        assertEquals(listOf("A/B", "A"), LibraryCourseFolder.selfAndAncestorPaths("/A// B /"))
        assertEquals(listOf("英語"), LibraryCourseFolder.selfAndAncestorPaths("英語"))
        assertEquals(emptyList(), LibraryCourseFolder.selfAndAncestorPaths(null))
        assertEquals(emptyList(), LibraryCourseFolder.selfAndAncestorPaths(" / "))
    }

    @Test
    fun `resolveSttOverride_どのフォルダも上書きしていなければ全体設定に従う`() {
        val folders = listOf(folder("A"), folder("A/B"))

        assertEquals(ItemSttOverride.NONE, LibraryCourseFolder.resolveSttOverride("A/B", folders))
        assertEquals(ItemSttOverride.NONE, LibraryCourseFolder.resolveSttOverride(null, folders))
        assertEquals(ItemSttOverride.NONE, LibraryCourseFolder.resolveSttOverride("A/B", emptyList()))
    }

    @Test
    fun `resolveSttOverride_近いフォルダを優先し上書きしていない言語は親フォルダの設定を使う`() {
        val folders = listOf(
            folder("A", ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.SPEECH_RECOGNIZER)),
            folder("A/B", ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX)),
            folder("A/B/C"),
        )

        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX, baseEngine = SttEngine.SPEECH_RECOGNIZER),
            LibraryCourseFolder.resolveSttOverride("A/B/C", folders),
        )
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.SPEECH_RECOGNIZER),
            LibraryCourseFolder.resolveSttOverride("A", folders),
        )
    }

    @Test
    fun `resolveSttOverride_名前の先頭が同じだけのフォルダの設定は使わない`() {
        val folders = listOf(
            folder("英語", ItemSttOverride(targetEngine = SttEngine.ML_KIT)),
            folder("英語上級/1", ItemSttOverride.NONE),
        )

        assertEquals(ItemSttOverride.NONE, LibraryCourseFolder.resolveSttOverride("英語上級/1", folders))
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT),
            LibraryCourseFolder.resolveSttOverride("/英語/ 1/", folders),
        )
    }

    @Test
    fun `resolveSttOverride_ファイル個別設定と合成するとファイル個別設定が優先される`() {
        val folders = listOf(
            folder("A", ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.SPEECH_RECOGNIZER)),
        )
        val itemOverride = ItemSttOverride(baseEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE)

        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE),
            itemOverride.withFolderFallback(LibraryCourseFolder.resolveSttOverride("A/B", folders)),
        )
    }

    @Test
    fun `resolveSttOverride_問い合わせ版は近い順にフォルダのパスを渡し両言語が決まったら止める`() {
        val asked = mutableListOf<String>()
        val result = LibraryCourseFolder.resolveSttOverride("A/B/C") { path ->
            asked += path
            when (path) {
                "A/B" -> ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.ML_KIT)
                else -> null
            }
        }

        assertEquals(ItemSttOverride(targetEngine = SttEngine.ML_KIT, baseEngine = SttEngine.ML_KIT), result)
        assertEquals(listOf("A/B/C", "A/B"), asked)
    }
}
