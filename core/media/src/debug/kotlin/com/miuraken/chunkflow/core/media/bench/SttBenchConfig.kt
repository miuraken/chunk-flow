package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SttEngine
import java.util.Locale

/**
 * 音声認識の実機計測ツール（debug ビルドだけ）で計測するエンジン。
 *
 * @property label 起動の引数・結果のファイルに使う名前。
 * @property trialEngine `transcribeChunkTrial` に渡すエンジンの指定（このエンジンだけを試し、ほかへ切り替えない）。
 * @property sttEngine モデルの状態の問い合わせ・ダウンロードに使う [SttEngine]。
 */
internal enum class SttBenchEngine(
    val label: String,
    val trialEngine: OnDeviceTranscriptionEngine,
    val sttEngine: SttEngine,
) {
    /** ML Kit GenAI Speech Recognition（端末に合わせて高度 / 基本モード）。 */
    ML_KIT("mlkit", OnDeviceTranscriptionEngine.ML_KIT, SttEngine.ML_KIT),

    /** Android 標準の `SpeechRecognizer`（端末内）。 */
    SPEECH_RECOGNIZER("sr", OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER, SttEngine.SPEECH_RECOGNIZER),

    /** `sherpa-onnx` Whisper Tiny。 */
    WHISPER_TINY("wtiny", OnDeviceTranscriptionEngine.SHERPA_ONNX, SttEngine.SHERPA_ONNX),

    /** `sherpa-onnx` Whisper Base。 */
    WHISPER_BASE("wbase", OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE, SttEngine.SHERPA_ONNX_WHISPER_BASE),

    /** `sherpa-onnx` Whisper Small。 */
    WHISPER_SMALL("wsmall", OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL, SttEngine.SHERPA_ONNX_WHISPER_SMALL),

    /** `sherpa-onnx` SenseVoice Small。 */
    SENSE_VOICE("sensevoice", OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE, SttEngine.SHERPA_ONNX_SENSE_VOICE),

    /** Moonshine ONNX Tiny（言語別モデル）。 */
    MOONSHINE_TINY("mtiny", OnDeviceTranscriptionEngine.MOONSHINE_TINY, SttEngine.MOONSHINE_TINY),

    /** Moonshine ONNX Base（言語別モデル）。 */
    MOONSHINE_BASE("mbase", OnDeviceTranscriptionEngine.MOONSHINE_BASE, SttEngine.MOONSHINE_BASE),
    ;

    companion object {
        /** [label] のエンジンを返す。知らない名前なら `null`。 */
        fun fromLabel(label: String): SttBenchEngine? = entries.firstOrNull { it.label == label.trim().lowercase(Locale.ROOT) }

        /** 既定で計測するエンジン（端末内のエンジンすべて）。 */
        val DEFAULT: List<SttBenchEngine> = entries.toList()
    }
}

/**
 * 計測ツールの動作。
 *
 * @property label 起動の引数に使う名前。
 */
internal enum class SttBenchMode(val label: String) {
    /** 言語ごと・エンジンごとのモデルの状態を出す。 */
    STATUS("status"),

    /** 足りないモデルのダウンロードを要求し、使えるようになるまで待つ。 */
    DOWNLOAD("download"),

    /** クリップごとに、2 言語のどちらかを言語判定（Whisper Tiny）だけで判定する（正解の言語の付与に使う）。 */
    LID("lid"),

    /** クリップごと・エンジンごとに文字起こしして、時間と本文を記録する。 */
    RUN("run"),
    ;

    companion object {
        /** [label] の動作を返す。知らない名前なら `null`。 */
        fun fromLabel(label: String): SttBenchMode? = entries.firstOrNull { it.label == label.trim().lowercase(Locale.ROOT) }
    }
}

/** 引数や入力ファイルを読んだ結果。読めなければ理由の文を持つ。 */
internal sealed interface SttBenchParsed<out T> {
    /** 読めた。 */
    data class Ok<T>(val value: T) : SttBenchParsed<T>

    /** 読めなかった。[reason] は画面とログに出す。 */
    data class Invalid(val reason: String) : SttBenchParsed<Nothing>
}

