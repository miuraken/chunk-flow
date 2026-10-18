package com.miuraken.chunkflow.core.media

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 一括言語判定のための音声の切り出しの方式（[AndroidSpeechRecognizerChunkTranscriber.detectChunkLanguages]）。
 *
 * どの方式でも、結果の受け渡しとファイル内の補正はチャンクの順に行う。第 2 段階（文字起こし）の切り出しは変えない。
 *
 * @property label 計測ツールの引数とログに使う名前。
 */
internal enum class LidExtractionMethod(val label: String) {
    /** 今の方式: 呼び出し側のスレッドで、共有の抽出器を使ってチャンクを 1 件ずつ切り出す（MediaCodec はチャンクごとに作る）。 */
    CURRENT("cur"),

    /**
     * 並列の切り出し: 推論のワーカーが、それぞれ独立した抽出器で自分のチャンクを切り出してから推論する。
     * MediaCodec はチャンクごとに作るので、切り出す PCM は [CURRENT] と同じ。
     */
    PARALLEL("par"),

    /**
     * 順に流す切り出し: 対象のチャンクを含む範囲を 1 つの MediaCodec で頭から順にデコードし、各チャンクの窓を切り出して
     * 上限付きの待ち行列で推論のワーカーに渡す。窓ごとにリサンプラの位相を [CURRENT] と同じにそろえる。
     */
    STREAMING("str"),
    ;

