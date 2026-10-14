package com.miuraken.chunkflow.core.testing.fixture

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.miuraken.chunkflow.core.model.AudioItem

/** プレビュー用: すべての教材のフィクスチャ（[SampleMaterials.all]）。 */
class SampleMaterialPreviewParameterProvider : PreviewParameterProvider<SampleMaterial> {
    override val values: Sequence<SampleMaterial> = SampleMaterials.all.asSequence()

    override fun getDisplayName(index: Int): String = SampleMaterials.all[index].key
}

/** プレビュー用: ライブラリの一覧（空 / 複数のコース）。 */
class SampleLibraryPreviewParameterProvider : PreviewParameterProvider<List<AudioItem>> {
    override val values: Sequence<List<AudioItem>> = sequenceOf(SampleLibraries.empty, SampleLibraries.multipleCourses)

    override fun getDisplayName(index: Int): String = if (index == 0) "empty" else "multiple-courses"
}
