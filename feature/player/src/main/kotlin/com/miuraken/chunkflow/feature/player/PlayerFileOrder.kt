package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.fileOrderKey

/*
 * 同じフォルダの中のファイルの順番。プレイヤーの「次のファイル」に使う。
 *
 * 並べ方は core:model の `AudioItemFileOrder`（ファイル名の昇順 → タイトル → ID。ADR 0015）で、ライブラリの一覧と
 * 同じ。ライブラリで見えている順の次のファイルへ進む。
 */

/**
 * [current] と同じフォルダにあるファイルを、ライブラリと同じ順番（[sortedInLibraryFileOrder]）に並べたときの、
 * 次のファイルの ID。最後のファイルなら `null`。
 *
 * フォルダは [AudioItem.course]（前後の `/` を除いたもの）で比べる。
 */
internal fun nextMediaIdInSameFolder(current: AudioItem, allItems: List<AudioItem>): MediaId? {
    val folder = normalizedFolderPath(current.course)
    val sameFolder = sortedInLibraryFileOrder(allItems.filter { normalizedFolderPath(it.course) == folder })
    val index = sameFolder.indexOfFirst { it.id == current.id }
    return if (index >= 0) sameFolder.getOrNull(index + 1)?.id else null
}

/**
 * [items] を、ライブラリにファイルを並べる順番（core:model の `AudioItemFileOrder` と同じ）に並べる。
 *
 * 並びのキー（[fileOrderKey]）は要素ごとに 1 回だけ作ってから比べる（比べるたびに URI をデコードし直さない）。
 */
internal fun sortedInLibraryFileOrder(items: List<AudioItem>): List<AudioItem> = items
    .map { item -> item to item.fileOrderKey() }
    .sortedBy { (_, key) -> key }
    .map { (item, _) -> item }

private fun normalizedFolderPath(course: String?): String? = course?.trim('/')?.takeIf { it.isNotBlank() }
