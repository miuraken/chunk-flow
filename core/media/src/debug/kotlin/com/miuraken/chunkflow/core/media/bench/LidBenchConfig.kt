package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import java.io.File
import java.util.Locale

/**
 * 言語判定の実機計測ツール（debug ビルドだけ）で計測するエンジン。
 *
 * @property label 起動の引数とログに使う名前。
 */
internal enum class LidBenchEngine(val label: String) {
    /** 純 Kotlin の推論（main と同じ）。 */
    KOTLIN("kotlin"),

    /** ONNX Runtime（CPU）の推論。 */
    ONNX("onnx"),
    ;

    companion object {
        /** [label] のエンジンを返す。知らない名前なら `null`。 */
        fun fromLabel(label: String): LidBenchEngine? = entries.firstOrNull { it.label == label.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * 一括言語判定（`detectChunkLanguages`）の進め方。
 *
 * @property label 起動の引数とログに使う名前。
 * @property parallelism `detectChunkLanguages` に渡す並列数。
 */
internal enum class LidBenchMode(val label: String, val parallelism: Int) {
    /** 1 件ずつ推論する。 */
    SEQUENTIAL("seq", 1),

    /** 最大 3 件を同時に推論する（本番の上限と同じ）。 */
    PARALLEL3("par3", 3),
    ;

    companion object {
        /** [label] の進め方を返す。知らない名前なら `null`。 */
        fun fromLabel(label: String): LidBenchMode? = entries.firstOrNull { it.label == label.trim().lowercase(Locale.ROOT) }
    }
}

/** 引数や入力ファイルを読んだ結果。読めなければ理由の文を持つ。 */
internal sealed interface LidBenchParsed<out T> {
    /** 読めた。 */
    data class Ok<T>(val value: T) : LidBenchParsed<T>

    /** 読めなかった。[reason] は画面とログに出す。 */
    data class Invalid(val reason: String) : LidBenchParsed<Nothing>
}

/**
 * 計測の設定（起動の引数から作る）。
 *
 * @property name 教材名。入力は `<name>.chunks.json` と [audioFileName]。
 * @property targetTag 学習対象言語（例: `hi`）。
 * @property baseTag 解説の言語（例: `en`）。
 * @property audioFileName 音声のファイル名（既定は `<name>.mp3`）。
 * @property maxChunks 先頭から使うチャンク数（0 ならすべて）。
 * @property warmupChunks ウォームアップで判定するチャンク数（エンジンごと。0 ならウォームアップしない）。
 * @property cooldownSec 各回の前に待つ秒数。
 * @property thermalWaitSec クールダウンのあと、端末の温度の状態が MODERATE 未満に下がるまで追加で待つ上限の秒数。
 * @property engines 計測するエンジン（この順に計測する）。
 * @property modes 各エンジンで計測する進め方（この順に計測する）。
 */
internal data class LidBenchConfig(
    val name: String,
    val targetTag: String,
    val baseTag: String,
    val audioFileName: String,
    val maxChunks: Int = DEFAULT_MAX_CHUNKS,
    val warmupChunks: Int = DEFAULT_WARMUP_CHUNKS,
    val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    val thermalWaitSec: Int = DEFAULT_THERMAL_WAIT_SEC,
    val engines: List<LidBenchEngine> = DEFAULT_ENGINES,
    val modes: List<LidBenchMode> = DEFAULT_MODES,
) {
    /** チャンク境界の JSON のファイル名。 */
    val chunksFileName: String
        get() = "$name$CHUNKS_FILE_SUFFIX"

    companion object {
        /** チャンク境界の JSON のファイル名の後ろ（`<name>.chunks.json`）。 */
        const val CHUNKS_FILE_SUFFIX: String = ".chunks.json"

        /** 音声のファイル名の既定の後ろ（`<name>.mp3`）。 */
        const val DEFAULT_AUDIO_SUFFIX: String = ".mp3"

        const val DEFAULT_MAX_CHUNKS: Int = 60
        const val DEFAULT_WARMUP_CHUNKS: Int = 6
        const val DEFAULT_COOLDOWN_SEC: Int = 30
        const val DEFAULT_THERMAL_WAIT_SEC: Int = 300
        val DEFAULT_ENGINES: List<LidBenchEngine> = listOf(LidBenchEngine.KOTLIN, LidBenchEngine.ONNX)
        val DEFAULT_MODES: List<LidBenchMode> = listOf(LidBenchMode.SEQUENTIAL, LidBenchMode.PARALLEL3)

        /** 起動の引数の一覧（画面とログに出す使い方）。 */
        const val USAGE: String = "USAGE --es name <教材名> --es target <言語> --es base <言語> [--ei maxChunks 60] " +
            "[--ei warmupChunks 6] [--ei cooldownSec 30] [--ei thermalWaitSec 300] [--es engines kotlin,onnx] " +
            "[--es modes seq,par3] [--es audio <音声のファイル名>]"

        private const val MAX_CHUNKS_LIMIT: Int = LidBenchChunkList.MAX_CHUNKS
        private const val MAX_WARMUP_CHUNKS: Int = 50
        private const val MAX_WAIT_SEC: Int = 3_600

        /** ファイル名に使ってよい文字（ディレクトリの区切りを含めない）。 */
        private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

        /** 言語タグ（`hi`、`en`、`zh-TW` など）。 */
        private val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,3}")

        /**
         * 起動の引数 [args]（Intent の extras。値は文字列か数値）から設定を作る。
         *
         * 数値の引数は `--ei`（数値）と `--es`（文字列）のどちらで渡してもよい。
         */
        fun parse(args: Map<String, Any?>): LidBenchParsed<LidBenchConfig> {
            val name = args.text("name") ?: return invalid("name がない")
            if (!FILE_NAME_PATTERN.matches(name)) return invalid("name に使えない文字がある: $name")
            val target = args.text("target") ?: return invalid("target がない")
            val base = args.text("base") ?: return invalid("base がない")
            if (!LANGUAGE_TAG_PATTERN.matches(target)) return invalid("target が言語タグでない: $target")
            if (!LANGUAGE_TAG_PATTERN.matches(base)) return invalid("base が言語タグでない: $base")
            if (target.equals(base, ignoreCase = true)) return invalid("target と base が同じ: $target")
            val audio = args.text("audio") ?: "$name$DEFAULT_AUDIO_SUFFIX"
            if (!FILE_NAME_PATTERN.matches(audio)) return invalid("audio に使えない文字がある: $audio")

            val maxChunks = when (val v = args.int("maxChunks", DEFAULT_MAX_CHUNKS, 0..MAX_CHUNKS_LIMIT)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            val warmupChunks = when (val v = args.int("warmupChunks", DEFAULT_WARMUP_CHUNKS, 0..MAX_WARMUP_CHUNKS)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            val cooldownSec = when (val v = args.int("cooldownSec", DEFAULT_COOLDOWN_SEC, 0..MAX_WAIT_SEC)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            val thermalWaitSec = when (val v = args.int("thermalWaitSec", DEFAULT_THERMAL_WAIT_SEC, 0..MAX_WAIT_SEC)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            val engines = when (val v = args.list("engines", DEFAULT_ENGINES, LidBenchEngine::fromLabel)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            val modes = when (val v = args.list("modes", DEFAULT_MODES, LidBenchMode::fromLabel)) {
                is LidBenchParsed.Ok -> v.value
                is LidBenchParsed.Invalid -> return v
            }
            return LidBenchParsed.Ok(
                LidBenchConfig(
                    name = name,
                    targetTag = target,
                    baseTag = base,
                    audioFileName = audio,
                    maxChunks = maxChunks,
                    warmupChunks = warmupChunks,
                    cooldownSec = cooldownSec,
                    thermalWaitSec = thermalWaitSec,
                    engines = engines,
                    modes = modes,
                ),
            )
        }

        private fun invalid(reason: String): LidBenchParsed.Invalid = LidBenchParsed.Invalid(reason)

        private fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, Any?>.int(key: String, default: Int, range: IntRange): LidBenchParsed<Int> {
            val value = when (val raw = this[key]) {
                null -> default
                is Int -> raw
                is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else null
                is String -> raw.trim().toIntOrNull()
                else -> null
            } ?: return invalid("$key が整数でない: ${this[key]}")
            if (value !in range) return invalid("$key が範囲外: $value（${range.first}〜${range.last}）")
            return LidBenchParsed.Ok(value)
        }

        private fun <T> Map<String, Any?>.list(key: String, default: List<T>, parse: (String) -> T?): LidBenchParsed<List<T>> {
            val raw = text(key) ?: return LidBenchParsed.Ok(default)
            val values = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val parsed = values.map { label -> parse(label) ?: return invalid("$key に知らない値がある: $label") }
            if (parsed.isEmpty()) return invalid("$key が空")
            if (parsed.distinct().size != parsed.size) return invalid("$key に同じ値が 2 回ある: $raw")
            return LidBenchParsed.Ok(parsed)
        }
    }
}

/**
 * チャンク境界の JSON（`[[startMs,endMs],...]`。lead の評価データの `chunks_<name>.json` と同じ形）を読む。
 *
 * 信頼できない入力として扱い、形・大きさ・区間を確かめる。
 */
internal object LidBenchChunkList {
    /** 読み込む JSON の大きさの上限。 */
    const val MAX_FILE_BYTES: Long = 4L * 1024L * 1024L

    /** チャンク数の上限。 */
    const val MAX_CHUNKS: Int = 100_000

    /** 時刻の上限（24 時間）。 */
    private const val MAX_TIME_MS: Long = 24L * 60L * 60L * 1_000L

    /** 数字の桁数の上限（[MAX_TIME_MS] は 8 桁）。 */
    private const val MAX_DIGITS: Int = 12

    /** [text] を読み、チャンクの一覧を返す。 */
    fun parse(text: String): LidBenchParsed<List<ChunkTimeRange>> {
        val reader = Reader(text)
        if (!reader.consume('[')) return LidBenchParsed.Invalid("先頭が [ でない")
        val chunks = ArrayList<ChunkTimeRange>()
        if (!reader.peek(']')) {
            while (true) {
                val position = chunks.size + 1
                if (!reader.consume('[')) return LidBenchParsed.Invalid("$position 件目が [startMs,endMs] の形でない")
                val start = reader.number() ?: return LidBenchParsed.Invalid("$position 件目の startMs が整数でない")
                if (!reader.consume(',')) return LidBenchParsed.Invalid("$position 件目に , がない")
                val end = reader.number() ?: return LidBenchParsed.Invalid("$position 件目の endMs が整数でない")
                if (!reader.consume(']')) return LidBenchParsed.Invalid("$position 件目が ] で終わっていない")
                if (end <= start) return LidBenchParsed.Invalid("$position 件目の区間が正しくない: [$start,$end]")
                if (end > MAX_TIME_MS) return LidBenchParsed.Invalid("$position 件目の endMs が大きすぎる: $end")
                chunks += ChunkTimeRange(startMs = start, endMs = end)
                if (chunks.size > MAX_CHUNKS) return LidBenchParsed.Invalid("チャンクが多すぎる（上限 $MAX_CHUNKS）")
                if (!reader.consume(',')) break
            }
        }
        if (!reader.consume(']')) return LidBenchParsed.Invalid("配列が ] で終わっていない")
        if (!reader.atEnd()) return LidBenchParsed.Invalid("配列のあとに余分な文字がある")
        if (chunks.isEmpty()) return LidBenchParsed.Invalid("チャンクが 1 つもない")
        return LidBenchParsed.Ok(chunks)
    }

    /** 空白を読み飛ばしながら 1 文字ずつ読む。 */
    private class Reader(private val text: String) {
        private var pos: Int = if (text.startsWith('\uFEFF')) 1 else 0

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun peek(c: Char): Boolean {
            skipWhitespace()
            return pos < text.length && text[pos] == c
        }

        fun consume(c: Char): Boolean {
            if (!peek(c)) return false
            pos++
            return true
        }

        fun number(): Long? {
            skipWhitespace()
            val start = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            val digits = pos - start
            if (digits == 0 || digits > MAX_DIGITS) return null
            return text.substring(start, pos).toLong()
        }

        fun atEnd(): Boolean {
            skipWhitespace()
            return pos == text.length
        }
    }
}

/**
 * 計測に使う音声とチャンク。
 *
 * @property audioFile 音声のファイル。
 * @property chunks 計測するチャンク（先頭から [LidBenchConfig.maxChunks] 件）。
 * @property totalChunks JSON にあったチャンクの数。
 */
internal class LidBenchInput(
    val audioFile: File,
    val chunks: List<ChunkTimeRange>,
    val totalChunks: Int,
)

/** 入力（音声とチャンク境界の JSON）を探して読む。 */
internal object LidBenchInputLoader {
    /** [dirs] を順に探し、`<name>.chunks.json` があるディレクトリから入力を読む。 */
    fun load(dirs: List<File>, config: LidBenchConfig): LidBenchParsed<LidBenchInput> {
        val dir = dirs.firstOrNull { File(it, config.chunksFileName).isFile }
            ?: return LidBenchParsed.Invalid(
                "${config.chunksFileName} が見つからない（探した場所: ${dirs.joinToString(", ") { it.absolutePath }}）",
            )
        val chunksFile = File(dir, config.chunksFileName)
        if (chunksFile.length() > LidBenchChunkList.MAX_FILE_BYTES) {
            return LidBenchParsed.Invalid("${config.chunksFileName} が大きすぎる: ${chunksFile.length()} バイト")
        }
        val audioFile = File(dir, config.audioFileName)
        if (!audioFile.isFile || audioFile.length() <= 0L) {
            return LidBenchParsed.Invalid("${config.audioFileName} が見つからない（${dir.absolutePath}）")
        }
        val chunks = when (val parsed = LidBenchChunkList.parse(chunksFile.readText(Charsets.UTF_8))) {
            is LidBenchParsed.Ok -> parsed.value
            is LidBenchParsed.Invalid -> return LidBenchParsed.Invalid("${config.chunksFileName}: ${parsed.reason}")
        }
        val limited = if (config.maxChunks > 0) chunks.take(config.maxChunks) else chunks
        return LidBenchParsed.Ok(LidBenchInput(audioFile = audioFile, chunks = limited, totalChunks = chunks.size))
    }
}