/**
 * 計測の設定（起動の引数から作る）。
 *
 * @property mode 動作。
 * @property manifestFileName クリップ一覧（TSV）のファイル名。[SttBenchMode.LID] と [SttBenchMode.RUN] で使う。
 * @property languages 対象の言語。[SttBenchMode.STATUS] と [SttBenchMode.DOWNLOAD] では必須、
 *   [SttBenchMode.RUN] では絞り込み（空ならクリップ一覧のすべての言語）。
 * @property engines 計測するエンジン（この順に計測する）。
 * @property maxClipsPerLanguage 言語ごとに先頭から使うクリップ数（0 ならすべて）。
 * @property warmup エンジン × 言語ごとに、先頭のクリップで 1 回ウォームアップする（モデルの読み込みを計測から外す）か。
 * @property cooldownSec エンジン × 言語の各回の前に待つ秒数。
 * @property thermalWaitSec クールダウンのあと、端末の温度の状態が MODERATE 未満に下がるまで追加で待つ上限の秒数。
 * @property downloadWaitSec [SttBenchMode.DOWNLOAD] で、OS が管理するモデルが使えるようになるまで待つ上限の秒数。
 * @property tag 結果のファイル名に付ける名前。
 */
internal data class SttBenchConfig(
    val mode: SttBenchMode,
    val manifestFileName: String? = null,
    val languages: List<String> = emptyList(),
    val engines: List<SttBenchEngine> = SttBenchEngine.DEFAULT,
    val maxClipsPerLanguage: Int = 0,
    val warmup: Boolean = true,
    val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    val thermalWaitSec: Int = DEFAULT_THERMAL_WAIT_SEC,
    val downloadWaitSec: Int = DEFAULT_DOWNLOAD_WAIT_SEC,
    val tag: String = DEFAULT_TAG,
) {
    companion object {
        const val DEFAULT_COOLDOWN_SEC: Int = 5
        const val DEFAULT_THERMAL_WAIT_SEC: Int = 180
        const val DEFAULT_DOWNLOAD_WAIT_SEC: Int = 600
        const val DEFAULT_TAG: String = "bench"

        /** 起動の引数の一覧（画面とログに出す使い方）。 */
        const val USAGE: String = "USAGE --es mode status|download|lid|run [--es manifest <clips.tsv>] [--es langs ja,en] " +
            "[--es engines mlkit,sr,wtiny,wbase,wsmall,sensevoice,mtiny,mbase] [--ei maxClips 0] [--ez warmup true] " +
            "[--ei cooldownSec 5] [--ei thermalWaitSec 180] [--ei downloadWaitSec 600] [--es tag bench]"

        private const val MAX_CLIPS_LIMIT: Int = SttBenchManifest.MAX_CLIPS
        private const val MAX_WAIT_SEC: Int = 3_600
        private const val MAX_LANGUAGES: Int = 32

        /** ファイル名に使ってよい文字（ディレクトリの区切りを含めない）。 */
        private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

        /** 結果のファイル名に付ける名前に使ってよい文字。 */
        private val TAG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")

        /**
         * 起動の引数 [args]（Intent の extras。値は文字列・数値・真偽値）から設定を作る。
         *
         * 数値の引数は `--ei`（数値）と `--es`（文字列）のどちらで渡してもよい。
         */
        fun parse(args: Map<String, Any?>): SttBenchParsed<SttBenchConfig> {
            val modeText = args.text("mode") ?: return invalid("mode がない")
            val mode = SttBenchMode.fromLabel(modeText) ?: return invalid("mode に知らない値がある: $modeText")
            val manifest = args.text("manifest")
            if (manifest != null && !FILE_NAME_PATTERN.matches(manifest)) return invalid("manifest に使えない文字がある: $manifest")
            if ((mode == SttBenchMode.LID || mode == SttBenchMode.RUN) && manifest == null) {
                return invalid("mode=${mode.label} には manifest が要る")
            }
            val languages = args.text("langs")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            for (language in languages) {
                if (!SttBenchManifest.LANGUAGE_TAG_PATTERN.matches(language)) return invalid("langs に言語タグでない値がある: $language")
            }
            if (languages.size > MAX_LANGUAGES) return invalid("langs が多すぎる（上限 $MAX_LANGUAGES）")
            if (languages.distinct().size != languages.size) return invalid("langs に同じ値が 2 回ある")
            if ((mode == SttBenchMode.STATUS || mode == SttBenchMode.DOWNLOAD) && languages.isEmpty()) {
                return invalid("mode=${mode.label} には langs が要る")
            }
            val engines = when (val v = args.list("engines", SttBenchEngine.DEFAULT, SttBenchEngine::fromLabel)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val maxClips = when (val v = args.int("maxClips", 0, 0..MAX_CLIPS_LIMIT)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val warmup = when (val v = args.bool("warmup", true)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val cooldownSec = when (val v = args.int("cooldownSec", DEFAULT_COOLDOWN_SEC, 0..MAX_WAIT_SEC)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val thermalWaitSec = when (val v = args.int("thermalWaitSec", DEFAULT_THERMAL_WAIT_SEC, 0..MAX_WAIT_SEC)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val downloadWaitSec = when (val v = args.int("downloadWaitSec", DEFAULT_DOWNLOAD_WAIT_SEC, 0..MAX_WAIT_SEC)) {
                is SttBenchParsed.Ok -> v.value
                is SttBenchParsed.Invalid -> return v
            }
            val tag = args.text("tag") ?: DEFAULT_TAG
            if (!TAG_PATTERN.matches(tag)) return invalid("tag に使えない文字がある: $tag")
            return SttBenchParsed.Ok(
                SttBenchConfig(
                    mode = mode,
                    manifestFileName = manifest,
                    languages = languages,
                    engines = engines,
                    maxClipsPerLanguage = maxClips,
                    warmup = warmup,
                    cooldownSec = cooldownSec,
                    thermalWaitSec = thermalWaitSec,
                    downloadWaitSec = downloadWaitSec,
                    tag = tag,
                ),
            )
        }

        private fun invalid(reason: String): SttBenchParsed.Invalid = SttBenchParsed.Invalid(reason)

        private fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun Map<String, Any?>.int(key: String, default: Int, range: IntRange): SttBenchParsed<Int> {
            val value = when (val raw = this[key]) {
                null -> default
                is Int -> raw
                is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else null
                is String -> raw.trim().toIntOrNull()
                else -> null
            } ?: return invalid("$key が整数でない: ${this[key]}")
            if (value !in range) return invalid("$key が範囲外: $value（${range.first}〜${range.last}）")
            return SttBenchParsed.Ok(value)
        }

        private fun Map<String, Any?>.bool(key: String, default: Boolean): SttBenchParsed<Boolean> {
            val value = when (val raw = this[key]) {
                null -> default

                is Boolean -> raw

                is String -> when (raw.trim().lowercase(Locale.ROOT)) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }

                is Int -> raw != 0

                else -> null
            } ?: return invalid("$key が真偽値でない: ${this[key]}")
            return SttBenchParsed.Ok(value)
        }

        private fun <T> Map<String, Any?>.list(key: String, default: List<T>, parse: (String) -> T?): SttBenchParsed<List<T>> {
            val raw = text(key) ?: return SttBenchParsed.Ok(default)
            val values = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val parsed = values.map { label -> parse(label) ?: return invalid("$key に知らない値がある: $label") }
            if (parsed.isEmpty()) return invalid("$key が空")
            if (parsed.distinct().size != parsed.size) return invalid("$key に同じ値が 2 回ある: $raw")
            return SttBenchParsed.Ok(parsed)
        }
    }
}

/**
 * 計測するクリップ 1 件（クリップ一覧の 1 行）。
 *
 * @property id クリップの識別子（結果の突き合わせに使う）。
 * @property language 文字起こしに使う言語タグ（[SttBenchMode.RUN]）。
 * @property relativePath 入力のディレクトリからの相対パス（16kHz モノラル 16-bit の WAV を想定）。
 * @property pair 言語判定で比べる 2 言語（学習言語・解説の言語）。[SttBenchMode.LID] で使う。
 * @property context 音声認識に渡す前後の文脈・語彙ヒント（任意）。
 */
internal data class SttBenchClip(
    val id: String,
    val language: String,
    val relativePath: String,
    val pair: Pair<String, String>? = null,
    val context: String? = null,
)

/**
 * クリップ一覧（TSV）を読む。
 *
 * 1 行目は列名の行（`id`、`lang`、`file` は必須、`pair`（`ko,ja` の形）と `context` は任意。ほかの列は読み飛ばす）。
 * `#` で始まる行と空行は読み飛ばす。信頼できない入力として扱い、形・大きさ・パスを確かめる。
 */
internal object SttBenchManifest {
    /** 読み込む TSV の大きさの上限。 */
    const val MAX_FILE_BYTES: Long = 2L * 1024L * 1024L

    /** クリップ数の上限。 */
    const val MAX_CLIPS: Int = 10_000

    /** 言語タグ（`hi`、`en`、`zh-TW`、`yue` など）。 */
    val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,3}")

    /** クリップの識別子に使ってよい文字。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

    /** 相対パスの 1 区切りに使ってよい文字（`..` などの親ディレクトリへの参照を含めない）。 */
    private val PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

    /** 相対パスの区切りの数の上限。 */
    private const val MAX_PATH_SEGMENTS: Int = 3

    /** [text] を読み、クリップの一覧を返す。 */
    fun parse(text: String): SttBenchParsed<List<SttBenchClip>> {
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .map { it.trimEnd('\r') }
            .withIndex()
            .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return SttBenchParsed.Invalid("列名の行がない")
        val header = lines.first().value.split('\t').map { it.trim().lowercase(Locale.ROOT) }
        val idColumn = header.indexOf("id")
        val langColumn = header.indexOf("lang")
        val fileColumn = header.indexOf("file")
        val pairColumn = header.indexOf("pair")
        val contextColumn = header.indexOf("context")
        if (idColumn < 0 || langColumn < 0 || fileColumn < 0) {
            return SttBenchParsed.Invalid("列名の行に id・lang・file のどれかがない")
        }
        val clips = ArrayList<SttBenchClip>()
        val seenIds = HashSet<String>()
        for ((index, line) in lines.drop(1)) {
            val lineNumber = index + 1
            val cells = line.split('\t').map { it.trim() }
            val id = cells.getOrNull(idColumn).orEmpty()
            val language = cells.getOrNull(langColumn).orEmpty()
            val file = cells.getOrNull(fileColumn).orEmpty()
            if (!ID_PATTERN.matches(id)) return SttBenchParsed.Invalid("$lineNumber 行目の id が正しくない: $id")
            if (!seenIds.add(id)) return SttBenchParsed.Invalid("$lineNumber 行目の id が重複している: $id")
            if (!LANGUAGE_TAG_PATTERN.matches(language)) return SttBenchParsed.Invalid("$lineNumber 行目の lang が言語タグでない: $language")
            if (!isSafeRelativePath(file)) return SttBenchParsed.Invalid("$lineNumber 行目の file が正しくない: $file")
            val pair = if (pairColumn >= 0) {
                when (val parsed = parsePair(cells.getOrNull(pairColumn).orEmpty())) {
                    is SttBenchParsed.Ok -> parsed.value
                    is SttBenchParsed.Invalid -> return SttBenchParsed.Invalid("$lineNumber 行目の pair: ${parsed.reason}")
                }
            } else {
                null
            }
            val context = if (contextColumn >= 0) {
                cells.getOrNull(contextColumn)?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            clips += SttBenchClip(id = id, language = language, relativePath = file, pair = pair, context = context)
            if (clips.size > MAX_CLIPS) return SttBenchParsed.Invalid("クリップが多すぎる（上限 $MAX_CLIPS）")
        }
        if (clips.isEmpty()) return SttBenchParsed.Invalid("クリップが 1 つもない")
        return SttBenchParsed.Ok(clips)
    }

    /** `ko,ja` の形の 2 言語を読む。空なら `null`。 */
    private fun parsePair(raw: String): SttBenchParsed<Pair<String, String>?> {
        if (raw.isEmpty()) return SttBenchParsed.Ok(null)
        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 2) return SttBenchParsed.Invalid("「学習言語,解説の言語」の形でない: $raw")
        val (target, base) = parts
        if (!LANGUAGE_TAG_PATTERN.matches(target) || !LANGUAGE_TAG_PATTERN.matches(base)) {
            return SttBenchParsed.Invalid("言語タグでない値がある: $raw")
        }
        if (target.equals(base, ignoreCase = true)) return SttBenchParsed.Invalid("2 言語が同じ: $raw")
        return SttBenchParsed.Ok(target to base)
    }

    /** 入力のディレクトリの外を指さない相対パスか。 */
    internal fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/")) return false
        val segments = path.split('/')
        if (segments.size > MAX_PATH_SEGMENTS) return false
        return segments.all { segment -> PATH_SEGMENT_PATTERN.matches(segment) && segment != "." && segment != ".." }
    }
}
