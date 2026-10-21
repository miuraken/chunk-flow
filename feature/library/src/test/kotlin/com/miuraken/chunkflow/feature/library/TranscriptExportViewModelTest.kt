package com.miuraken.chunkflow.feature.library

import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.testing.fake.FakeLibraryRepository
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [TranscriptExportViewModel] のフォーマットの並び順・既定値と、クリップボードへのエクスポートのテスト。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptExportViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `フォーマット_並び順はTSV_SRT_LRC_JSONで既定はTSV`() {
        assertEquals(
            listOf(
                TranscriptExportFormat.TSV,
                TranscriptExportFormat.SRT,
                TranscriptExportFormat.LRC,
                TranscriptExportFormat.JSON,
            ),
            TranscriptExportUiState.FORMAT_ORDER,
        )
        assertEquals(TranscriptExportFormat.entries.toSet(), TranscriptExportUiState.FORMAT_ORDER.toSet())
        assertEquals(TranscriptExportFormat.TSV, TranscriptExportUiState().selectedFormat)
    }

    @Test
    fun `クリップボード_1ファイルは選んだ形式の全文を書き込みAndroid12以下でだけコピーしたことを表示する`() = runTest {
        val sampleKo = SampleMaterials.jaKo
        val repo = FakeLibraryRepository(initialItems = listOf(sampleKo.item))
        val tsv = "startMs\tendMs\tlang\ttext\n0\t1500\tko\t안녕하세요\n"
        repo.exportedTranscriptTexts[sampleKo.item.id to TranscriptExportFormat.TSV] = tsv
        val viewModel = TranscriptExportViewModel(libraryRepository = repo)
        viewModel.open(listOf(sampleKo.item.id))
        assertTrue(viewModel.uiState.value.canCopyToClipboard)

        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        // 画面が書き込みを終えるまでは、もう一度押せない
        assertTrue(viewModel.uiState.value.isCopyingToClipboard)
        assertFalse(viewModel.uiState.value.canCopyToClipboard)
        assertEquals(
            TranscriptExportEffect.WriteToClipboard(
                label = TranscriptExportViewModel.CLIPBOARD_LABEL,
                text = tsv.trimEnd(),
            ),
            viewModel.effects.first(),
        )

        viewModel.onEvent(TranscriptExportEvent.ClipboardWriteFinished(success = true, showConfirmation = true))
        assertFalse(viewModel.uiState.value.isCopyingToClipboard)
        assertEquals(
            TranscriptExportResultState.CopiedToClipboard(count = 1, format = TranscriptExportFormat.TSV),
            viewModel.uiState.value.exportResult,
        )

        // Android 13 以上（showConfirmation = false）はシステムが知らせるので、画面には出さない
        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        viewModel.effects.first()
        viewModel.onEvent(TranscriptExportEvent.ClipboardWriteFinished(success = true, showConfirmation = false))
        assertEquals(TranscriptExportResultState.Idle, viewModel.uiState.value.exportResult)

        // 書き込めなかったら失敗を表示する
        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        viewModel.effects.first()
        viewModel.onEvent(TranscriptExportEvent.ClipboardWriteFinished(success = false, showConfirmation = false))
        assertEquals(TranscriptExportResultState.Failed, viewModel.uiState.value.exportResult)
    }

    @Test
    fun `クリップボード_複数ファイルはファイル名の見出しを付けてつなげ長すぎるときは書き込まない`() = runTest {
        val sampleKo = SampleMaterials.jaKo
        val sampleZh = SampleMaterials.jaZh
        val repo = FakeLibraryRepository(initialItems = listOf(sampleKo.item, sampleZh.item))
        repo.exportedTranscriptTexts[sampleKo.item.id to TranscriptExportFormat.SRT] =
            "1\n00:00:00,000 --> 00:00:01,500\n안녕하세요\n"
        repo.exportedTranscriptTexts[sampleZh.item.id to TranscriptExportFormat.SRT] =
            "1\n00:00:00,000 --> 00:00:01,000\n你好\n"
        val viewModel = TranscriptExportViewModel(libraryRepository = repo)
        viewModel.open(listOf(sampleKo.item.id, sampleZh.item.id))
        viewModel.onEvent(TranscriptExportEvent.SelectFormat(TranscriptExportFormat.SRT))

        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        val effect = viewModel.effects.first()
        assertEquals(
            TranscriptExportEffect.WriteToClipboard(
                label = TranscriptExportViewModel.CLIPBOARD_LABEL,
                text = "=== ${sampleKo.item.title} ===\n1\n00:00:00,000 --> 00:00:01,500\n안녕하세요\n\n" +
                    "=== ${sampleZh.item.title} ===\n1\n00:00:00,000 --> 00:00:01,000\n你好",
            ),
            effect,
        )
        viewModel.onEvent(TranscriptExportEvent.ClipboardWriteFinished(success = true, showConfirmation = true))
        assertEquals(
            TranscriptExportResultState.CopiedToClipboard(count = 2, format = TranscriptExportFormat.SRT),
            viewModel.uiState.value.exportResult,
        )

        // 上限を超えると書き込みを頼まず、長すぎることを表示する
        repo.exportedTranscriptTexts[sampleZh.item.id to TranscriptExportFormat.SRT] =
            "あ".repeat(TranscriptExportViewModel.MAX_CLIPBOARD_CHARS + 1)
        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        assertEquals(TranscriptExportResultState.ClipboardTooLarge, viewModel.uiState.value.exportResult)
        assertFalse(viewModel.uiState.value.isCopyingToClipboard)
    }

    @Test
    fun `クリップボード_文字起こしがないときは書き込まず失敗を表示する`() = runTest {
        val sampleKo = SampleMaterials.jaKo
        val sampleZh = SampleMaterials.jaZh
        val repo = FakeLibraryRepository(initialItems = listOf(sampleKo.item, sampleZh.item))
        val viewModel = TranscriptExportViewModel(libraryRepository = repo)
        viewModel.open(listOf(sampleKo.item.id, sampleZh.item.id))

        viewModel.onEvent(TranscriptExportEvent.CopyToClipboard)
        assertEquals(TranscriptExportResultState.Failed, viewModel.uiState.value.exportResult)
        assertFalse(viewModel.uiState.value.isCopyingToClipboard)
    }
}
