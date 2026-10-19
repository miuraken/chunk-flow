package com.miuraken.chunkflow.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * SAF のファイル選択ダイアログ（`EXTRA_INITIAL_URI`）で既定のフォールバック先とする sdcard ルートのドキュメント URI 文字列。
 */
internal const val SDCARD_ROOT_DOCUMENT_URI_STRING: String =
    "content://com.android.externalstorage.documents/document/primary%3A"

/**
 * SAF のフォルダ選択（`OpenDocumentTree`）で指定されたディレクトリの走査結果。
 *
 * @property directoryName 選択されたディレクトリの表示名。
 * @property documentUris ディレクトリ配下の対象音声ファイル URI 一覧。
 */
internal data class ScannedDirectory(
    val directoryName: String?,
    val documentUris: List<Uri>,
)

/**
 * `OpenDocumentTree` で選ばれたツリー URI から、音声ファイルのドキュメント URI 一覧を走査して返す。
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
        maxDepth = 6,
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
    val lower = displayName.lowercase()
    return lower.endsWith(".mp3") ||
        lower.endsWith(".m4a") ||
        lower.endsWith(".aac") ||
        lower.endsWith(".ogg") ||
        lower.endsWith(".opus") ||
        lower.endsWith(".flac") ||
        lower.endsWith(".wav")
}

/**
 * ファイル URI から親フォルダの名称を抽出する。
 *
 * SAF ドキュメント URI（`primary:Music/Korean/01.mp3` など）、`raw:/...`、`file://` URI、
 * または [contentResolver] の `relative_path` / `_data` / `bucket_display_name` 列から親ディレクトリ名を特定する。
 */
internal fun extractParentFolderNameFromUri(
    uri: Uri,
    contentResolver: ContentResolver? = null,
): String? {
    val docId = extractDocumentIdFromUri(uri)
    if (docId != null) {
        if (docId.startsWith("raw:")) {
            val rawPath = docId.removePrefix("raw:").trimEnd('/')
            val segments = rawPath.split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                val parentPath = "/" + segments.dropLast(1).joinToString("/")
                if (parentPath == "/storage/emulated/0" || parentPath == "/sdcard") {
                    return "sdcard"
                }
                return segments[segments.size - 2]
            }
        } else if (':' in docId) {
            val prefix = docId.substringBefore(':')
            val relPath = docId.substringAfter(':').trim('/')
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                return segments[segments.size - 2]
            } else if (segments.size == 1) {
                when {
                    prefix.equals("primary", ignoreCase = true) -> return "sdcard"
                    prefix.equals("home", ignoreCase = true) -> return "Documents"
                    prefix.isNotBlank() && prefix !in NON_DIRECTORY_DOC_PREFIXES -> return prefix
                }
            }
        }
    }

    if (contentResolver != null) {
        queryParentFolderNameFromResolver(contentResolver, uri)?.let { return it }
    }

    val uriStr = uri.toString().trim()
    if (uriStr.startsWith("file://")) {
        val path = safeUrlDecode(uriStr.removePrefix("file://").substringBefore('?').substringBefore('#'))
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size >= 2) {
            val parentPath = "/" + segments.dropLast(1).joinToString("/")
            if (parentPath == "/storage/emulated/0" || parentPath == "/sdcard") {
                return "sdcard"
            }
            return segments[segments.size - 2]
        }
    }

    val pathAfterAuthority = uriStr
        .substringAfter("://", "")
        .substringAfter('/', "")
        .substringBefore('?')
        .substringBefore('#')
    if (pathAfterAuthority.isNotBlank()) {
        val decodedSegments = pathAfterAuthority
            .split('/')
            .map(::safeUrlDecode)
            .filter { it.isNotBlank() }
        if (decodedSegments.size >= 2) {
            val parentCandidate = decodedSegments[decodedSegments.size - 2]
            if (parentCandidate !in GENERIC_URI_PATH_SEGMENTS && ':' !in parentCandidate) {
                return parentCandidate
            }
        }
    }
    return null
}

