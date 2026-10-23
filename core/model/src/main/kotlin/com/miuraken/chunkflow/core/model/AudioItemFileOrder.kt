package com.miuraken.chunkflow.core.model

import java.math.BigInteger
import java.net.URLDecoder

/*
 * 教材（音声ファイル）を並べる順番（ファイル名の昇順。ADR 0015）。
 *
 * ライブラリのファイルの一覧（フォルダの中・検索結果・タグや言語での絞り込み・ブックマークなど）と、
 * プレイヤーの「次のファイル」は、すべてここで決める順番を使う。手で並べ替えた順（`ItemState.fileSortOrder`）や、
 * 音声のタグから取ったタイトルの順は使わない。
 */

/**
 * 教材の並びのキー。[AudioItem.fileOrderKey] で作る。
 *
 * 比べ方は [AudioItemFileOrder] と同じ（ファイル名 → タイトル → ID）。一覧を並べるときは、要素ごとに 1 回だけ
 * キーを作ってから比べると、URI のデコードを比較のたびに繰り返さずに済む。
 *
 * @property fileName 並びに使うファイル名（拡張子を除く。[AudioItem.sortableFileName]）。
 * @property title 教材のタイトル（ファイル名が同じときに使う）。
 * @property id 教材の ID（最後の決め手）。
 */
data class AudioItemFileOrderKey(
    val fileName: String,
    val title: String,
    val id: String,
) : Comparable<AudioItemFileOrderKey> {
    override fun compareTo(other: AudioItemFileOrderKey): Int {
        val byFileName = compareFileNamesAscending(fileName, other.fileName)
        if (byFileName != 0) return byFileName
        val byTitle = compareFileNamesAscending(title, other.title)
        if (byTitle != 0) return byTitle
        return id.compareTo(other.id)
    }
}

/** この教材の並びのキー（[AudioItemFileOrderKey]）。 */
fun AudioItem.fileOrderKey(): AudioItemFileOrderKey = AudioItemFileOrderKey(
    fileName = sortableFileName(),
    title = title,
    id = id.value,
)

/**
 * 教材を並べる順番（ファイル名の昇順）。
 *
 * 1. ファイル名（拡張子を除く。[sortableFileName]）
 * 2. 同じならタイトル
 * 3. 最後に教材 ID
 *
 * 名前の比較は [compareFileNamesAscending]（数字を数値として比べ、大文字小文字を区別しない）。
 */
val AudioItemFileOrder: Comparator<AudioItem> = Comparator { a, b -> a.fileOrderKey().compareTo(b.fileOrderKey()) }

/**
 * 並びに使うファイル名（拡張子を除く）。
 *
 * 1. [AudioItem.displayName]（取り込み時に保存した元のファイル名）
 * 2. [AudioItem.localFilePath] の最後の名前
 * 3. [AudioItem.sourceUri] から取り出したファイル名（ローカルのストレージのドキュメント URI など）
 * 4. どれもわからなければ（Google ドライブで元のファイル名をまだ補っていないときなど）[AudioItem.title]
 */
fun AudioItem.sortableFileName(): String {
    val fileName = fileNameFromDisplayName(displayName)
        ?: fileNameFromLocalPath(localFilePath)
        ?: fileNameFromSourceUri(sourceUri)
    return fileName?.let(::removeFileExtension)?.takeIf { it.isNotBlank() } ?: title
}

/**
 * ファイル名の自然順（数字の並びを数値として比べ、大文字小文字を区別しない）の昇順の比較。
 *
 * 例: `Lesson 2` ＜ `lesson 10`、`A02` ＜ `b01`。数値が同じで桁数だけ違うとき（`01` と `1`）は短いほうを先にし、
 * 最後は文字列そのもので決める（同じ名前でなければ 0 を返さない）。
 */
fun compareFileNamesAscending(a: String, b: String): Int {
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
            val numCmp = BigInteger(a.substring(startA, i)).compareTo(BigInteger(b.substring(startB, j)))
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

/**
 * 拡張子とみなす末尾（英字を 1 文字以上含む 5 文字までの英数字）。
 *
 * `Lesson 1.5` の `.5` のように数字だけのものは拡張子とみなさない。
 */
private val FILE_EXTENSION_REGEX = Regex("""\.(?=[0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$""")

/** 保存してある元のファイル名 [displayName]。空なら `null`。念のためパスの区切りより後ろだけを使う。 */
internal fun fileNameFromDisplayName(displayName: String?): String? = displayName
    ?.let(::lastPathSegment)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/**
 * ローカルのファイルパス [path]（`/storage/emulated/0/Music/…/01.mp3`）の最後の名前。
 *
 * 絶対パスでないもの（プレビュー用の `preview:…` など）や空のときは `null`。
 */
internal fun fileNameFromLocalPath(path: String?): String? {
    val clean = path?.trim()?.takeIf { it.startsWith('/') } ?: return null
    return lastPathSegment(clean)
}

/**
 * 音声の元の URI [uri] からファイル名を取り出す。わからなければ `null`。
 *
 * - ローカルのストレージのドキュメント URI: ドキュメント ID（URL デコードした `primary:Music/…/01.mp3`）の最後の名前
 * - ドキュメント ID が `raw:/storage/…/01.mp3` の形（ダウンロードのプロバイダなど）: パスの最後の名前
 * - `file://…/01.mp3` と絶対パス: パスの最後の名前
 *
 * Google ドライブ（ドキュメント ID がファイル名を含まない文字列）、メディアのプロバイダの `audio:123`・`msf:123` などは
 * ファイル名がわからないので `null`。
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

/**
 * ファイル名 [fileName] から拡張子（`.mp3` など）を外す。拡張子がなければそのまま返す。
 *
 * 先頭がドットだけの名前（`.mp3`）は外さない。
 */
internal fun removeFileExtension(fileName: String): String {
    val match = FILE_EXTENSION_REGEX.find(fileName) ?: return fileName
    return if (match.range.first > 0) fileName.substring(0, match.range.first) else fileName
}

private fun lastPathSegment(path: String): String? = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }

/** URI の一部を URL デコードする（`+` は空白にしない）。壊れたエスケープならそのまま返す。 */
private fun decodeUriComponent(encoded: String): String = runCatching {
    URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
}.getOrDefault(encoded)
