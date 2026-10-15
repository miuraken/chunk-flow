package com.miuraken.chunkflow.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF のフォルダ選択（`OpenDocumentTree`）で指定されたディレクトリの走査結果。
 *
 * @property directoryName 選択されたディレクトリの表示名。
 * @property documentUris ディレクトリ配下および `transcription/` サブフォルダ配下の対象ファイル URI 一覧。
 */
internal data class ScannedDirectory(
    val directoryName: String?,
    val documentUris: List<Uri>,
)

/**
 * `OpenDocumentTree` で選ばれたツリー URI から、音声ファイルと `transcription/<音声名>.chunkflow.json` などの
 * 対象ドキュメント URI 一覧を走査して返す。
 */
internal fun scanDocumentTree(
    contentResolver: ContentResolver,
    treeUri: Uri,
): ScannedDirectory {
    val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return ScannedDirectory(directoryName = null, documentUris = emptyList())
    val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    val directoryName = queryDisplayName(contentResolver, rootDocUri)
    val collected = mutableListOf<Uri>()
    collectChildDocuments(
        contentResolver = contentResolver,
        treeUri = treeUri,
        parentDocumentId = rootDocId,
        depth = 0,
        maxDepth = 3,
        outUris = collected,
    )
    return ScannedDirectory(
        directoryName = directoryName,
        documentUris = collected,
    )
}

private fun queryDisplayName(
    contentResolver: ContentResolver,
    documentUri: Uri,
): String? = runCatching {
    contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    }
}.getOrNull()

private fun collectChildDocuments(
    contentResolver: ContentResolver,
    treeUri: Uri,
    parentDocumentId: String,
    depth: Int,
    maxDepth: Int,
    outUris: MutableList<Uri>,
) {
    if (depth > maxDepth) return
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    runCatching {
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1).orEmpty()
                val mimeType = cursor.getString(2).orEmpty()
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectChildDocuments(
                        contentResolver = contentResolver,
                        treeUri = treeUri,
                        parentDocumentId = docId,
                        depth = depth + 1,
                        maxDepth = maxDepth,
                        outUris = outUris,
                    )
                } else if (isSupportedImportFile(displayName = displayName, mimeType = mimeType)) {
                    outUris.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                }
            }
        }
    }
}

private fun isSupportedImportFile(displayName: String, mimeType: String): Boolean {
    if (mimeType.startsWith("audio/")) return true
    if (mimeType == "application/json") return true
    val lower = displayName.lowercase()
    return lower.endsWith(".mp3") ||
        lower.endsWith(".m4a") ||
        lower.endsWith(".aac") ||
        lower.endsWith(".ogg") ||
        lower.endsWith(".opus") ||
        lower.endsWith(".flac") ||
        lower.endsWith(".wav") ||
        lower.endsWith(".json") ||
        lower.endsWith(".srt") ||
        lower.endsWith(".vtt") ||
        lower.endsWith(".lrc")
}
