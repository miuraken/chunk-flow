package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import java.math.BigInteger
import java.net.URLDecoder

/*
 * 同じフォルダの中のファイルの順番。プレイヤーの「次のファイル」に使う。
 *
 * ライブラリの並び（feature:library の `LibraryFileOrder.kt`）と同じ決め方にして、ライブラリで見えている順の次の
 * ファイルへ進むようにしている。変えるときは `LibraryFileOrder.kt` と合わせて変える。
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
 * [items] を、ライブラリにファイルを並べる順番に並べる。
 *
 * 1. ファイル名（拡張子を除く。[sortableFileNameOf]）の昇順
 * 2. 同じならタイトルの昇順
 * 3. 最後に教材 ID
 *
 * 名前の比較は [compareFileNamesAscending]（数字を数値として比べ、大文字小文字を区別しない）。
 */
internal fun sortedInLibraryFileOrder(items: List<AudioItem>): List<AudioItem> = items
    .map { item -> item to sortableFileNameOf(item) }
    .sortedWith { (a, aFileName), (b, bFileName) ->
        val byFileName = compareFileNamesAscending(aFileName, bFileName)
        if (byFileName != 0) return@sortedWith byFileName
        val byTitle = compareFileNamesAscending(a.title, b.title)
        if (byTitle != 0) byTitle else a.id.value.compareTo(b.id.value)
    }
    .map { (item, _) -> item }

private fun normalizedFolderPath(course: String?): String? = course?.trim('/')?.takeIf { it.isNotBlank() }

/**
 * ファイル名の自然順（数値部分を数値として比較し、大文字小文字を区別しない）の昇順比較関数。
 */
internal fun compareFileNamesAscending(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            while (i < a.length && a[i].isDigit()) i++
            val startB = j
            while (j < b.length && b[j].isDigit()) j++
            val numA = BigInteger(a.substring(startA, i))
            val numB = BigInteger(b.substring(startB, j))
            val numCmp = numA.compareTo(numB)
            if (numCmp != 0) return numCmp
            val lenCmp = (i - startA).compareTo(j - startB)
            if (lenCmp != 0) return lenCmp
        } else {
            val lowerA = ca.lowercaseChar()
            val lowerB = cb.lowercaseChar()
            if (lowerA != lowerB) return lowerA.compareTo(lowerB)
            i++
            j++
        }
    }
    val lenDiff = a.length.compareTo(b.length)
    return if (lenDiff != 0) lenDiff else a.compareTo(b)
}

/** ローカルのストレージの SAF プロバイダ（ドキュメント ID が `primary:Music/…/01.mp3` の形）。 */
private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

/** ドキュメント URI のうち、ドキュメント ID の前に付く部分。 */
private const val DOCUMENT_PATH_MARKER = "/document/"

/** 拡張子とみなす末尾（英字を 1 文字以上含む 5 文字までの英数字）。 */
private val FILE_EXTENSION_REGEX = Regex("""\.(?=[0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$""")

/**
 * 教材 [item] の、並びに使うファイル名（拡張子を除く）。
 *
 * [AudioItem.localFilePath] があればその最後の名前を、なければ [AudioItem.sourceUri] から取り出す。
 * Google ドライブのように URI からファイル名がわからないときは [AudioItem.title] を使う。
 */
internal fun sortableFileNameOf(item: AudioItem): String {
    val fileName = fileNameFromLocalPath(item.localFilePath) ?: fileNameFromSourceUri(item.sourceUri)
    return fileName?.let(::removeFileExtension)?.takeIf { it.isNotBlank() } ?: item.title
}

/** ローカルのファイルパス [path] の最後の名前。絶対パスでないときや空のときは `null`。 */
internal fun fileNameFromLocalPath(path: String?): String? {
    val clean = path?.trim()?.takeIf { it.startsWith('/') } ?: return null
    return lastPathSegment(clean)
}

/**
 * 音声の元の URI [uri] からファイル名を取り出す。わからなければ `null`。
 *
 * ローカルのストレージのドキュメント URI（ドキュメント ID `primary:Music/…/01.mp3`）、ドキュメント ID が `raw:/…` の形、
 * `file://` に対応する。Google ドライブやメディアのプロバイダ（`audio:123` など）はファイル名がわからないので `null`。
 */
internal fun fileNameFromSourceUri(uri: String?): String? {
    val raw = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.startsWith('/')) return lastPathSegment(raw)
    if (raw.startsWith("file://")) {
        return lastPathSegment(decodeUriComponent(raw.removePrefix("file://").substringBefore('?').substringBefore('#')))
    }
    if (!raw.startsWith("content://")) return null
    val authority = raw.removePrefix("content://").substringBefore('/')
    val markerIndex = raw.lastIndexOf(DOCUMENT_PATH_MARKER)
    if (markerIndex < 0) return null
    val encodedDocumentId = raw.substring(markerIndex + DOCUMENT_PATH_MARKER.length)
        .substringBefore('?')
        .substringBefore('#')
    val documentId = decodeUriComponent(encodedDocumentId)
    return when {
        documentId.startsWith("raw:") -> lastPathSegment(documentId.removePrefix("raw:"))

        authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY && ':' in documentId ->
            lastPathSegment(documentId.substringAfter(':'))

        else -> null
    }
}

/** ファイル名 [fileName] から拡張子（`.mp3` など）を外す。拡張子がなければそのまま返す。 */
internal fun removeFileExtension(fileName: String): String {
    val match = FILE_EXTENSION_REGEX.find(fileName) ?: return fileName
    return if (match.range.first > 0) fileName.substring(0, match.range.first) else fileName
}

private fun lastPathSegment(path: String): String? = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }

/** URI の一部を URL デコードする（`+` は空白にしない）。壊れたエスケープならそのまま返す。 */
private fun decodeUriComponent(encoded: String): String = runCatching {
    URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
}.getOrDefault(encoded)
