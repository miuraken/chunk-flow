package com.miuraken.chunkflow.feature.library

/**
 * ライブラリにファイルを並べる順番（ファイル名の昇順。ADR 0015）。
 *
 * フォルダの中・検索結果のフォルダごとの並び・タグや言語での絞り込みの結果・ブックマークなど、ライブラリにファイルを
 * 並べる場所はすべてこれを使う。手で並べ替えた順（`ItemState.fileSortOrder`）は使わない。
 *
 * 決め方は core:model の `AudioItemFileOrder`（ファイル名 → タイトル → ID）と同じで、プレイヤーの「次のファイル」とも
 * 共通。キー（[LibraryItemUiModel.fileOrderKey]）は UI モデルを作るときに 1 回だけ作ってあるので、比べるたびに
 * URI をデコードし直さない。
 */
internal val LibraryFileOrder: Comparator<LibraryItemUiModel> = compareBy { it.fileOrderKey }