/**
 * ファイル URI から親ディレクトリの SAF ドキュメント URI 文字列を抽出する。
 */
internal fun extractParentDirectoryUriFromFileUri(
    uri: Uri,
    contentResolver: ContentResolver? = null,
): String? {
    val docId = extractDocumentIdFromUri(uri)
    if (docId != null) {
        if (docId.startsWith("raw:")) {
            val rawPath = docId.removePrefix("raw:")
            val parentDir = File(rawPath).parent?.takeIf { it.isNotBlank() }
            if (parentDir != null) {
                return localDirectoryPathToSafDocumentUriString(parentDir)
            }
        } else if (':' in docId) {
            val prefix = docId.substringBefore(':')
            val relPath = docId.substringAfter(':').trim('/')
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                val parentRel = segments.dropLast(1).joinToString("/")
                return buildSafExternalStorageDocumentUriString("$prefix:$parentRel")
            } else if (segments.size == 1) {
                if (prefix.equals("primary", ignoreCase = true)) {
                    return SDCARD_ROOT_DOCUMENT_URI_STRING
                } else if (prefix.equals("home", ignoreCase = true)) {
                    return buildSafExternalStorageDocumentUriString("primary:Documents")
                }
            }
        }
    }

    if (contentResolver != null) {
        queryParentDirectoryUriFromResolver(contentResolver, uri)?.let { return it }
    }

    val uriStr = uri.toString().trim()
    if (uriStr.startsWith("file://")) {
        val filePath = safeUrlDecode(uriStr.removePrefix("file://").substringBefore('?').substringBefore('#'))
        val parentDir = File(filePath).parent?.takeIf { it.isNotBlank() }
        if (parentDir != null) {
            return localDirectoryPathToSafDocumentUriString(parentDir)
        }
    }
    return null
}

/**
 * フォルダの `treeUri`（またはドキュメント URI / ローカルパス文字列）と相対サブパスから、
 * SAF の `EXTRA_INITIAL_URI` に渡すドキュメント URI 文字列とローカルディレクトリパス（存在確認用）のペアを返す。
 */
