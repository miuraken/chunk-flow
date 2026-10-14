package com.miuraken.chunkflow.spike.playback

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * スパイクの計測ログ。logcat（タグ `S2`）と画面に出し、結果のファイルは `files/results/` に書く。
 *
 * スパイク専用（デバッグ用）。本体ではファイル名・URI をリリースビルドのログに出さない（§8）。
 */
object SpikeLog {
    private const val TAG = "S2"
    private const val MAX_LINES = 400

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    @Volatile private var logFile: File? = null

    /** 画面に表示する最新のログ行。 */
    val lines: StateFlow<List<String>> = _lines

    /** ログを `files/results/log.txt` にも追記するようにする（logcat は共用の端末で消されることがあるため）。 */
    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "results").apply { mkdirs() }
        logFile = File(dir, "log.txt")
    }

    /** 1 行を記録する（どのスレッドから呼んでもよい）。 */
    fun i(message: String) {
        Log.i(TAG, message)
        _lines.update { (it + message).takeLast(MAX_LINES) }
        logFile?.let { f ->
            synchronized(this) {
                runCatching { f.appendText("${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} $message\n") }
            }
        }
    }

    /** 画面のログを消す。 */
    fun clear() {
        _lines.value = emptyList()
    }

    /** 結果を `files/results/<prefix>-<時刻>.<ext>` に書き、そのファイルを返す。 */
    fun writeResult(context: Context, prefix: String, ext: String, content: String): File {
        val dir = File(context.getExternalFilesDir(null), "results").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$prefix-$stamp.$ext")
        file.writeText(content)
        i("結果を保存: results/${file.name}")
        return file
    }
}