    companion object {
        /** [label] の方式を返す。知らない名前なら `null`。 */
        fun fromLabel(label: String): LidExtractionMethod? = entries.firstOrNull { it.label == label.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * 言語判定のための音声の切り出しの各段階の時間（ナノ秒の累計）と回数（[snapshot] で読む）。
 *
 * `System.nanoTime` の差を足すだけなので、本番でも負担はほぼない（1 チャンクあたり数十回）。複数のスレッドから同時に足してよい。
 */
internal class LidExtractionTimings {
    /** 切り出しの回数（チャンクの数）。 */
    val extractions = AtomicLong()

    /** 切り出し全体の時間（各段階を含む）。 */
    val extractNanos = AtomicLong()

    /** 抽出器を開いた回数。 */
    val extractorOpens = AtomicLong()

    /** 抽出器を開く時間。 */
    val extractorOpenNanos = AtomicLong()

    /** 抽出器の位置合わせ（シークと、圧縮のままの読み飛ばし）の時間。 */
    val positionNanos = AtomicLong()

    /** 位置合わせでシークした回数。 */
    val seeks = AtomicLong()

    /** 位置合わせで圧縮のまま読み飛ばしたサンプル（フレーム）の数。 */
    val skippedSamples = AtomicLong()

    /** MediaCodec を作った（作成・configure・start）回数。 */
    val codecCreates = AtomicLong()

    /** MediaCodec を作る時間。 */
    val codecCreateNanos = AtomicLong()

    /** デコードのループの時間（入力・出力のやり取りに加えて、[mixNanos]・[resampleNanos] を含む）。 */
    val decodeLoopNanos = AtomicLong()

    /** 出力バッファの読み出しとモノラル化の時間。 */
    val mixNanos = AtomicLong()

    /** リサンプルの時間（リサンプラが呼ぶシンクの中で PCM に変えるので、[pcmNanos] を含む）。 */
    val resampleNanos = AtomicLong()

    /** 16-bit PCM への変換（リサンプラのシンクの中）の時間。 */
    val pcmNanos = AtomicLong()

    /** MediaCodec の stop・release の時間。 */
    val codecReleaseNanos = AtomicLong()

    /** 切り出した PCM の受け渡し（バイト列から ShortArray への変換とキャッシュ）の時間。 */
    val handoffNanos = AtomicLong()

    /** デコーダが出力したサンプル数（ネイティブのレート、1 チャンネルあたり）の合計。 */
    val decodedNativeSamples = AtomicLong()

    /** ファイル内の補正と結果の受け渡し（`onResult`）の時間。 */
    val adaptNanos = AtomicLong()

    /** 結果をチャンクの順に受け取る側が、推論（と切り出し）の終わりを待った時間。 */
    val awaitNanos = AtomicLong()

    /** 順に流す切り出しで、1 つの MediaCodec で続けてデコードした区間（run）の数。 */
    val streamRuns = AtomicLong()

    /** 順に流す切り出しで切り出した窓の数。 */
    val streamWindows = AtomicLong()

    /** 順に流す切り出しで、待ち行列がいっぱいで推論のワーカーを待った時間。 */
    val queueWaitNanos = AtomicLong()

    /** 速い方式で切り出せず、今の方式で切り出し直した回数。 */
    val fallbacks = AtomicLong()

    /** 今の値を読む（各値は別々に読むので、同時に足されている間はわずかにずれることがある）。 */
    fun snapshot(): LidExtractionTimingsSnapshot = LidExtractionTimingsSnapshot(
        extractions = extractions.get(),
        extractNanos = extractNanos.get(),
        extractorOpens = extractorOpens.get(),
        extractorOpenNanos = extractorOpenNanos.get(),
        positionNanos = positionNanos.get(),
        seeks = seeks.get(),
        skippedSamples = skippedSamples.get(),
        codecCreates = codecCreates.get(),
        codecCreateNanos = codecCreateNanos.get(),
        decodeLoopNanos = decodeLoopNanos.get(),
        mixNanos = mixNanos.get(),
        resampleNanos = resampleNanos.get(),
        pcmNanos = pcmNanos.get(),
        codecReleaseNanos = codecReleaseNanos.get(),
        handoffNanos = handoffNanos.get(),
        decodedNativeSamples = decodedNativeSamples.get(),
        adaptNanos = adaptNanos.get(),
        awaitNanos = awaitNanos.get(),
        streamRuns = streamRuns.get(),
        streamWindows = streamWindows.get(),
        queueWaitNanos = queueWaitNanos.get(),
        fallbacks = fallbacks.get(),
    )
}

/**
 * [LidExtractionTimings] のある時点の値（各項目は [LidExtractionTimings] の同じ名前の値）。
 *
 * 並列の方式では複数のスレッドの時間を足すので、各段階の合計が壁時計の時間より長くなることがある。
 */
internal data class LidExtractionTimingsSnapshot(
    val extractions: Long = 0L,
    val extractNanos: Long = 0L,
    val extractorOpens: Long = 0L,
    val extractorOpenNanos: Long = 0L,
    val positionNanos: Long = 0L,
    val seeks: Long = 0L,
    val skippedSamples: Long = 0L,
    val codecCreates: Long = 0L,
    val codecCreateNanos: Long = 0L,
    val decodeLoopNanos: Long = 0L,
    val mixNanos: Long = 0L,
    val resampleNanos: Long = 0L,
    val pcmNanos: Long = 0L,
    val codecReleaseNanos: Long = 0L,
    val handoffNanos: Long = 0L,
    val decodedNativeSamples: Long = 0L,
    val adaptNanos: Long = 0L,
    val awaitNanos: Long = 0L,
    val streamRuns: Long = 0L,
    val streamWindows: Long = 0L,
    val queueWaitNanos: Long = 0L,
    val fallbacks: Long = 0L,
) {
    /** デコードのループのうち、MediaCodec とのやり取り（入力の詰め込みと出力の待ち）の時間（モノラル化とリサンプルを除く）。 */
    val codecWaitNanos: Long
        get() = (decodeLoopNanos - mixNanos - resampleNanos).coerceAtLeast(0L)

    /** リサンプルだけの時間（PCM への変換を除く）。 */
    val resampleOnlyNanos: Long
        get() = (resampleNanos - pcmNanos).coerceAtLeast(0L)

    /** 切り出し全体のうち、どの段階にも入らない時間（ファイルの確認・ロックの待ち・トラックの選択など）。 */
    val otherNanos: Long
        get() = (
            extractNanos - extractorOpenNanos - positionNanos - codecCreateNanos - decodeLoopNanos -
                codecReleaseNanos - handoffNanos
            ).coerceAtLeast(0L)

    /** [before] からの増分を返す。 */
    fun since(before: LidExtractionTimingsSnapshot): LidExtractionTimingsSnapshot = LidExtractionTimingsSnapshot(
        extractions = extractions - before.extractions,
        extractNanos = extractNanos - before.extractNanos,
        extractorOpens = extractorOpens - before.extractorOpens,
        extractorOpenNanos = extractorOpenNanos - before.extractorOpenNanos,
        positionNanos = positionNanos - before.positionNanos,
        seeks = seeks - before.seeks,
        skippedSamples = skippedSamples - before.skippedSamples,
        codecCreates = codecCreates - before.codecCreates,
        codecCreateNanos = codecCreateNanos - before.codecCreateNanos,
        decodeLoopNanos = decodeLoopNanos - before.decodeLoopNanos,
        mixNanos = mixNanos - before.mixNanos,
        resampleNanos = resampleNanos - before.resampleNanos,
        pcmNanos = pcmNanos - before.pcmNanos,
        codecReleaseNanos = codecReleaseNanos - before.codecReleaseNanos,
        handoffNanos = handoffNanos - before.handoffNanos,
        decodedNativeSamples = decodedNativeSamples - before.decodedNativeSamples,
        adaptNanos = adaptNanos - before.adaptNanos,
        awaitNanos = awaitNanos - before.awaitNanos,
        streamRuns = streamRuns - before.streamRuns,
        streamWindows = streamWindows - before.streamWindows,
        queueWaitNanos = queueWaitNanos - before.queueWaitNanos,
        fallbacks = fallbacks - before.fallbacks,
    )
}

/** 時間を測るとき（この値が `null` でないとき）だけ時計を読む。測らないときは 0。 */
internal fun LidExtractionTimings?.startNanos(): Long = if (this != null) System.nanoTime() else 0L

/** 時間を測るとき（この値が `null` でないとき）だけ、[startNanos] からの経過時間を [select] の値に足す。 */
internal inline fun LidExtractionTimings?.addSince(startNanos: Long, select: LidExtractionTimings.() -> AtomicLong) {
    if (this != null) select().addAndGet(System.nanoTime() - startNanos)
}