internal fun resolveSafDirectoryUriAndLocalPathFromFolderUri(
    uriString: String,
    subPath: String = "",
): Pair<String, String?>? {
    val raw = uriString.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanSub = subPath.trim('/')
    val baseDocId = extractTreeOrDocIdFromUriString(raw)
    if (baseDocId != null) {
        if (baseDocId.startsWith("raw:")) {
            val baseLocal = baseDocId.removePrefix("raw:").trimEnd('/')
            val fullLocal = if (cleanSub.isNotBlank()) "$baseLocal/$cleanSub" else baseLocal
            return localDirectoryPathToSafDocumentUriString(fullLocal) to fullLocal
        } else if (':' in baseDocId) {
            val prefix = baseDocId.substringBefore(':')
            val baseRel = baseDocId.substringAfter(':').trim('/')
            val fullRel = listOf(baseRel, cleanSub).filter { it.isNotBlank() }.joinToString("/")
            val fullDocId = "$prefix:$fullRel"
            val safUri = buildSafExternalStorageDocumentUriString(fullDocId)
            val localPath = if (prefix.equals("primary", ignoreCase = true)) {
                if (fullRel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$fullRel"
            } else {
                null
            }
            return safUri to localPath
        }
    }

    if (raw.startsWith("file://") || raw.startsWith("/")) {
        val baseLocal = if (raw.startsWith("file://")) {
            safeUrlDecode(raw.removePrefix("file://").substringBefore('?').substringBefore('#'))
        } else {
            raw
        }.trimEnd('/')
        if (baseLocal.isNotBlank()) {
            val fullLocal = if (cleanSub.isNotBlank()) "$baseLocal/$cleanSub" else baseLocal
            return localDirectoryPathToSafDocumentUriString(fullLocal) to fullLocal
        }
    }
    return null
}

/**
 * 教材ファイルの `localFilePath` または `sourceUri` から、そのファイルが置かれていた親ディレクトリの
 * SAF ドキュメント URI 文字列とローカルディレクトリパス（存在確認用）のペアを返す。
 */
internal fun resolveParentSafDirectoryUriAndLocalPathFromFile(
    localFilePath: String?,
    sourceUri: String?,
): Pair<String, String?>? {
    val cleanLocal = localFilePath?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("preview:") }
    if (cleanLocal != null) {
        val parentDir = File(cleanLocal).parent?.takeIf { it.isNotBlank() }
        if (parentDir != null) {
            return localDirectoryPathToSafDocumentUriString(parentDir) to parentDir
        }
    }

    val cleanSource = sourceUri?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("preview:") }
        ?: return null
    val docId = extractDocumentIdFromUriString(cleanSource)
    if (docId != null) {
        if (docId.startsWith("raw:")) {
            val parentDir = File(docId.removePrefix("raw:")).parent?.takeIf { it.isNotBlank() }
            if (parentDir != null) {
                return localDirectoryPathToSafDocumentUriString(parentDir) to parentDir
            }
        } else if (':' in docId) {
            val prefix = docId.substringBefore(':')
            val relPath = docId.substringAfter(':').trim('/')
            val segments = relPath.split('/').filter { it.isNotBlank() }
            if (segments.isNotEmpty()) {
                val parentRel = if (segments.size >= 2) segments.dropLast(1).joinToString("/") else ""
                val parentDocId = if (prefix.equals("home", ignoreCase = true) && parentRel.isEmpty()) {
                    "primary:Documents"
                } else {
                    "$prefix:$parentRel"
                }
                val safUri = buildSafExternalStorageDocumentUriString(parentDocId)
                val localPath = when {
                    parentDocId.startsWith("primary:", ignoreCase = true) -> {
                        val rel = parentDocId.substringAfter(':')
                        if (rel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
                    }

                    else -> null
                }
                return safUri to localPath
            }
        }
    }

    if (cleanSource.startsWith("file://") || cleanSource.startsWith("/")) {
        val filePath = if (cleanSource.startsWith("file://")) {
            safeUrlDecode(cleanSource.removePrefix("file://").substringBefore('?').substringBefore('#'))
        } else {
            cleanSource
        }
        val parentDir = File(filePath).parent?.takeIf { it.isNotBlank() }
        if (parentDir != null) {
            return localDirectoryPathToSafDocumentUriString(parentDir) to parentDir
        }
    }
    return null
}

internal fun localDirectoryPathToSafDocumentUriString(dirPath: String): String {
    val normalized = dirPath.trim().trimEnd('/').ifEmpty { "/" }
    return when {
        normalized == "/storage/emulated/0" || normalized == "/sdcard" ->
            SDCARD_ROOT_DOCUMENT_URI_STRING

        normalized.startsWith("/storage/emulated/0/") -> {
            val rel = normalized.removePrefix("/storage/emulated/0/").trim('/')
            buildSafExternalStorageDocumentUriString("primary:$rel")
        }

        normalized.startsWith("/sdcard/") -> {
            val rel = normalized.removePrefix("/sdcard/").trim('/')
            buildSafExternalStorageDocumentUriString("primary:$rel")
        }

        else -> buildSafExternalStorageDocumentUriString("raw:$normalized")
    }
}

internal fun buildSafExternalStorageDocumentUriString(docId: String): String {
    val encoded = runCatching {
        URLEncoder.encode(docId, Charsets.UTF_8.name()).replace("+", "%20")
    }.getOrDefault(docId)
    return "content://com.android.externalstorage.documents/document/$encoded"
}

internal fun defaultDirectoryExists(path: String): Boolean {
    if (path.startsWith("/storage/emulated/0")) {
        val sdcardRoot = File("/storage/emulated/0")
        if (!sdcardRoot.exists()) {
            return true
        }
    }
    return File(path).isDirectory
}

