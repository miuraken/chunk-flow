package com.miuraken.chunkflow.spike.playback.picker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * システムのファイル選択画面（SAF）で選んだものを調べる（S2 の 4: SAF での選択）。
 *
 * 表示名・MIME（`ContentResolver.getType` と Document の列の両方）・サイズ・プロバイダ（authority）を出す。
 * スパイク専用。本体ではファイル名・URI をリリースビルドのログに出さない（§8）。
 */
object PickerInspector {
    /** OpenMultipleDocuments で選ばれた URI を調べる。 */
    fun describeDocuments(context: Context, uris: List<Uri>): String = buildString {
        appendLine("選ばれた数: ${uris.size}")
        uris.forEachIndexed { i, uri ->
            appendLine("#$i authority=${uri.authority}")
            appendLine("   getType=${context.contentResolver.getType(uri)}")
            queryDocument(context, uri).forEach { (k, v) -> appendLine("   $k=$v") }
        }
    }

    /** OpenDocumentTree で選ばれたフォルダの中身を 2 階層まで調べる。 */
    fun describeTree(context: Context, treeUri: Uri): String = buildString {
        appendLine("tree authority=${treeUri.authority}")
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        listChildren(context, treeUri, rootId, depth = 0, out = this)
    }

    private fun listChildren(context: Context, treeUri: Uri, parentId: String, depth: Int, out: StringBuilder) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val indent = "  ".repeat(depth + 1)
        val cursor = runCatching { context.contentResolver.query(childrenUri, projection, null, null, null) }
            .getOrElse {
                out.appendLine("${indent}一覧できない: ${it.javaClass.simpleName}: ${it.message}")
                return
            }
        if (cursor == null) {
            out.appendLine("${indent}一覧できない（cursor=null）")
            return
        }
        cursor.use { c ->
            out.appendLine("$indent(${c.count} 件)")
            var n = 0
            while (c.moveToNext() && n < MAX_ENTRIES) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2)
                val size = if (c.isNull(3)) null else c.getLong(3)
                out.appendLine("$indent- $name  mime=$mime  size=$size")
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR && depth < 1) {
                    listChildren(context, treeUri, id, depth + 1, out)
                }
                n++
            }
        }
    }

    private fun queryDocument(context: Context, uri: Uri): List<Pair<String, String?>> {
        val result = ArrayList<Pair<String, String?>>()
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (col in c.columnNames) {
                        val idx = c.getColumnIndex(col)
                        val value = runCatching { c.getString(idx) }.getOrNull()
                        if (col in INTERESTING) result += col to value
                    }
                }
            }
        }.onFailure { result += "query" to "${it.javaClass.simpleName}: ${it.message}" }
        return result
    }

    private const val MAX_ENTRIES = 50
    private val INTERESTING = setOf(
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
}
