package com.miuraken.chunkflow.core.testing.fixture

import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.SyncError
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.model.AudioItem

/** ライブラリ画面向けのフィクスチャ（§10.5.1）。 */
object SampleLibraries {
    /** 空のライブラリ。 */
    val empty: List<AudioItem> = emptyList()

    /** 複数のコースにまたがるライブラリ（コースなしの教材を含む）。 */
    val multipleCourses: List<AudioItem> = SampleMaterials.all.map { it.item }

    /** 取り込みの進捗の例。 */
    val importProgress: List<ImportProgress> = listOf(
        ImportProgress.Copying(null),
        ImportProgress.Copying(0.35f),
        ImportProgress.Done(listOf(SampleMaterials.jaKo.item.id), duplicates = 1, transcriptsAttached = 1),
        ImportProgress.Failed(ImportError.NO_SPACE),
    )

    /** 同期中 / 同期の失敗の例。 */
    val syncProgress: List<SyncProgress> = listOf(
        SyncProgress.Listing,
        SyncProgress.Downloading(done = 2, total = 5),
        SyncProgress.Done(newAudio = 3, newTranscripts = 2, failed = 0),
        SyncProgress.Failed(SyncError.NOT_AUTHORIZED),
        SyncProgress.Failed(SyncError.NETWORK),
    )
}