private fun extractDocumentIdFromUri(uri: Uri): String? {
    runCatching { DocumentsContract.getDocumentId(uri) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return extractDocumentIdFromUriString(uri.toString())
}

private fun extractDocumentIdFromUriString(uriString: String): String? {
    val raw = uriString.trim()
    val marker = "/document/"
    val idx = raw.lastIndexOf(marker)
    if (idx >= 0) {
        val encoded = raw.substring(idx + marker.length).substringBefore('?').substringBefore('#')
        if (encoded.isNotBlank()) {
            return safeUrlDecode(encoded)
        }
    }
    return null
}

private fun extractTreeOrDocIdFromUriString(uriString: String): String? {
    extractDocumentIdFromUriString(uriString)?.let { return it }
    val raw = uriString.trim()
    val marker = "/tree/"
    val idx = raw.indexOf(marker)
    if (idx >= 0) {
        val afterTree = raw.substring(idx + marker.length)
            .substringBefore("/document/")
            .substringBefore('?')
            .substringBefore('#')
        if (afterTree.isNotBlank()) {
            return safeUrlDecode(afterTree)
        }
    }
    return null
}

private fun safeUrlDecode(encoded: String): String = runCatching { Uri.decode(encoded) }.getOrNull()
    ?: runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name()) }
        .getOrDefault(encoded)

private fun queryParentFolderNameFromResolver(
    contentResolver: ContentResolver,
    uri: Uri,
): String? = runCatching {
    contentResolver.query(
        uri,
        arrayOf("relative_path", "_data", "bucket_display_name"),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val relIdx = cursor.getColumnIndex("relative_path")
        if (relIdx >= 0 && !cursor.isNull(relIdx)) {
            val rel = cursor.getString(relIdx).orEmpty().trim('/')
            if (rel.isNotEmpty()) {
                return@use rel.split('/').lastOrNull { it.isNotBlank() }
            }
        }
        val dataIdx = cursor.getColumnIndex("_data")
        if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
            val dataPath = cursor.getString(dataIdx).orEmpty()
            val parent = File(dataPath).parentFile
            if (parent != null) {
                if (parent.absolutePath == "/storage/emulated/0" || parent.absolutePath == "/sdcard") {
                    return@use "sdcard"
                }
                parent.name.takeIf { it.isNotBlank() }?.let { return@use it }
            }
        }
        val bucketIdx = cursor.getColumnIndex("bucket_display_name")
        if (bucketIdx >= 0 && !cursor.isNull(bucketIdx)) {
            cursor.getString(bucketIdx)?.trim()?.takeIf { it.isNotBlank() }?.let { return@use it }
        }
        null
    }
}.getOrNull()

private fun queryParentDirectoryUriFromResolver(
    contentResolver: ContentResolver,
    uri: Uri,
): String? = runCatching {
    contentResolver.query(
        uri,
        arrayOf("relative_path", "_data"),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val relIdx = cursor.getColumnIndex("relative_path")
        if (relIdx >= 0 && !cursor.isNull(relIdx)) {
            val rel = cursor.getString(relIdx).orEmpty().trim('/')
            return@use if (rel.isEmpty()) {
                SDCARD_ROOT_DOCUMENT_URI_STRING
            } else {
                buildSafExternalStorageDocumentUriString("primary:$rel")
            }
        }
        val dataIdx = cursor.getColumnIndex("_data")
        if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
            val dataPath = cursor.getString(dataIdx).orEmpty()
            val parentDir = File(dataPath).parent?.takeIf { it.isNotBlank() }
            if (parentDir != null) {
                return@use localDirectoryPathToSafDocumentUriString(parentDir)
            }
        }
        null
    }
}.getOrNull()

private val NON_DIRECTORY_DOC_PREFIXES = setOf("msf", "audio", "document", "video", "image", "downloads")
private val GENERIC_URI_PATH_SEGMENTS = setOf(
    "document",
    "tree",
    "audio",
    "media",
    "external",
    "external_primary",
    "files",
    "downloads",
)
