package com.miuraken.chunkflow.spike.textai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CSV の 1 行。**教材の本文・訳・校正の案の文は持たない**（文字数だけ。AGENTS.md §10）。
 *
 * 文字列の列は、このアプリが決めた記号（列挙の名前・言語コード・モデル名）だけにする。
 * 列の意味は `docs/spikes/S4-mlkit-text.md` の「CSV の列」を参照。
 */
internal data class CsvRow(
    val event: CsvEvent,
    val batch: String? = null,
    val fileKind: FileKind? = null,
    val fileId: String? = null,
    val target: String? = null,
    val base: String? = null,
    val chunk: Int? = null,
    val chunkLang: String? = null,
    val langSource: LanguageSource? = null,
    val method: Method? = null,
    val methods: String? = null,
    val first: Boolean? = null,
    val elapsedMs: Long? = null,
    val result: ResultKind? = null,
    val errorCode: Int? = null,
    val inputChars: Int? = null,
    val outputChars: Int? = null,
    val changed: Boolean? = null,
    val suggestions: Int? = null,
    val finish: String? = null,
    val rating: String? = null,
    val count: Int? = null,
    val successCount: Int? = null,
    val foreground: Boolean? = null,
    val screenOn: Boolean? = null,
    val bytes: Long? = null,
    val featureStatus: String? = null,
    val model: String? = null,
)

/** CSV の `event` 列。 */
internal enum class CsvEvent {
    SESSION_START,
    FILE_LOADED,
    LANGUAGES,
    STATUS,
    DOWNLOAD_START,
    DOWNLOAD_END,
    RUN,
    RATING,
    BATCH_START,
    BATCH_END,
    APP_FOREGROUND,
    APP_BACKGROUND,
    RESULTS_CLEARED,
    SUMMARY,
}

/** CSV の値を書く（カンマ・引用符・改行を含むときは引用符で囲む）。 */
internal fun csvField(value: String): String {
    val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

/** [CsvRow] を、[CSV_HEADER] の列の順の 1 行にする。 */
internal fun csvLine(time: String, session: String, row: CsvRow): String = listOf(
    time,
    session,
    row.event.name,
    row.batch,
    row.fileKind?.name,
    row.fileId,
    row.target,
    row.base,
    row.chunk?.toString(),
    row.chunkLang,
    row.langSource?.name,
    row.method?.code,
    row.methods,
    row.first?.toString(),
    row.elapsedMs?.toString(),
    row.result?.name,
    row.errorCode?.toString(),
    row.inputChars?.toString(),
    row.outputChars?.toString(),
    row.changed?.toString(),
    row.suggestions?.toString(),
    row.finish,
    row.rating,
    row.count?.toString(),
    row.successCount?.toString(),
    row.foreground?.toString(),
    row.screenOn?.toString(),
    row.bytes?.toString(),
    row.featureStatus,
    row.model,
).joinToString(",") { csvField(it.orEmpty()) }

/** CSV の見出しの行。 */
internal const val CSV_HEADER: String =
    "time,session,event,batch,fileKind,fileId,target,base,chunk,chunkLang,langSource,method,methods,first,elapsedMs," +
        "result,errorCode,inputChars,outputChars,changed,suggestions,finish,rating,count,successCount,foreground,screenOn," +
        "bytes,featureStatus,model"

/**
 * 計測の記録を、アプリ専用の領域（`getExternalFilesDir`）の CSV に追記する。
 *
 * - 行は呼んだときの時刻で作り、1 本の直列の Dispatcher で呼んだ順に書く（呼び出し側は待たない）。
 * - 1 行ごとに書き込んで閉じるので、「全部を順に実行」の途中で画面を消したり、アプリが止められたりしても、そこまでの行は残る。
 *
 * @param scope 書き込みを走らせるスコープ（ViewModel のスコープ）。
 */
internal class SpikeRecorder(
    private val directory: File,
    ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    /** この起動の識別子（CSV の `session` 列）。 */
    val sessionId: String = UUID.randomUUID().toString().take(SESSION_ID_LENGTH)

    private val serialDispatcher = ioDispatcher.limitedParallelism(1)
    private val startStamp: String = now().format(FILE_STAMP)
    private val eventsFile = File(directory, "s4-events-$startStamp-$sessionId.csv")

    /** 画面に出す保存先（端末内のパス。教材の名前は含まない）。 */
    val directoryPath: String get() = directory.absolutePath

    /** 1 行を追記する。 */
    fun record(row: CsvRow) {
        val line = csvLine(timestamp(), sessionId, row)
        scope.launch(serialDispatcher) { appendLines(eventsFile, listOf(line)) }
    }

    /** 集計（チャンクと方式ごとの最新の結果と評価）を別の CSV に書く。書いたファイル名を返す。 */
    suspend fun writeSummary(rows: List<CsvRow>): String? {
        val time = timestamp()
        val file = File(directory, "s4-summary-${now().format(FILE_STAMP)}-$sessionId.csv")
        val lines = rows.map { csvLine(time, sessionId, it) }
        return withContext(serialDispatcher) { if (appendLines(file, lines)) file.name else null }
    }

    private fun appendLines(file: File, lines: List<String>): Boolean = try {
        if (!directory.exists()) directory.mkdirs()
        val isNew = !file.exists()
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            if (isNew) writer.write(CSV_HEADER + "\n")
            for (line in lines) writer.write(line + "\n")
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun timestamp(): String = now().truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private companion object {
        const val SESSION_ID_LENGTH = 8
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    }
}
