package com.miuraken.chunkflow.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.TranscriptionDiagnosticEntry
import com.miuraken.chunkflow.core.model.TranscriptionStep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端末内音声認識による 1 チャンク分の認識結果（F6.2）。
 *
 * 選ばれた言語の音声認識モデルが端末にない（未ダウンロードなど）場合は、本文を空にして
 * 言語判定（Whisper Tiny）の結果だけを返すことがある（[isLanguageOnly]）。
 *
 * @property text 認識されたテキスト（前後空白除去済み）。言語判定のみの結果では空文字。
 * @property language 検出または指定された言語タグ（不明な場合は `null`）。
 * @property confidence 認識信頼度（0.0..1.0、取得できない場合は `null`）。言語判定のみの結果では言語判定の確率。
 * @property engineId 使用した端末内認識エンジンの識別子。
 */
data class OnDeviceChunkTranscriptionResult(
    val text: String,
    val language: LanguageTag? = null,
    val confidence: Float? = null,
    val engineId: EngineId = DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    /** 本文がなく、言語の判定だけを行った結果かどうか（音声認識モデルが使えなかった場合）。 */
    val isLanguageOnly: Boolean
        get() = text.isBlank() && language != null

    companion object {
        val DEFAULT_ON_DEVICE_ENGINE_ID: EngineId = EngineId("android-on-device-speech")

        /** 音声認識モデルが使えず、Whisper Tiny の言語判定だけで言語を決めた結果のエンジン ID。 */
        val LANGUAGE_ID_ONLY_ENGINE_ID: EngineId = EngineId("whisper-tiny-lid")
    }
}

/**
 * 音声ファイル内の 1 チャンクの時間範囲（[OnDeviceChunkTranscriber.detectChunkLanguages] に渡す）。
 *
 * @property startMs チャンクの開始位置（ミリ秒）。
 * @property endMs チャンクの終了位置（ミリ秒、[startMs] より大きい）。
 */
data class ChunkTimeRange(
    val startMs: Long,
    val endMs: Long,
)

/**
 * 初回デコード中に順次確定するチャンクの 16kHz モノラル PCM スライス（[pcm16kMono]）の言語判定要求。
 *
 * @property chunkId 対象チャンクの ID。
 * @property startMs チャンクの開始位置（ミリ秒）。
 * @property endMs チャンクの終了位置（ミリ秒）。
 * @property pcm16kMono チャンク先頭（最大 2 秒分）の 16kHz モノラル PCM。
 */
data class PcmChunkLanguageRequest(
    val chunkId: Long,
    val startMs: Long,
    val endMs: Long,
    val pcm16kMono: ShortArray,
)

/**
 * チャンク単位（16kHz モノラル 16-bit PCM または音声ファイルの `[startMs, endMs]` 区間）で
 * 端末内音声認識を行うインターフェース（F6.2）。
 */
interface OnDeviceChunkTranscriber {
    /**
     * 現在実行中のチャンク認識の内部ステップ情報（F6.2）。
     *
     * @property step 現在の処理ステップ。
     * @property detail 使用中のエンジンや言語タグなどの補足ラベル。
     * @property startedAtEpochMs このチャンクの認識を開始した時刻（エポックミリ秒）。
     * @property highPriority 手動の強制文字起こし等の優先実行かどうか。
     */
    data class ActiveTranscriptionStepInfo(
        val step: TranscriptionStep,
        val detail: String? = null,
        val startedAtEpochMs: Long = 0L,
        val highPriority: Boolean = false,
    )

    /** 端末内音声認識が利用可能かどうかを返す。 */
    fun isAvailable(): Boolean

    /**
     * 端末内音声認識の言語モデルが使えない言語と、その理由（未ダウンロード・ダウンロード中・非対応など）。
     *
     * 認識を試みて問題がわかった言語だけを含み、問題のない言語は含まない。
     */
    val recognitionModelIssues: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>
        get() = NO_RECOGNITION_MODEL_ISSUES

    /** 現在実行中の認識ステップ情報。待機中は `null`。 */
    val activeStepState: StateFlow<ActiveTranscriptionStepInfo?>
        get() = NO_ACTIVE_STEP_STATE

    /** 直近の文字起こし診断・デバッグログ一覧。 */
    val recentDiagnostics: StateFlow<List<TranscriptionDiagnosticEntry>>
        get() = NO_RECENT_DIAGNOSTICS

    /**
     * 現在実行中の文字起こし処理を中断する。
     *
     * @param onlyLowPriority `true` の場合はバックグラウンドの先読み認識のみを中断し、
     *   優先度の高い手動の強制文字起こしへ即座にスロットを譲る。`false` の場合はすべての認識処理を中断する。
     */
    fun cancelActiveTranscription(onlyLowPriority: Boolean = false) = Unit

    /**
     * 認識サービスやロックが応答しなくなった場合などに、すべての実行中ジョブと待機ロックを強制リセットする。
     */
    fun resetTranscriberState() = Unit

    /**
     * リポジトリ層など外部からの制御イベントを診断ログに記録する。
     */
    fun recordExternalDiagnostic(
        stage: String,
        message: String,
        isWarningOrError: Boolean = false,
    ) = Unit

    /**
     * チャンクの切り出し用に開いたままにしている音声ファイルなどの資源を解放する（プレイヤー画面を閉じたときなど）。
     *
     * 実行中の文字起こしは止めない（切り出しの途中なら、その切り出しの終わりに解放する）。次の切り出しでは開き直す。
     */
    fun releaseIdleResources() = Unit

    /**
     * ダウンロード可能な全ローカル音声認識モデル（[com.miuraken.chunkflow.core.model.DownloadableSttModel]）の状態一覧を返す。
     */
    suspend fun queryDownloadableModelStates(): List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> = com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.map {
        com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = it)
    }

    /**
     * 指定された [model] をグローバルにダウンロード・検証する。
     */
    suspend fun downloadSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean = false

    /**
     * 指定された [model] のローカルファイル一式を削除（パージ）する。
     */
    suspend fun purgeSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean = false

    /**
     * [language] の端末内音声認識モデルのダウンロードを要求する。
     *
     * OS の `SpeechRecognizer` が対応している言語は OS にダウンロードを要求し、
     * OS 非対応言語（または `SHERPA_ONNX` 選択時）は `sherpa-onnx` ローカルモデルをダウンロードする。
     */
    suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean = false

    /**
     * [language] 向けに `sherpa-onnx` ローカル音声認識モデルをダウンロード・検証して有効化する（Stage 2）。
     */
    suspend fun downloadSherpaModel(language: LanguageTag): Boolean = false

    /**
     * [language] の `sherpa-onnx` ローカル音声認識モデルを削除する（Stage 2）。
     */
    suspend fun deleteSherpaModel(language: LanguageTag): Boolean = false

    /**
     * 指定された言語 [language] に対する [model] のローカルモデルを削除する。
     */
    suspend fun deleteSttModelForLanguage(
        language: LanguageTag,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ): Boolean = false

    /**
     * [language] で、エンジン [engine] のモデルの取得を要求する（ADR-0011 §2.4）。
     *
     * - [SttEngine.ML_KIT]・[SttEngine.SPEECH_RECOGNIZER]: モデルは OS が管理するので、OS に取得を要求するだけ（削除はできない）。
     * - [SttEngine.SHERPA_ONNX]: アプリがダウンロードして検証する（[downloadSherpaModel] と同じ）。
     *
     * 要求を送れた（または取得できた）場合は `true`。状態は [queryLanguageModelInfos] で確かめる。
     */
    suspend fun requestSttModelDownload(language: LanguageTag, engine: com.miuraken.chunkflow.core.model.SttEngine): Boolean = false

    /**
     * [recognitionModelIssues] に含まれる言語の言語モデルの状態を OS に問い合わせ直す。
     *
     * ダウンロードが終わっていれば、その言語を [recognitionModelIssues] から外す。
     */
    suspend fun refreshRecognitionModelIssues() = Unit

    /**
     * 指定された言語一覧 [languages] について、端末内音声認識モデルの利用可能状態と
     * 解決された BCP-47 ロケールタグ、および `sherpa-onnx` ローカルモデルの状態を問い合わせて返す（設定画面の「文字起こし設定」タブ用）。
     */
    suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> = languages.map { OnDeviceLanguageModelInfo(language = it) }

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * 認識不能・非対応端末・エミュレータ・無音の場合はクラッシュせず `null` を返す。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair? = null,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine> = emptyMap(),
    ): OnDeviceChunkTranscriptionResult?

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * @param forceRefresh `true` の場合は対象チャンクのキャッシュを破棄して強制的に再認識する。
     * @param highPriority `true` の場合はバックグラウンド先読みより優先して認識ロックを取得する。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine> = emptyMap(),
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
    )

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * @param precomputedLanguage 先行する言語判定パス（`language-id-only` 等）で既に判定済みの言語タグ（未判定時は `null`）。
     * @param precomputedLanguageConfidence [precomputedLanguage] の判定確率（0.0..1.0、不明時は `null`）。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
    )

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * @param precomputedLanguage 先行する言語判定パス（`language-id-only` 等）で既に判定済みの言語タグ（未判定時は `null`）。
     * @param precomputedLanguageConfidence [precomputedLanguage] の判定確率（0.0..1.0、不明時は `null`）。
     * @param compareBothLanguages `true` の場合は LID の確信度にかかわらず `target` と `base` の両言語で STT を実行し、テキスト自然度スコアを比較して採用する。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
        compareBothLanguages: Boolean,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
        precomputedLanguage = precomputedLanguage,
        precomputedLanguageConfidence = precomputedLanguageConfidence,
    )

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * @param precedingContextByLanguage 直前 1〜2 チャンクの同一言語の確定テキスト（ステージ A 文脈引き継ぎ用）。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
        compareBothLanguages: Boolean,
        precedingContextByLanguage: Map<LanguageTag, String>,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
        precomputedLanguage = precomputedLanguage,
        precomputedLanguageConfidence = precomputedLanguageConfidence,
        compareBothLanguages = compareBothLanguages,
    )

    /**
     * 切り出し済みの 16kHz モノラル 16-bit リトルエンディアン PCM（[pcm16LeBytes]）に対して端末内音声認識を行う。
     */
    suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult? = null

    /**
     * 「より詳しく文字起こし」ダイアログからの試行用として、認識エンジン・言語・音声速度倍率（[audioSpeed]）を指定して
     * 1 チャンクの端末内音声認識を実行する（キャッシュは使用せず更新もしない）。
     */
    suspend fun transcribeChunkTrial(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        engineOverride: OnDeviceTranscriptionEngine? = null,
        audioSpeed: Float = 1.0f,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = true,
        highPriority = true,
    )

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を切り出し、音声認識（文字起こし）は行わずに
     * Whisper Tiny による言語判定（[LanguagePair.target] と [LanguagePair.base] の比較）のみを実行する。
     */
    suspend fun detectChunkLanguage(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair,
    ): OnDeviceChunkTranscriptionResult? = null

    /**
     * 複数チャンクの言語判定（音声認識なし、[detectChunkLanguage] と同じ判定）をまとめて行い、
     * 結果を [chunks] の順に [onResult]（`index` は [chunks] の位置）へ渡す。
     *
     * 実装は、音声の解析（Whisper Tiny の推論）を [computeDispatcher] 上で最大 [parallelism] 件まで同時に進めてよい。
     * ファイル内の補正（[VoiceProfileLanguageTracker]）と [onResult] の呼び出しはチャンクの順に行うため、
     * 判定結果は 1 件ずつ処理した場合と同じになる。既定の実装は [detectChunkLanguage] を順に呼ぶ。
     * 1 チャンクの判定で例外が起きた場合は、そのチャンクの結果を `null` にして続ける。
     *
     * @param parallelism 同時に推論するチャンク数の上限（[recommendedLanguageDetectionParallelism] を参照）。
     * @param computeDispatcher 推論に使う Dispatcher（CPU 向け）。呼び出し側が DI で受け取ったものを渡す。
     */
    suspend fun detectChunkLanguages(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        languagePair: LanguagePair,
        parallelism: Int,
        computeDispatcher: CoroutineDispatcher,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        for ((index, chunk) in chunks.withIndex()) {
            val result = try {
                detectChunkLanguage(audioFile, chunk.startMs, chunk.endMs, languagePair)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            onResult(index, result)
        }
    }

    /**
     * デコード済みの 16kHz モノラル PCM（[pcm16kMono]）から、音声認識（文字起こし）は行わずに
     * Whisper Tiny による言語判定（[LanguagePair.target] と [LanguagePair.base] の比較）のみを実行する。
     */
    suspend fun detectChunkLanguageFromPcm(
        audioScopeKey: String,
        pcm16kMono: ShortArray,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair,
        audioFile: File? = null,
    ): OnDeviceChunkTranscriptionResult? = if (audioFile != null && audioFile.isFile) {
        detectChunkLanguage(audioFile, startMs, endMs, languagePair)
    } else {
        null
    }

    /**
     * ストリーミングデコード中に確定したチャンクの PCM スライス（[requests]）を順次受け取り、
     * [computeDispatcher] 上で最大 [parallelism] 並列で Whisper Tiny 推論を進めつつ、
     * チャンクの到着順（開始時刻順）にファイル内の話者プロファイル適応（[VoiceProfileLanguageTracker]）と
     * [onResult] の呼び出しを行う。
     */
    suspend fun detectChunkLanguagesFromPcmStream(
        audioScopeKey: String,
        requests: ReceiveChannel<PcmChunkLanguageRequest>,
        languagePair: LanguagePair,
        parallelism: Int,
        computeDispatcher: CoroutineDispatcher,
        audioFile: File? = null,
        onResult: suspend (request: PcmChunkLanguageRequest, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        for (req in requests) {
            val result = try {
                detectChunkLanguageFromPcm(
                    audioScopeKey = audioScopeKey,
                    pcm16kMono = req.pcm16kMono,
                    startMs = req.startMs,
                    endMs = req.endMs,
                    languagePair = languagePair,
                    audioFile = audioFile,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            onResult(req, result)
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

        /** 一括言語判定で同時に推論するチャンク数の上限。 */
        const val MAX_LANGUAGE_DETECTION_PARALLELISM: Int = 3

        /**
         * 端末の CPU コア数 [availableProcessors] から、一括言語判定で同時に推論するチャンク数を決める。
         *
         * 再生と UI のために半分以上のコアを残し、[MAX_LANGUAGE_DETECTION_PARALLELISM] を上限にする（8 コアなら 3、4 コアなら 2）。
         */
        fun recommendedLanguageDetectionParallelism(
            availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        ): Int = (availableProcessors / 2).coerceIn(1, MAX_LANGUAGE_DETECTION_PARALLELISM)

        private val NO_RECOGNITION_MODEL_ISSUES: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>> =
            MutableStateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>(emptyMap()).asStateFlow()

        private val NO_ACTIVE_STEP_STATE: StateFlow<ActiveTranscriptionStepInfo?> =
            MutableStateFlow<ActiveTranscriptionStepInfo?>(null).asStateFlow()

        private val NO_RECENT_DIAGNOSTICS: StateFlow<List<TranscriptionDiagnosticEntry>> =
            MutableStateFlow<List<TranscriptionDiagnosticEntry>>(emptyList()).asStateFlow()
    }
}

/**
 * 短い言語コード（`"ja"`, `"en"`, `"ko"` など）を Android `SpeechRecognizer`（SODA）が要求する
 * 地域コード付き BCP-47 ロケールタグ（`"ja-JP"`, `"en-US"`, `"ko-KR"` など）へ変換する。
 */
internal fun toBcp47LocaleTag(lang: String): String {
    val normalized = lang.trim().replace('_', '-')
    if (normalized.isEmpty()) return "en-US"
    val lower = normalized.lowercase(Locale.ROOT)
    when (lower) {
        "zh", "zh-cn", "zh-hans" -> return "zh-CN"
        "zh-tw", "zh-hant" -> return "zh-TW"
        "yue" -> return "yue-HK"
    }
    if ('-' in normalized) {
        val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
        return if (localeTag.isNotBlank() && localeTag != "und") localeTag else normalized
    }
    com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.findByCode(lower)?.let { entry ->
        return entry.defaultBcp47Tag
    }
    val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
    return if (localeTag.isNotBlank() && localeTag != "und") localeTag else normalized
}

/**
 * 言語コード [lang] に対して Android `SpeechRecognizer`（SODA）へ順に試行する BCP-47 ロケールタグ候補一覧を返す。
 *
 * Pixel 等のオンデバイス音声認識（SODA）では、中国語（普通話・簡体字）が `"zh-CN"` ではなく `"cmn-Hans-CN"`、
 * 台湾華語（繁体字）が `"cmn-Hant-TW"`、広東語が `"yue-Hant-HK"` として登録されている端末があるため、
 * 第一候補が `ERROR_LANGUAGE_NOT_SUPPORTED` となった場合にこれらの同義タグへフォールバックできるようにする。
 */
internal fun toBcp47CandidateLocaleTags(lang: String): List<String> {
    val primary = toBcp47LocaleTag(lang)
    val normalized = lang.trim().replace('_', '-').lowercase(Locale.ROOT)
    val catalogCandidates = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.toBcp47CandidateTags(normalized)
    val aliases = when (normalized) {
        "zh",
        "zh-cn",
        "zh-hans",
        "zh-hans-cn",
        "cmn",
        "cmn-hans",
        "cmn-hans-cn",
        "cmn-cn",
        -> listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh")

        "zh-sg",
        "zh-hans-sg",
        "cmn-hans-sg",
        -> listOf("zh-SG", "cmn-Hans-SG", "cmn-Hans-CN", "zh-CN", "zh-Hans-CN")

        "zh-tw",
        "zh-hant",
        "zh-hant-tw",
        "cmn-hant",
        "cmn-hant-tw",
        "cmn-tw",
        -> listOf("zh-TW", "cmn-Hant-TW", "zh-Hant-TW", "cmn-TW")

        "yue",
        "yue-hk",
        "yue-hant",
        "yue-hant-hk",
        "zh-hk",
        "zh-hant-hk",
        -> listOf("yue-HK", "yue-Hant-HK", "zh-HK", "zh-Hant-HK", "cmn-Hant-HK", "yue")

        else -> {
            val baseLang = normalized.substringBefore('-')
            val defaultForBase = if (baseLang.isNotEmpty() && baseLang != normalized) {
                toBcp47LocaleTag(baseLang)
            } else {
                null
            }
            catalogCandidates + listOfNotNull(primary, defaultForBase, baseLang.takeIf { it.isNotEmpty() })
        }
    }
    return (listOf(primary) + aliases + catalogCandidates).distinct()
}

/**
 * チャンクの先頭発話プローブ切り出し結果（F6.2）。
 *
 * @property probePcm16LeBytes 発話開始位置から最大約 2 秒間を切り出した 16-bit リトルエンディアン PCM。
 * @property onsetByteOffset 元のチャンク PCM 内での発話開始バイトオフセット（偶数）。
 * @property isFullChunkCovered プローブ区間がチャンクの残り全体をカバーしている場合 `true`。
 */
internal data class SpeechProbeSlice(
    val probePcm16LeBytes: ByteArray,
    val onsetByteOffset: Int,
    val isFullChunkCovered: Boolean,
)

/**
 * 音声ファイルから指定したチャンク区間 `[startMs, endMs]` の 16kHz モノラル 16-bit PCM を取り出すヘルパー（F6.2）。
 *
 * - `endSample` に到達した時点で即座にデコードを打ち切り、30 分等の長時間ファイル全体をデコードしない。
 * - [MediaCodecAudioStreamDecoder] 使用時は、`startMs` の 500ms 手前まで圧縮データのまま読み飛ばしてからデコードし、
 *   同じファイルの抽出器を使い回す（[MediaCodecAudioStreamDecoder.decodeFromPosition]）。抽出済みチャンクは LRU キャッシュする。
 */
object ChunkPcmExtractor {

    private class ChunkExtractionCompletedException : CancellationException("Chunk PCM extraction reached target endSample")

    private data class ChunkCacheKey(
        val absolutePath: String,
        val lastModified: Long,
        val fileLength: Long,
        val decoderIdentity: Int,
        val startMs: Long,
        val endMs: Long,
        val sampleRateHz: Int,
    )

    private val cacheLock = Any()
    private val recentChunkCache = object : LinkedHashMap<ChunkCacheKey, ByteArray>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkCacheKey, ByteArray>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    /**
     * 正規化 Float サンプル配列（`-1.0..1.0`）を 16-bit 符号付き整数（[ShortArray]）へ変換する。
     */
    fun floatToPcm16Shorts(samples: FloatArray, count: Int = samples.size): ShortArray {
        val safeCount = count.coerceIn(0, samples.size)
        val out = ShortArray(safeCount)
        for (i in 0 until safeCount) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val scaled = (clamped * Short.MAX_VALUE.toFloat()).roundToInt()
            out[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * 16-bit 符号付き整数配列（[ShortArray]）をリトルエンディアン [ByteArray] へ変換する。
     */
    fun shortsToPcm16LeBytes(shorts: ShortArray, count: Int = shorts.size): ByteArray {
        val safeCount = count.coerceIn(0, shorts.size)
        val buffer = ByteBuffer.allocate(safeCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until safeCount) {
            buffer.putShort(shorts[i])
        }
        return buffer.array()
    }

    /**
     * 16-bit リトルエンディアン PCM（[pcm16LeBytes]）を [ShortArray] へ変換する。
     */
    fun pcm16LeBytesToShorts(pcm16LeBytes: ByteArray): ShortArray {
        val shortCount = pcm16LeBytes.size / 2
        val shorts = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(pcm16LeBytes, 0, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = byteBuffer.short
        }
        return shorts
    }

    /**
     * 16-bit リトルエンディアン PCM（[pcm16LeBytes]）の先頭と末尾に無音（0 バイト列）を付加する。
     */
    internal fun padPcmWithSilence(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
        leadingSilenceMs: Int = LEADING_SILENCE_MS,
        trailingSilenceMs: Int = TRAILING_SILENCE_MS,
    ): ByteArray {
        if (pcm16LeBytes.isEmpty() || sampleRateHz <= 0) return pcm16LeBytes
        val leadingBytes = ((sampleRateHz * leadingSilenceMs) / 1_000).coerceAtLeast(0) * 2
        val trailingBytes = ((sampleRateHz * trailingSilenceMs) / 1_000).coerceAtLeast(0) * 2
        val padded = ByteArray(leadingBytes + pcm16LeBytes.size + trailingBytes)
        System.arraycopy(pcm16LeBytes, 0, padded, leadingBytes, pcm16LeBytes.size)
        return padded
    }

    /**
     * チャンク PCM（[pcm16LeBytes]）から発話開始位置を検出し、先頭約 [probeDurationMs]（既定 2,000ms）の
     * 言語判定プローブ用 PCM スライスを切り出す。
     */
    internal fun extractLeadingSpeechProbePcm(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
        probeDurationMs: Long = PROBE_DURATION_MS,
        fullCoverageThresholdMs: Long = PROBE_FULL_COVERAGE_THRESHOLD_MS,
    ): SpeechProbeSlice {
        val totalBytes = pcm16LeBytes.size and 1.inv()
        if (totalBytes <= 0 || sampleRateHz <= 0) {
            return SpeechProbeSlice(
                probePcm16LeBytes = pcm16LeBytes,
                onsetByteOffset = 0,
                isFullChunkCovered = true,
            )
        }

        val frameSamples = ((sampleRateHz * PROBE_RMS_FRAME_MS) / 1_000).coerceAtLeast(1)
        val frameBytes = frameSamples * 2
        val preRollBytes = (((sampleRateHz * PROBE_ONSET_PRE_ROLL_MS) / 1_000) * 2).coerceAtLeast(0)

        var onsetByteOffset = 0
        var offset = 0
        while (offset + 1 < totalBytes) {
            val frameEnd = min(offset + frameBytes, totalBytes)
            var sumSq = 0.0
            var count = 0
            var idx = offset
            while (idx + 1 < frameEnd) {
                val lo = pcm16LeBytes[idx].toInt() and 0xFF
                val hi = pcm16LeBytes[idx + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toDouble()
                sumSq += sample * sample
                count += 1
                idx += 2
            }
            val rms = if (count > 0) sqrt(sumSq / count) else 0.0
            if (rms >= SPEECH_ONSET_RMS_THRESHOLD) {
                onsetByteOffset = (offset - preRollBytes).coerceAtLeast(0) and 1.inv()
                break
            }
            offset += frameBytes
        }

        val remainingBytes = (totalBytes - onsetByteOffset).coerceAtLeast(0)
        val maxProbeBytes = (((sampleRateHz.toLong() * probeDurationMs) / 1_000L) * 2L)
            .coerceAtLeast(2L)
            .toInt() and 1.inv()
        val fullCoverageBytes = (((sampleRateHz.toLong() * fullCoverageThresholdMs) / 1_000L) * 2L)
            .coerceAtLeast(maxProbeBytes.toLong())
            .toInt() and 1.inv()

        val isFullChunkCovered = remainingBytes <= fullCoverageBytes
        val sliceBytes = min(remainingBytes, maxProbeBytes)
        val probeBytes = if (onsetByteOffset == 0 && sliceBytes == pcm16LeBytes.size) {
            pcm16LeBytes
        } else {
            pcm16LeBytes.copyOfRange(onsetByteOffset, onsetByteOffset + sliceBytes)
        }

        return SpeechProbeSlice(
            probePcm16LeBytes = probeBytes,
            onsetByteOffset = onsetByteOffset,
            isFullChunkCovered = isFullChunkCovered,
        )
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit PCM（[ShortArray]）を抽出する。
     */
    suspend fun extractChunkPcm16Shorts(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ShortArray? {
        val bytes = extractChunkPcm16LeBytes(
            decoder = decoder,
            audioFile = audioFile,
            startMs = startMs,
            endMs = endMs,
            sampleRateHz = sampleRateHz,
        ) ?: return null
        val shortCount = bytes.size / 2
        val shorts = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = byteBuffer.short
        }
        return shorts
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit リトルエンディアン PCM（[ByteArray]）を抽出する。
     *
     * `endMs` に達した時点で即座にデコードを終了し、ファイル全体のデコードを行わない。
     */
    suspend fun extractChunkPcm16LeBytes(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ByteArray? = extractChunkPcm16LeBytes(
        decoder = decoder,
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        sampleRateHz = sampleRateHz,
        lane = null,
        timings = null,
    )

    /**
     * [extractChunkPcm16LeBytes] の本体。
     *
     * @param lane 並列の切り出しのレーン（[MediaCodecAudioStreamDecoder.openRangeDecodeLane]）。`null` なら共有の抽出器を使う。
     *   レーンを使っても切り出す PCM は同じ（[MediaCodecAudioStreamDecoder.decodeFromPosition]）。
     * @param timings 各段階の時間を足す先（言語判定の切り出しだけで渡す）。
     */
    internal suspend fun extractChunkPcm16LeBytes(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int,
        lane: RangeDecodeLane?,
        timings: LidExtractionTimings?,
    ): ByteArray? {
        if (startMs < 0L || endMs <= startMs || sampleRateHz <= 0 || !audioFile.isFile) {
            return null
        }
        val startSample = (startMs * sampleRateHz) / 1_000L
        val endSample = (endMs * sampleRateHz) / 1_000L
        if (endSample <= startSample) return null

        val cacheKey = ChunkCacheKey(
            absolutePath = audioFile.absolutePath,
            lastModified = audioFile.lastModified(),
            fileLength = audioFile.length(),
            decoderIdentity = System.identityHashCode(decoder),
            startMs = startMs,
            endMs = endMs,
            sampleRateHz = sampleRateHz,
        )
        synchronized(cacheLock) {
            recentChunkCache[cacheKey]?.let { cached ->
                return cached.copyOf()
            }
        }

        // 出力の大きさは最初から決まるので、1 バイトずつ書く ByteArrayOutputStream（write は同期化されている）を使わず配列に直接書く
        val expectedBytes = ((endSample - startSample) * 2L).coerceAtMost(MAX_CHUNK_PCM_BYTES.toLong()).toInt()
        val out = ByteArray(expectedBytes)
        var writtenBytes = 0
        var currentSampleIndex = 0L
        val job = currentCoroutineContext()[Job]

        val sink = SampleSink { samples, count ->
            job?.ensureActive()
            val pcmStartNanos = timings.startNanos()
            val blockStart = currentSampleIndex
            val blockEnd = currentSampleIndex + count
            currentSampleIndex = blockEnd

            val sliceStart = max(startSample, blockStart)
            val sliceEnd = min(endSample, blockEnd)
            if (sliceStart < sliceEnd) {
                var idx = (sliceStart - blockStart).toInt()
                val localTo = (sliceEnd - blockStart).toInt()
                while (idx < localTo && writtenBytes + 2 <= out.size) {
                    val sampleInt = floatToPcm16(samples[idx])
                    out[writtenBytes] = (sampleInt and 0xFF).toByte()
                    out[writtenBytes + 1] = ((sampleInt ushr 8) and 0xFF).toByte()
                    writtenBytes += 2
                    idx++
                }
            }
            timings.addSince(pcmStartNanos) { pcmNanos }
            if (currentSampleIndex >= endSample || writtenBytes + 2 > out.size) {
                throw ChunkExtractionCompletedException()
            }
        }

        var stoppedEarly = false
        val result = try {
            if (decoder is MediaCodecAudioStreamDecoder) {
                decoder.decodeFromPosition(
                    file = audioFile,
                    startPositionMs = startMs,
                    sink = sink,
                    outputSampleRate = sampleRateHz,
                    onSeekLandingUs = { landedUs ->
                        // 最初にデコーダへ渡すサンプルの時刻を、出力（sampleRateHz）のサンプル位置に直す
                        currentSampleIndex = Math.floorDiv(landedUs * sampleRateHz, 1_000_000L)
                    },
                    lane = lane,
                    timings = timings,
                )
            } else {
                decoder.decode(
                    file = audioFile,
                    outputSampleRate = sampleRateHz,
                    sink = sink,
                )
            }
        } catch (_: ChunkExtractionCompletedException) {
            stoppedEarly = true
            null
        }

        val handoffStartNanos = timings.startNanos()
        val extracted = if ((stoppedEarly || result is AudioDecodeResult.Success) && writtenBytes > 0) {
            if (writtenBytes == out.size) out else out.copyOf(writtenBytes)
        } else {
            null
        }

        if (extracted != null && extracted.size <= MAX_CACHED_ENTRY_BYTES) {
            synchronized(cacheLock) {
                recentChunkCache[cacheKey] = extracted.copyOf()
            }
        }
        timings.addSince(handoffStartNanos) { handoffNanos }
        return extracted
    }

    /**
     * 正規化 Float サンプル（`-1.0..1.0` の外は丸める）を 16-bit 符号付き整数の値にする。
     *
     * チャンクの切り出し（[extractChunkPcm16LeBytes]）と、順に流す切り出し（[LidStreamingExtractor]）で同じ値にするために共有する。
     */
    internal fun floatToPcm16(sample: Float): Int {
        val clamped = sample.coerceIn(-1.0f, 1.0f)
        return (clamped * Short.MAX_VALUE.toFloat())
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    internal const val LEADING_SILENCE_MS: Int = 150
    internal const val TRAILING_SILENCE_MS: Int = 600
    internal const val PROBE_LEADING_SILENCE_MS: Int = 120
    internal const val PROBE_TRAILING_SILENCE_MS: Int = 400
    internal const val PROBE_DURATION_MS: Long = 2_000L
    internal const val PROBE_FULL_COVERAGE_THRESHOLD_MS: Long = 2_400L
    private const val PROBE_RMS_FRAME_MS: Int = 20
    private const val PROBE_ONSET_PRE_ROLL_MS: Int = 60
    private const val SPEECH_ONSET_RMS_THRESHOLD: Double = 160.0
    private const val MAX_CHUNK_PCM_BYTES: Int = 4 * 1024 * 1024
    private const val MAX_CACHED_ENTRY_BYTES: Int = 512 * 1024
    private const val MAX_CACHE_ENTRIES: Int = 8
}

/**
 * 1 回の `SpeechRecognizer` 呼び出し試行の結果（内部・テスト用）。
 *
 * @property errorCode 認識できなかったときの `SpeechRecognizer` のエラーコード（言語モデルの未ダウンロードなどの判定に使う）。
 * @property engineId 実際に認識を実行したエンジンの識別子。
 */
internal data class SpeechRecognitionAttemptResult(
    val text: String,
    val confidence: Float? = null,
    val errorCode: Int? = null,
    val engineId: EngineId = OnDeviceChunkTranscriptionResult.DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    /** 言語モデルが使えない（未ダウンロード・非対応）ために認識できなかったかどうか。 */
    val isLanguageModelUnavailable: Boolean
        get() = text.isBlank() &&
            (
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                )
}

/** 端末内音声認識の言語モデルのダウンロード要求の進み具合（内部・テスト用）。 */
internal enum class RecognitionModelDownloadEvent {
    /** ダウンロード中。 */
    PROGRESS,

    /** ダウンロードが予約された（Wi-Fi 接続時などに OS が行う。以後の通知はない）。 */
    SCHEDULED,

    /** ダウンロードが終わり、言語モデルを使えるようになった。 */
    SUCCESS,

    /** ダウンロードに失敗した。 */
    FAILED,
}

/** 端末内音声認識の言語モデルの対応状況（内部・テスト用）。 */
internal enum class RecognitionModelSupport {
    /** 端末にインストール済み。 */
    INSTALLED,

    /** ダウンロード中または予約済み。 */
    PENDING,

    /** 対応しているが未インストール。 */
    SUPPORTED_NOT_INSTALLED,

    /** 端末内音声認識が対応していない。 */
    UNSUPPORTED,
}

/**
 * 端末内音声認識の言語モデルの対応状況の確認とダウンロード要求（内部。テストで差し替える）。
 */
internal interface RecognitionModelGateway {
    /**
     * [bcp47LocaleTag] の言語モデルのダウンロードを要求し、進み具合を [onEvent] に通知する。
     *
     * 要求を送れた場合は `true`。
     */
    fun requestDownload(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean

    /**
     * [bcp47LocaleTags] の言語モデルの対応状況を調べる。調べられなかった言語は結果に含めない。
     */
    suspend fun checkSupport(bcp47LocaleTags: List<String>): Map<String, RecognitionModelSupport>
}

/**
 * [SpeechRecognizer.triggerModelDownload] と [SpeechRecognizer.checkRecognitionSupport] を使う
 * [RecognitionModelGateway] の本番実装。`SpeechRecognizer` はメインスレッドで作成・破棄する。
 */
internal class AndroidRecognitionModelGateway(
    private val context: Context,
) : RecognitionModelGateway {

    private data class CachedSupportSnapshot(
        val support: RecognitionSupport,
        val cachedAtNanos: Long,
    )

    @Volatile
    private var cachedSnapshot: CachedSupportSnapshot? = null

    override fun requestDownload(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean {
        cachedSnapshot = null
        val mainHandler = Handler(Looper.getMainLooper())
        return mainHandler.post {
            var recognizer: SpeechRecognizer? = null
            var released = false
            val release = Runnable {
                if (!released) {
                    released = true
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                }
            }
            try {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = created
                created.triggerModelDownload(
                    buildModelIntent(bcp47LocaleTag),
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            onEvent(RecognitionModelDownloadEvent.PROGRESS)
                        }

                        override fun onSuccess() {
                            cachedSnapshot = null
                            onEvent(RecognitionModelDownloadEvent.SUCCESS)
                            release.run()
                        }

                        override fun onScheduled() {
                            cachedSnapshot = null
                            onEvent(RecognitionModelDownloadEvent.SCHEDULED)
                            release.run()
                        }

                        override fun onError(error: Int) {
                            cachedSnapshot = null
                            logModelInfo("modelDownload onError lang=$bcp47LocaleTag error=$error")
                            onEvent(RecognitionModelDownloadEvent.FAILED)
                            release.run()
                        }
                    },
                )
                // 完了通知が来ないまま SpeechRecognizer を持ち続けないよう、一定時間で手放す
                mainHandler.postDelayed(release, MODEL_DOWNLOAD_LISTENER_TIMEOUT_MS)
            } catch (e: Exception) {
                logModelInfo("modelDownload failed lang=$bcp47LocaleTag error=${e.javaClass.simpleName}")
                release.run()
                onEvent(RecognitionModelDownloadEvent.FAILED)
            }
        }
    }

    override suspend fun checkSupport(bcp47LocaleTags: List<String>): Map<String, RecognitionModelSupport> {
        if (bcp47LocaleTags.isEmpty()) return emptyMap()
        val nowNanos = System.nanoTime()
        val cached = cachedSnapshot
        val support = if (
            cached != null &&
            nowNanos - cached.cachedAtNanos in 0L..SUPPORT_CACHE_TTL_NANOS &&
            cached.support.pendingOnDeviceLanguages.isEmpty()
        ) {
            cached.support
        } else {
            val queried = withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) { querySupportOnMainLooper() }
                ?: return emptyMap()
            if (queried.pendingOnDeviceLanguages.isEmpty()) {
                cachedSnapshot = CachedSupportSnapshot(queried, System.nanoTime())
            } else {
                cachedSnapshot = null
            }
            queried
        }
        val result = LinkedHashMap<String, RecognitionModelSupport>(bcp47LocaleTags.size)
        for (tag in bcp47LocaleTags) {
            result[tag] = when {
                support.installedOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.INSTALLED
                support.pendingOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.PENDING
                support.supportedOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
                else -> RecognitionModelSupport.UNSUPPORTED
            }
        }
        return result
    }

    private suspend fun querySupportOnMainLooper(): RecognitionSupport? = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        val release = Runnable {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        continuation.invokeOnCancellation { mainHandler.post(release) }
        mainHandler.post {
            if (!continuation.isActive) return@post
            try {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = created
                created.checkRecognitionSupport(
                    buildModelIntent(bcp47LocaleTag = null),
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            release.run()
                            if (continuation.isActive) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            logModelInfo("checkRecognitionSupport onError error=$error")
                            release.run()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                logModelInfo("checkRecognitionSupport failed error=${e.javaClass.simpleName}")
                release.run()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun buildModelIntent(bcp47LocaleTag: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (bcp47LocaleTag != null) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47LocaleTag)
        }
    }

    private fun List<String>.containsTag(tag: String): Boolean = any { it.equals(tag, ignoreCase = true) }

    private fun logModelInfo(message: String) {
        runCatching {
            if (Log.isLoggable(MODEL_LOG_TAG, Log.INFO)) {
                Log.i(MODEL_LOG_TAG, message)
            }
        }
    }

    private companion object {
        const val MODEL_LOG_TAG: String = "ChunkFlowSpeech"
        const val MODEL_DOWNLOAD_LISTENER_TIMEOUT_MS: Long = 30L * 60L * 1_000L
        const val SUPPORT_CACHE_TTL_NANOS: Long = 30_000_000_000L
        const val SUPPORT_CHECK_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * 2 秒プローブ認識結果 [attempt] が指定言語 [langTag] の自然な発話としてどの程度もっともらしいかを採点する。
 *
 * - `SpeechRecognizer` の [SpeechRecognitionAttemptResult.confidence] を最重視（重み [CONFIDENCE_SCORE_WEIGHT] = 3.0）し、
 *   低確信度（`< 0.60`）の無理な当てはめ認識には追加ペナルティを与える。
 * - 外国語音声が日本語認識器にカタカナ外来語化・音写された場合（純カタカナだけでなく、助詞や「です」等の
 *   ひらがなが数文字混入したカタカナ主体の結果も含む）を強く減点し、本来の学習言語の認識結果が高スコアになるよう判定する。
 */
internal fun scoreProbeCandidateForLanguage(
    attempt: SpeechRecognitionAttemptResult,
    langTag: String,
): Float {
    val text = attempt.text.trim()
    if (text.isEmpty()) return Float.NEGATIVE_INFINITY

    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    val validConfidence = attempt.confidence?.takeIf { it > 0f }
    val baseConfidence = validConfidence ?: DEFAULT_PROBE_CONFIDENCE
    var score = baseConfidence * CONFIDENCE_SCORE_WEIGHT
    if (validConfidence != null && validConfidence < LOW_CONFIDENCE_PENALTY_THRESHOLD) {
        score -= (LOW_CONFIDENCE_PENALTY_THRESHOLD - validConfidence) * LOW_CONFIDENCE_PENALTY_WEIGHT
    }

    var kanjiCount = 0
    var hiraganaCount = 0
    var katakanaCount = 0
    var hangulCount = 0
    var devanagariCount = 0
    var latinCount = 0
    var totalLetterCount = 0

    for (ch in text) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> {
                kanjiCount += 1
                totalLetterCount += 1
            }

            ch in '\u3040'..'\u309F' -> {
                hiraganaCount += 1
                totalLetterCount += 1
            }

            ch in '\u30A0'..'\u30FF' -> {
                katakanaCount += 1
                totalLetterCount += 1
            }

            ch in '\uAC00'..'\uD7A3' || ch in '\u3130'..'\u318F' -> {
                hangulCount += 1
                totalLetterCount += 1
            }

            ch in '\u0900'..'\u097F' -> {
                devanagariCount += 1
                totalLetterCount += 1
            }

            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                latinCount += 1
                totalLetterCount += 1
            }

            Character.isLetterOrDigit(ch) -> {
                totalLetterCount += 1
            }
        }
    }

    val safeTotalLetters = totalLetterCount.coerceAtLeast(1).toFloat()

    when (primaryLang) {
        "ja" -> {
            val katakanaRatio = katakanaCount.toFloat() / safeTotalLetters
            val hasJaMultiPattern = JA_FUNCTION_PATTERNS.any { text.contains(it) }
            val hasJaParticle = hiraganaCount >= 1 && JA_PARTICLES.any { text.contains(it) }

            when {
                hiraganaCount == 0 && kanjiCount == 0 && katakanaCount > 0 -> {
                    // ひらがな・漢字を含まない純カタカナ音写は強く減点する
                    score -= 1.6f
                }

                katakanaRatio >= 0.50f -> {
                    // 半分以上がカタカナのテキストは、助詞や「です」が混ざっていても外来語音写の可能性が高いためボーナスを与えず減点する
                    score -= katakanaRatio * 1.5f
                }

                katakanaRatio >= 0.30f -> {
                    // カタカナ比率が 30%〜50% の場合も減点し、日本語ボーナスを半分に抑える
                    score -= katakanaRatio * 1.0f
                    if (kanjiCount >= 1 && hiraganaCount >= 2) {
                        score += 0.35f
                    } else if (hiraganaCount >= 2) {
                        score += 0.15f
                    }
                    if (hasJaMultiPattern || hasJaParticle) {
                        score += 0.2f
                    }
                }

                else -> {
                    if (kanjiCount >= 1 && hiraganaCount >= 2) {
                        score += 0.7f
                    } else if (hiraganaCount >= 2) {
                        score += 0.35f
                    }
                    if (hasJaMultiPattern || hasJaParticle) {
                        score += 0.45f
                    }
                }
            }
        }

        "ko" -> {
            if (hangulCount > 0) {
                val hangulRatio = hangulCount.toFloat() / safeTotalLetters
                score += hangulRatio * 0.55f

                val hasKoGrammar = KO_GRAMMAR_PATTERNS.any { text.contains(it) }
                if (hasKoGrammar) {
                    score += 0.55f
                }
                val tokens = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val avgTokenLen = if (tokens.isNotEmpty()) {
                    tokens.sumOf { it.length }.toFloat() / tokens.size.toFloat()
                } else {
                    0f
                }
                val hasPoliteEnding = KO_POLITE_ENDINGS.any { text.contains(it) }
                if (tokens.size >= 3 && avgTokenLen <= 2.2f && !hasPoliteEnding) {
                    score -= 0.6f
                }
            } else {
                score -= 1.0f
            }
        }

        "zh", "yue" -> {
            if (totalLetterCount > 0) {
                score += (kanjiCount.toFloat() / safeTotalLetters) * 0.65f
            }
            if (hiraganaCount == 0 && katakanaCount == 0 && ZH_COMMON_CHARS.any { text.contains(it) }) {
                score += 0.5f
            } else if (kanjiCount >= 5 && ZH_COMMON_CHARS.none { text.contains(it) }) {
                score -= 0.45f
            }
            if (hiraganaCount > 0 || katakanaCount > 0 || hangulCount > 0) {
                score -= 0.8f
            }
        }

        "hi" -> {
            if (totalLetterCount > 0) {
                score += (devanagariCount.toFloat() / safeTotalLetters) * 1.1f
            }
        }

        else -> {
            if (totalLetterCount > 0) {
                score += (latinCount.toFloat() / safeTotalLetters) * 0.95f
            }
            val targetFunctionWords = latinFunctionWordsFor(primaryLang)
            if (targetFunctionWords != null && latinCount > 0) {
                val words = extractLatinWordsLower(text)
                val matchedCount = words.count { it in targetFunctionWords }
                when {
                    matchedCount >= 2 -> score += 0.55f
                    matchedCount == 1 -> score += 0.30f
                }
                if (hasCharacteristicLatinDiacritics(text, primaryLang)) {
                    score += 0.25f
                }
                if (words.size >= 4 && matchedCount == 0) {
                    val foreignMatches = maxForeignLatinFunctionWordMatches(words, primaryLang)
                    val avgWordLen = words.sumOf { it.length }.toFloat() / words.size.toFloat()
                    if (foreignMatches >= 2) {
                        score -= 0.90f
                    } else if (foreignMatches == 1 || avgWordLen <= 2.7f) {
                        score -= 0.50f
                    }
                }
            }
        }
    }

    return score
}

/**
 * 日本語認識結果 [text] が外国語音声のカタカナ外来語化（音写）である疑いが高いかどうかを判定する。
 */
internal fun isKatakanaTransliterationSuspected(
    text: String,
    langTag: String,
): Boolean {
    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    if (primaryLang != "ja") return false

    var kanjiCount = 0
    var hiraganaCount = 0
    var katakanaCount = 0
    var totalLetterCount = 0
    for (ch in text) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> {
                kanjiCount += 1
                totalLetterCount += 1
            }

            ch in '\u3040'..'\u309F' -> {
                hiraganaCount += 1
                totalLetterCount += 1
            }

            ch in '\u30A0'..'\u30FF' -> {
                katakanaCount += 1
                totalLetterCount += 1
            }

            Character.isLetterOrDigit(ch) -> {
                totalLetterCount += 1
            }
        }
    }
    if (totalLetterCount <= 0 || katakanaCount <= 0) return false
    if (hiraganaCount == 0 && kanjiCount == 0) return true
    val katakanaRatio = katakanaCount.toFloat() / totalLetterCount.toFloat()
    return katakanaRatio >= KATAKANA_SUSPECT_RATIO_THRESHOLD
}

/**
 * 認識結果 [text] が指定言語 [langTag] の自然な発話ではなく、別言語の音写（外来語化・空耳当て字）である疑いが高いかを判定する。
 *
 * ML Kit GenAI Speech Recognition や `sherpa-onnx` のように `confidence == null` を返すエンジンでも、
 * 誤った言語の認識器が別言語の音声を無理やり音写した結果（日本語のカタカナ音写、韓国語の短音節羅列、
 * ラテン文字言語間の機能語欠落・他言語機能語混入など）を検出してもう一方の言語でのフォールバック検証を発火させる。
 */
internal fun isUnnaturalOrTransliterationSuspected(
    text: String,
    langTag: String,
): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    if (primaryLang == "ja") {
        return isKatakanaTransliterationSuspected(trimmed, langTag)
    }

    var kanjiCount = 0
    var hiraganaCount = 0
    var katakanaCount = 0
    var hangulCount = 0
    var devanagariCount = 0
    var arabicCount = 0
    var cyrillicCount = 0
    var thaiCount = 0
    var latinCount = 0
    var totalLetterCount = 0
    for (ch in trimmed) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> {
                kanjiCount += 1
                totalLetterCount += 1
            }

            ch in '\u3040'..'\u309F' -> {
                hiraganaCount += 1
                totalLetterCount += 1
            }

            ch in '\u30A0'..'\u30FF' -> {
                katakanaCount += 1
                totalLetterCount += 1
            }

            ch in '\uAC00'..'\uD7A3' || ch in '\u3130'..'\u318F' -> {
                hangulCount += 1
                totalLetterCount += 1
            }

            ch in '\u0900'..'\u097F' -> {
                devanagariCount += 1
                totalLetterCount += 1
            }

            ch in '\u0600'..'\u06FF' -> {
                arabicCount += 1
                totalLetterCount += 1
            }

            ch in '\u0400'..'\u04FF' -> {
                cyrillicCount += 1
                totalLetterCount += 1
            }

            ch in '\u0E00'..'\u0E7F' -> {
                thaiCount += 1
                totalLetterCount += 1
            }

            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                latinCount += 1
                totalLetterCount += 1
            }

            Character.isLetterOrDigit(ch) -> {
                totalLetterCount += 1
            }
        }
    }
    if (totalLetterCount <= 0) return false

    return when (primaryLang) {
        "ko" -> {
            if (hangulCount == 0) return true
            val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            if (tokens.size >= 3) {
                val avgTokenLen = tokens.sumOf { it.length }.toFloat() / tokens.size.toFloat()
                val hasPoliteEnding = KO_POLITE_ENDINGS.any { trimmed.contains(it) }
                val hasKoGrammar = KO_GRAMMAR_PATTERNS.any { trimmed.contains(it) }
                avgTokenLen <= 2.2f && !hasPoliteEnding && !hasKoGrammar
            } else {
                false
            }
        }

        "zh", "yue" -> {
            if (kanjiCount == 0 || hiraganaCount > 0 || katakanaCount > 0 || hangulCount > 0) {
                true
            } else {
                kanjiCount >= 6 && ZH_COMMON_CHARS.none { trimmed.contains(it) }
            }
        }

        "hi", "mr", "ne", "sa" -> devanagariCount == 0

        "ar", "fa", "ur" -> arabicCount == 0

        "ru", "uk", "bg", "sr" -> cyrillicCount == 0

        "th" -> thaiCount == 0

        else -> {
            val targetWords = latinFunctionWordsFor(primaryLang)
            if (targetWords != null) {
                if (latinCount == 0) return true
                val words = extractLatinWordsLower(trimmed)
                if (words.size >= 4 && words.none { it in targetWords }) {
                    val foreignMatches = maxForeignLatinFunctionWordMatches(words, primaryLang)
                    val avgWordLen = words.sumOf { it.length }.toFloat() / words.size.toFloat()
                    foreignMatches >= 2 || avgWordLen <= 2.5f
                } else {
                    false
                }
            } else {
                false
            }
        }
    }
}

private enum class RecognizedScript {
    HAN,
    KANA,
    HANGUL,
    DEVANAGARI,
    ARABIC,
    CYRILLIC,
    THAI,
    GREEK,
    HEBREW,
    LATIN,
}

private fun expectedScriptsForLanguage(langTag: String): Set<RecognizedScript> = when (langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)) {
    "ja" -> setOf(RecognizedScript.HAN, RecognizedScript.KANA)
    "zh", "yue" -> setOf(RecognizedScript.HAN)
    "ko" -> setOf(RecognizedScript.HANGUL)
    "hi", "mr", "ne", "sa" -> setOf(RecognizedScript.DEVANAGARI)
    "ar", "fa", "ur" -> setOf(RecognizedScript.ARABIC)
    "ru", "uk", "bg", "sr", "mk", "be", "kk", "ky", "mn" -> setOf(RecognizedScript.CYRILLIC)
    "th" -> setOf(RecognizedScript.THAI)
    "el" -> setOf(RecognizedScript.GREEK)
    "he", "iw", "yi" -> setOf(RecognizedScript.HEBREW)
    else -> setOf(RecognizedScript.LATIN)
}

private fun scriptOfChar(ch: Char): RecognizedScript? = when {
    ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> RecognizedScript.KANA
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> RecognizedScript.HAN
    ch in '\uAC00'..'\uD7A3' || ch in '\u3130'..'\u318F' -> RecognizedScript.HANGUL
    ch in '\u0900'..'\u097F' -> RecognizedScript.DEVANAGARI
    ch in '\u0600'..'\u06FF' -> RecognizedScript.ARABIC
    ch in '\u0400'..'\u04FF' -> RecognizedScript.CYRILLIC
    ch in '\u0E00'..'\u0E7F' -> RecognizedScript.THAI
    ch in '\u0370'..'\u03FF' -> RecognizedScript.GREEK
    ch in '\u0590'..'\u05FF' -> RecognizedScript.HEBREW
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' || ch in '\u1E00'..'\u1EFF' -> RecognizedScript.LATIN
    else -> null
}

/**
 * 認識された [text] の文字体系を検査し、[currentLang] には使われず [otherLang] で使われる文字体系が
 * 全文字数の過半数（> 50%）を占める場合は [otherLang] に訂正して返す。
 */
internal fun resolveLanguageByRecognizedScript(
    text: String,
    currentLang: LanguageTag,
    otherLang: LanguageTag,
): LanguageTag {
    if (currentLang.value.equals(otherLang.value, ignoreCase = true)) return currentLang
    val currentScripts = expectedScriptsForLanguage(currentLang.value)
    val otherScripts = expectedScriptsForLanguage(otherLang.value)
    if (currentScripts == otherScripts) return currentLang

    var totalLetters = 0
    var otherOnlyCount = 0
    for (ch in text) {
        val script = scriptOfChar(ch)
        if (script != null) {
            totalLetters += 1
            if (script in otherScripts && script !in currentScripts) {
                otherOnlyCount += 1
            }
        } else if (Character.isLetter(ch)) {
            totalLetters += 1
        }
    }
    if (totalLetters <= 0) return currentLang
    return if (otherOnlyCount * 2 > totalLetters) otherLang else currentLang
}

private fun extractLatinWordsLower(text: String): List<String> = LATIN_WORD_REGEX.findAll(text.lowercase(Locale.ROOT))
    .map { it.value }
    .toList()

private fun latinFunctionWordsFor(primaryLang: String): Set<String>? = LATIN_FUNCTION_WORDS_BY_LANG[primaryLang]

private fun maxForeignLatinFunctionWordMatches(words: List<String>, currentLang: String): Int {
    var maxMatches = 0
    for ((lang, funcWords) in LATIN_FUNCTION_WORDS_BY_LANG) {
        if (lang == currentLang) continue
        val matches = words.count { it in funcWords && it !in (LATIN_FUNCTION_WORDS_BY_LANG[currentLang] ?: emptySet()) }
        if (matches > maxMatches) {
            maxMatches = matches
        }
    }
    return maxMatches
}

private fun hasCharacteristicLatinDiacritics(text: String, primaryLang: String): Boolean = when (primaryLang) {
    "es" -> text.any { it in "áéíóúñüÁÉÍÓÚÑÜ¿¡" }
    "fr" -> text.any { it in "éèêëàâùûçœæÉÈÊËÀÂÙÛÇŒÆ" }
    "de" -> text.any { it in "äöüßÄÖÜ" }
    "it" -> text.any { it in "àèéìòóùÀÈÉÌÒÓÙ" }
    "pt" -> text.any { it in "ãõçáéíóúâêôàÃÕÇÁÉÍÓÚÂÊÔÀ" }
    "vi" -> text.any { it in "ăâđêôơưĂÂĐÊÔƠƯáàảãạéèẻẽẹíìỉĩịóòỏõọúùủũụýỳỷỹỵ" }
    else -> false
}

private const val CONFIDENCE_SCORE_WEIGHT: Float = 3.0f
private const val DEFAULT_PROBE_CONFIDENCE: Float = 0.55f
private const val LOW_CONFIDENCE_PENALTY_THRESHOLD: Float = 0.60f
private const val LOW_CONFIDENCE_PENALTY_WEIGHT: Float = 1.5f
private const val KATAKANA_SUSPECT_RATIO_THRESHOLD: Float = 0.35f
private val WHITESPACE_REGEX = Regex("\\s+")
private val LATIN_WORD_REGEX = Regex("[a-z\\u00C0-\\u024F']+")

private val LATIN_FUNCTION_WORDS_BY_LANG: Map<String, Set<String>> = mapOf(
    "en" to setOf(
        "the", "and", "to", "of", "in", "is", "it", "you", "that", "for", "on", "with", "as", "are", "this",
        "was", "be", "at", "have", "from", "or", "by", "but", "not", "what", "all", "were", "we", "when",
        "your", "can", "there", "use", "an", "each", "which", "she", "do", "how", "their", "if", "will",
        "would", "like", "my", "name", "good", "morning", "hello", "please", "thank", "where", "could",
    ),
    "es" to setOf(
        "de", "la", "que", "el", "en", "y", "los", "se", "del", "las", "un", "por", "con", "no", "una",
        "su", "para", "es", "al", "lo", "como", "más", "mas", "pero", "sus", "le", "ha", "me", "si", "sin",
        "sobre", "este", "ya", "entre", "cuando", "todo", "esta", "ser", "son", "dos", "también", "hola",
        "gracias", "favor", "buenos", "días", "donde", "dónde", "está", "estoy", "quiero", "gustaría",
    ),
    "fr" to setOf(
        "de", "la", "le", "et", "les", "des", "en", "un", "du", "une", "que", "est", "pour", "qui", "dans",
        "ce", "il", "pas", "plus", "par", "sur", "se", "ne", "avec", "tout", "mais", "comme", "ou", "son",
        "aux", "cette", "nous", "vous", "je", "suis", "bonjour", "merci", "s'il", "plaît", "voudrais", "où",
    ),
    "de" to setOf(
        "der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich", "des", "auf", "für", "ist",
        "im", "dem", "nicht", "ein", "eine", "als", "auch", "es", "an", "werden", "aus", "er", "hat", "dass",
        "sie", "nach", "wird", "bei", "einer", "um", "am", "sind", "noch", "wie", "einem", "über", "ich",
        "guten", "morgen", "tag", "danke", "bitte", "möchte", "haben", "wir", "ihnen",
    ),
    "it" to setOf(
        "di", "che", "è", "e", "la", "il", "un", "a", "per", "in", "una", "mi", "sono", "ho", "ma", "non",
        "le", "si", "lo", "ha", "cosa", "con", "ti", "se", "da", "come", "ci", "no", "questo", "qui", "bene",
        "sei", "del", "della", "buongiorno", "grazie", "favore", "vorrei", "dove",
    ),
    "pt" to setOf(
        "de", "a", "o", "que", "e", "do", "da", "em", "um", "para", "é", "com", "não", "uma", "os", "no",
        "se", "na", "por", "mais", "as", "dos", "como", "mas", "foi", "ao", "ele", "das", "tem", "à", "seu",
        "sua", "ou", "ser", "quando", "muito", "há", "nos", "já", "está", "eu", "bom", "dia", "obrigado",
    ),
    "id" to setOf(
        "yang", "dan", "di", "ini", "untuk", "dengan", "tidak", "itu", "dari", "dalam", "akan", "pada",
        "juga", "saya", "ke", "karena", "tersebut", "bisa", "ada", "mereka", "lebih", "sudah", "atau",
        "oleh", "apa", "kita", "anda", "kami", "selamat", "terima", "kasih",
    ),
    "vi" to setOf(
        "của", "và", "các", "có", "trong", "được", "cho", "là", "không", "người", "một", "những",
        "này", "đã", "với", "để", "từ", "khi", "đến", "về", "như", "tôi", "bạn", "chúng", "xin", "chào", "cảm",
    ),
)

private val JA_FUNCTION_PATTERNS: List<String> = listOf(
    "です",
    "ます",
    "した",
    "して",
    "さい",
    "しょう",
    "という",
    "から",
    "では",
    "には",
    "ません",
    "ください",
    "ましょう",
    "について",
    "ように",
    "ている",
)

private val JA_PARTICLES: List<String> = listOf("の", "を", "が", "は", "に", "で")

private val KO_GRAMMAR_PATTERNS: List<String> = listOf(
    "습니다",
    "습니까",
    "세요",
    "해요",
    "어요",
    "아요",
    "예요",
    "이에요",
    "네요",
    "지만",
    "에서",
    "으로",
    "에게",
    "부터",
    "까지",
    "니다",
    "니까",
    "을",
    "를",
    "은",
    "는",
)

private val KO_POLITE_ENDINGS: List<String> = listOf(
    "습니다",
    "습니까",
    "세요",
    "해요",
    "어요",
    "아요",
    "예요",
    "이에요",
    "네요",
    "니다",
    "니까",
)

private val ZH_COMMON_CHARS: List<String> = listOf(
    "的",
    "了",
    "是",
    "在",
    "我",
    "你",
    "他",
    "们",
    "这",
    "那",
    "不",
    "有",
    "吗",
    "呢",
    "个",
    "和",
    "到",
    "说",
    "要",
    "会",
    "可",
    "以",
)

/**
 * Android 標準の [SpeechRecognizer.createOnDeviceSpeechRecognizer]（Stage 1）、
 * `ML Kit GenAI Speech Recognition`（AICore 対応端末）、および
 * `sherpa-onnx` ローカル Whisper モデル（Stage 2）を組み合わせた [OnDeviceChunkTranscriber] 実装（F6.2）。
 *
 * - 利用者が選択した [OnDeviceTranscriptionEngine]（`AUTO` / `ANDROID_SPEECH_RECOGNIZER` / `SHERPA_ONNX` / `ML_KIT`）に従って
 *   文字起こし処理を進める。
 * - `ML_KIT` が選択されている場合は、AICore / ML Kit GenAI 音声認識対応端末では ML Kit を最優先で使用し、
 *   非対応端末・非対応言語・バックグラウンド制約時は `AUTO`（Android 標準 `SpeechRecognizer` → `sherpa-onnx`）へ自動フォールバックする。
 * - `AUTO` では Android 標準 `SpeechRecognizer` を優先し、OS が非対応（`UNSUPPORTED`）の言語については
 *   ML Kit（対応時）または `sherpa-onnx` ローカルモデルへフォールバックする。
 * - `SHERPA_ONNX` が選択されている場合は、チェックアウト済みの `sherpa-onnx` ローカルモデルを優先して使用する。
 */
@Singleton
class AndroidSpeechRecognizerChunkTranscriber internal constructor(
    @ApplicationContext private val context: Context,
    private val audioStreamDecoder: AudioStreamDecoder,
    private val onDeviceAvailabilityChecker: () -> Boolean,
    private val standardAvailabilityChecker: () -> Boolean,
    private val attemptRunner: (
        suspend (
            pcm16LeBytes: ByteArray,
            sampleRateHz: Int,
            bcp47LocaleTag: String,
            preferOnDevice: Boolean,
        ) -> SpeechRecognitionAttemptResult?
    )? = null,
    private val languageDetector: (
        (
            pcm16kMono: ShortArray,
            targetTag: String,
            baseTag: String,
        ) -> LanguagePairPrediction?
    )? = null,
    modelGateway: RecognitionModelGateway? = null,
    private val sherpaModelManager: SherpaOnnxModelManager? = null,
    private val sherpaTranscriber: (
        (
            pcm16LeBytes: ByteArray,
            sampleRateHz: Int,
            language: LanguageTag,
        ) -> OnDeviceChunkTranscriptionResult?
    )? = null,
    private val mlKitGateway: MlKitSpeechRecognitionGateway? = null,
    private val engineProvider: suspend () -> OnDeviceTranscriptionEngine = { OnDeviceTranscriptionEngine.AUTO },
    /**
     * 言語判定と同時にエンコーダ出力の平均ベクトルも返す判定器（[WhisperTinyLanguageDetector.analyzeLanguagePair]）。
     * `null` のときは [languageDetector] だけを使い、ファイル内での補正（[VoiceProfileLanguageTracker]）は行わない。
     */
    private val languageAnalyzer: (
        (
            pcm16kMono: ShortArray,
            targetTag: String,
            baseTag: String,
        ) -> LanguagePairAnalysis?
    )? = null,
    /**
     * 判定器の推論の累計（[WhisperTinyLanguageDetector.inferenceStats]）。
     * 一括言語判定の終わりに、どの推論エンジン（onnxruntime / kotlin）で判定したかと推論時間を診断ログに出すために使う。
     */
    private val languageDetectionStats: (() -> LidInferenceStats)? = null,
    /**
     * 一括言語判定の音声の切り出しの方式（[LidExtractionMethod]）。本番の既定は [DEFAULT_LID_EXTRACTION_METHOD]。
     * 速い方式を使えない場合（デコーダが [MediaCodecAudioStreamDecoder] でないなど）は今の方式で切り出す。
     */
    private val languageOnlyExtractionMethod: LidExtractionMethod = DEFAULT_LID_EXTRACTION_METHOD,
    /** 順に流す切り出し（[LidExtractionMethod.STREAMING]）の設定。 */
    private val streamingExtractionOptions: StreamingExtractionOptions = StreamingExtractionOptions(),
    /**
     * 一括言語判定で切り出した PCM を、チャンクの番号と一緒に受け取る（計測ツールが方式ごとの PCM の一致を確かめるため。本番では `null`）。
     * 複数のスレッドから呼ばれることがある。
     */
    private val languageOnlyPcmObserver: ((index: Int, pcm: ShortArray) -> Unit)? = null,
    private val sherpaWhisperTranscriberInstance: SherpaOnnxWhisperTranscriber? = null,
    private val senseVoiceTranscriberInstance: SherpaOnnxSenseVoiceTranscriber? = null,
    private val moonshineTranscriberInstance: MoonshineOnnxTranscriber? = null,
    private val cloudSpeechTranscriberInstance: CloudSpeechTranscriber? = null,
) : OnDeviceChunkTranscriber {

    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        audioStreamDecoder: AudioStreamDecoder,
        whisperTinyLanguageDetector: WhisperTinyLanguageDetector,
        sherpaOnnxModelManager: SherpaOnnxModelManager,
        sherpaOnnxWhisperTranscriber: SherpaOnnxWhisperTranscriber,
        sherpaOnnxSenseVoiceTranscriber: SherpaOnnxSenseVoiceTranscriber,
        moonshineOnnxTranscriber: MoonshineOnnxTranscriber,
        cloudSpeechTranscriber: CloudSpeechTranscriber,
        mlKitGenAiSpeechTranscriber: MlKitGenAiSpeechTranscriber,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        context = context,
        audioStreamDecoder = audioStreamDecoder,
        onDeviceAvailabilityChecker = {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        },
        standardAvailabilityChecker = {
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        },
        attemptRunner = null,
        languageDetector = { pcm, target, base ->
            whisperTinyLanguageDetector.detectLanguagePair(pcm, target, base)
        },
        modelGateway = null,
        sherpaModelManager = sherpaOnnxModelManager,
        sherpaTranscriber = { pcmBytes, sampleRate, lang ->
            sherpaOnnxWhisperTranscriber.transcribePcm16Le(
                pcm16LeBytes = pcmBytes,
                sampleRateHz = sampleRate,
                language = lang,
            )
        },
        mlKitGateway = mlKitGenAiSpeechTranscriber,
        engineProvider = {
            runCatching { userSettingsRepository.settings.first().transcriptionEngine }
                .getOrDefault(OnDeviceTranscriptionEngine.AUTO)
        },
        languageAnalyzer = { pcm, target, base ->
            whisperTinyLanguageDetector.analyzeLanguagePair(pcm, target, base)
        },
        languageDetectionStats = { whisperTinyLanguageDetector.inferenceStats() },
        sherpaWhisperTranscriberInstance = sherpaOnnxWhisperTranscriber,
        senseVoiceTranscriberInstance = sherpaOnnxSenseVoiceTranscriber,
        moonshineTranscriberInstance = moonshineOnnxTranscriber,
        cloudSpeechTranscriberInstance = cloudSpeechTranscriber,
    )

    internal constructor(
        context: Context,
        audioStreamDecoder: AudioStreamDecoder,
    ) : this(
        context = context,
        audioStreamDecoder = audioStreamDecoder,
        defaultLanguageDetector = WhisperTinyLanguageDetector(context),
    )

    private constructor(
        context: Context,
        audioStreamDecoder: AudioStreamDecoder,
        defaultLanguageDetector: WhisperTinyLanguageDetector,
    ) : this(
        context = context,
        audioStreamDecoder = audioStreamDecoder,
        onDeviceAvailabilityChecker = {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        },
        standardAvailabilityChecker = {
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        },
        attemptRunner = null,
        languageDetector = { pcm, target, base -> defaultLanguageDetector.detectLanguagePair(pcm, target, base) },
        languageAnalyzer = { pcm, target, base -> defaultLanguageDetector.analyzeLanguagePair(pcm, target, base) },
        languageDetectionStats = { defaultLanguageDetector.inferenceStats() },
    )

    internal val voiceProfileTracker: VoiceProfileLanguageTracker = VoiceProfileLanguageTracker()

    /** 言語判定のための音声の切り出しの各段階の時間の累計（[languageOnlyExtractionTimings]）。 */
    private val languageOnlyTimings = LidExtractionTimings()

    /** 直近の一括言語判定で実際に使った切り出しの方式（指定した方式を使えずに今の方式にした場合を含む）。まだなければ `null`。 */
    @Volatile
    internal var lastLanguageOnlyExtractionMethod: LidExtractionMethod? = null
        private set

    /** 言語判定のための音声の切り出しの各段階の時間の累計（計測ツールが前後の差を取る）。 */
    internal fun languageOnlyExtractionTimings(): LidExtractionTimingsSnapshot = languageOnlyTimings.snapshot()

    private data class ChunkAudioCacheScope(
        val audioPath: String,
        val lastModified: Long,
        val startMs: Long,
        val endMs: Long,
    )

    internal data class ChunkSttCacheKey(
        val audioPath: String,
        val lastModified: Long,
        val startMs: Long,
        val endMs: Long,
        val normalizedLangTag: String,
    )

    private val sttCacheLock = Any()
    private val fullChunkSttCache = object : LinkedHashMap<ChunkSttCacheKey, OnDeviceChunkTranscriptionResult>(
        MAX_STT_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChunkSttCacheKey, OnDeviceChunkTranscriptionResult>?,
        ): Boolean = size > MAX_STT_CACHE_ENTRIES
    }

    private val recognitionLock = PriorityRecognitionLock()
    private var lastPipeAttemptCompletedRealtimeMs: Long = 0L

    private val recognitionModelGateway: RecognitionModelGateway =
        modelGateway ?: AndroidRecognitionModelGateway(context)
    private val modelIssuesState =
        MutableStateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>(emptyMap())

    /** 自動でダウンロードを要求済みの言語（BCP-47、小文字）。同じ言語の要求はプロセスごとに 1 回だけ。 */
    private val autoDownloadRequestedTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ML Kit GenAI 向けに自動でダウンロードを要求済みの言語（BCP-47、小文字）。 */
    private val autoDownloadMlKitRequestedTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 言語タグ（小文字）ごとに端末で動作または対応が確認された BCP-47 ロケールタグのキャッシュ。 */
    private val resolvedBcp47TagByLangKey: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    @Volatile
    private var lastModelIssueRefreshRealtimeMs: Long = 0L

    @Volatile
    private var activeTrialEngineOverride: OnDeviceTranscriptionEngine? = null

    @Volatile
    internal var activeTrialContextHint: String? = null

    @Volatile
    private var activePrecedingContextByLanguage: Map<String, String> = emptyMap()

    private data class ActiveJobHandle(
        val job: Job,
        val highPriority: Boolean,
        val startedAtEpochMs: Long,
    )

    private val activeJobsLock = Any()
    private val activeJobHandles = LinkedHashSet<ActiveJobHandle>()

    private val activeStepMutableState =
        MutableStateFlow<OnDeviceChunkTranscriber.ActiveTranscriptionStepInfo?>(null)

    private val recentDiagnosticsMutableState =
        MutableStateFlow<List<TranscriptionDiagnosticEntry>>(emptyList())

    override val recognitionModelIssues: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>> =
        modelIssuesState.asStateFlow()

    override val activeStepState: StateFlow<OnDeviceChunkTranscriber.ActiveTranscriptionStepInfo?> =
        activeStepMutableState.asStateFlow()

    override val recentDiagnostics: StateFlow<List<TranscriptionDiagnosticEntry>> =
        recentDiagnosticsMutableState.asStateFlow()

    override fun cancelActiveTranscription(onlyLowPriority: Boolean) {
        val toCancel = synchronized(activeJobsLock) {
            activeJobHandles.filter { handle ->
                !onlyLowPriority || !handle.highPriority
            }
        }
        recognitionLock.cancelWaiters(onlyLowPriority = onlyLowPriority)
        for (handle in toCancel) {
            handle.job.cancel(
                CancellationException(
                    if (onlyLowPriority) {
                        "Preempted by high-priority forced transcription"
                    } else {
                        "Cancelled by user turning off transcription"
                    },
                ),
            )
        }
        if (!onlyLowPriority) {
            activeStepMutableState.value = null
            releaseChunkDecodeSession()
        }
    }

    /** チャンク切り出し用に開いたままにしている抽出器を解放する（次の切り出しで開き直す）。 */
    private fun releaseChunkDecodeSession() {
        (audioStreamDecoder as? MediaCodecAudioStreamDecoder)?.releaseRangeSession()
    }

    override fun releaseIdleResources() {
        releaseChunkDecodeSession()
    }

    override fun resetTranscriberState() {
        cancelActiveTranscription(onlyLowPriority = false)
        recognitionLock.forceResetLock()
        voiceProfileTracker.clearAll()
        activeStepMutableState.value = null
        appendDiagnostic(
            stage = "RESET",
            message = "文字起こしエンジンと認識待機ロックをリセットしました",
            isWarningOrError = false,
        )
    }

    override fun recordExternalDiagnostic(
        stage: String,
        message: String,
        isWarningOrError: Boolean,
    ) {
        appendDiagnostic(
            stage = stage,
            message = message,
            isWarningOrError = isWarningOrError,
        )
    }

    private fun updateActiveStep(
        step: TranscriptionStep,
        detail: String?,
        startedAtEpochMs: Long,
        highPriority: Boolean,
    ) {
        activeStepMutableState.value = OnDeviceChunkTranscriber.ActiveTranscriptionStepInfo(
            step = step,
            detail = detail,
            startedAtEpochMs = startedAtEpochMs,
            highPriority = highPriority,
        )
    }

    private fun clearActiveStepIfMatches(startedAtEpochMs: Long) {
        activeStepMutableState.update { current ->
            if (current == null || current.startedAtEpochMs == startedAtEpochMs) null else current
        }
    }

    private fun appendDiagnostic(
        stage: String,
        message: String,
        isWarningOrError: Boolean = false,
    ) {
        val entry = TranscriptionDiagnosticEntry(
            timestampEpochMs = System.currentTimeMillis(),
            stage = stage,
            message = message,
            isWarningOrError = isWarningOrError,
        )
        recentDiagnosticsMutableState.update { current ->
            (current + entry).takeLast(MAX_DIAGNOSTIC_ENTRIES)
        }
    }

    override fun isAvailable(): Boolean = onDeviceAvailabilityChecker() ||
        standardAvailabilityChecker() ||
        sherpaModelManager?.areCoreModelFilesPresent() == true ||
        sherpaTranscriber != null ||
        mlKitGateway?.isDevicePotentiallySupported() == true ||
        cloudSpeechTranscriberInstance != null

    private fun isMlKitPotentiallyAvailable(): Boolean = mlKitGateway?.isDevicePotentiallySupported() == true

    private fun isSherpaInstalledFor(language: LanguageTag?): Boolean {
        if (language == null) return false
        return sherpaModelManager?.getLanguageModelStatus(language) == OnDeviceLanguageModelStatus.INSTALLED
    }

    private fun isSherpaSupportedFor(language: LanguageTag?): Boolean {
        if (language == null) return false
        return sherpaModelManager != null && SherpaOnnxModelManager.isLanguageSupportedByWhisper(language)
    }

    private fun clearAllSttCache() {
        synchronized(sttCacheLock) {
            fullChunkSttCache.clear()
        }
    }

    override suspend fun queryDownloadableModelStates(): List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> {
        val manager = sherpaModelManager ?: return super.queryDownloadableModelStates()
        return manager.queryAllDownloadableModelStates()
    }

    override suspend fun downloadSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean {
        val manager = sherpaModelManager ?: return false
        val ok = manager.downloadModel(model)
        if (ok) {
            clearAllSttCache()
        }
        return ok
    }

    override suspend fun purgeSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean {
        val manager = sherpaModelManager ?: return false
        val ok = manager.purgeModel(model)
        if (ok) {
            clearAllSttCache()
        }
        return ok
    }

    override suspend fun downloadSherpaModel(language: LanguageTag): Boolean {
        val manager = sherpaModelManager ?: return false
        setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
        val ok = manager.downloadModelForLanguage(language)
        if (ok) {
            clearModelIssue(language)
            clearAllSttCache()
        } else {
            setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
        return ok
    }

    override suspend fun deleteSherpaModel(language: LanguageTag): Boolean {
        val manager = sherpaModelManager ?: return false
        val ok = manager.deleteModelForLanguage(language)
        if (ok) {
            clearAllSttCache()
        }
        return ok
    }

    override suspend fun deleteSttModelForLanguage(
        language: LanguageTag,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ): Boolean {
        val manager = sherpaModelManager ?: return false
        val ok = manager.deleteModelForLanguage(model, language)
        if (ok) {
            clearAllSttCache()
        }
        return ok
    }

    override suspend fun requestSttModelDownload(language: LanguageTag, engine: com.miuraken.chunkflow.core.model.SttEngine): Boolean {
        return when (engine) {
            com.miuraken.chunkflow.core.model.SttEngine.ML_KIT -> {
                if (!isMlKitPotentiallyAvailable()) return requestRecognitionModelDownload(language)
                val gateway = mlKitGateway ?: return requestRecognitionModelDownload(language)
                val candidates = candidateBcp47TagsFor(language.value)
                val firstTag = candidates.firstOrNull() ?: return false
                val mlKitSupport = runCatching { gateway.checkMlKitSupport(candidates) }.getOrDefault(emptyMap())
                val targetTag = candidates.firstOrNull {
                    mlKitSupport[it]?.first != null && mlKitSupport[it]?.first != RecognitionModelSupport.UNSUPPORTED
                }
                if (targetTag == null) {
                    return requestRecognitionModelDownload(language)
                }
                autoDownloadMlKitRequestedTags.add(firstTag.lowercase(java.util.Locale.ROOT))

                var success = false
                val ok = gateway.downloadModel(targetTag, onEvent = { event ->
                    if (event == RecognitionModelDownloadEvent.SUCCESS || event == RecognitionModelDownloadEvent.SCHEDULED) {
                        success = true
                    }
                })
                if (ok || success) {
                    true
                } else {
                    requestRecognitionModelDownload(language)
                }
            }

            com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER -> {
                requestRecognitionModelDownload(language)
            }

            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX -> {
                downloadSherpaModel(language)
            }

            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE,
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE,
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL,
            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE,
            -> {
                val model = engine.downloadableModel ?: return false
                val manager = sherpaModelManager ?: return false
                val ok = manager.downloadModelForLanguage(language, model)
                if (ok) {
                    clearModelIssue(language)
                    clearAllSttCache()
                }
                ok
            }

            com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API,
            com.miuraken.chunkflow.core.model.SttEngine.GROQ_API,
            com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API,
            -> false
        }
    }

    override suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean {
        val selectedEngine = runCatching { engineProvider() }.getOrDefault(OnDeviceTranscriptionEngine.AUTO)
        if (selectedEngine == OnDeviceTranscriptionEngine.SHERPA_ONNX && isSherpaSupportedFor(language)) {
            return downloadSherpaModel(language)
        }
        val candidates = candidateBcp47TagsFor(language.value)
        val firstTag = candidates.firstOrNull() ?: return false
        if (!autoDownloadRequestedTags.add(firstTag.lowercase(java.util.Locale.ROOT))) return false
        if (selectedEngine == OnDeviceTranscriptionEngine.ML_KIT && isMlKitPotentiallyAvailable()) {
            val mlKitSupport = recognitionLock.withLock(highPriority = false) {
                runCatching { mlKitGateway?.checkMlKitSupport(candidates)?.mapValues { it.value.first }.orEmpty() }.getOrDefault(emptyMap())
            }
            val mlKitInstalled = candidates.firstOrNull { mlKitSupport[it] == RecognitionModelSupport.INSTALLED }
            if (mlKitInstalled != null) {
                rememberResolvedBcp47Tag(language.value, mlKitInstalled)
                clearModelIssue(language)
                return true
            }
            val mlKitDownloadable = candidates.firstOrNull {
                mlKitSupport[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED ||
                    mlKitSupport[it] == RecognitionModelSupport.PENDING
            }
            if (mlKitDownloadable != null) {
                rememberResolvedBcp47Tag(language.value, mlKitDownloadable)
                autoDownloadMlKitRequestedTags.add(mlKitDownloadable.lowercase(Locale.ROOT))
                return downloadMlKitModel(language, mlKitDownloadable)
            }
        }
        if (!onDeviceAvailabilityChecker()) {
            if (selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER && isMlKitPotentiallyAvailable()) {
                val mlKitSupport = recognitionLock.withLock(highPriority = false) {
                    runCatching { mlKitGateway?.checkMlKitSupport(candidates)?.mapValues { it.value.first }.orEmpty() }.getOrDefault(emptyMap())
                }
                val mlKitDownloadable = candidates.firstOrNull {
                    mlKitSupport[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED ||
                        mlKitSupport[it] == RecognitionModelSupport.PENDING ||
                        mlKitSupport[it] == RecognitionModelSupport.INSTALLED
                }
                if (mlKitDownloadable != null) {
                    rememberResolvedBcp47Tag(language.value, mlKitDownloadable)
                    return downloadMlKitModel(language, mlKitDownloadable)
                }
            }
            return if (selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER && isSherpaSupportedFor(language)) {
                downloadSherpaModel(language)
            } else {
                false
            }
        }
        val support = recognitionLock.withLock(highPriority = false) {
            try {
                recognitionModelGateway.checkSupport(candidates)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
        if (installedTag != null) {
            rememberResolvedBcp47Tag(language.value, installedTag)
            clearModelIssue(language)
            return true
        }
        val supportedOrPendingTag = candidates.firstOrNull {
            support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED ||
                support[it] == RecognitionModelSupport.PENDING
        }
        if (supportedOrPendingTag == null &&
            support.isNotEmpty() &&
            selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER &&
            isSherpaSupportedFor(language)
        ) {
            // OS の SpeechRecognizer が非対応の言語は Stage 2 の sherpa-onnx ローカルモデルをダウンロードする
            return downloadSherpaModel(language)
        }
        val bcp47Tag = supportedOrPendingTag ?: candidates.first()
        rememberResolvedBcp47Tag(language.value, bcp47Tag)
        autoDownloadRequestedTags.add(bcp47Tag.lowercase(Locale.ROOT))
        return startRecognitionModelDownload(language, bcp47Tag)
    }

    private suspend fun checkCombinedSupportUnderLock(
        bcp47Tags: List<String>,
        selectedEngine: OnDeviceTranscriptionEngine,
    ): Map<String, RecognitionModelSupport> {
        val osSupport = if (onDeviceAvailabilityChecker()) {
            try {
                recognitionModelGateway.checkSupport(bcp47Tags)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val mlKitSupport = if (isMlKitPotentiallyAvailable() &&
            selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER &&
            selectedEngine != OnDeviceTranscriptionEngine.SHERPA_ONNX
        ) {
            try {
                mlKitGateway?.checkMlKitSupport(bcp47Tags)?.mapValues { it.value.first }.orEmpty()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        if (mlKitSupport.isEmpty()) return osSupport
        if (osSupport.isEmpty()) return mlKitSupport

        val merged = LinkedHashMap<String, RecognitionModelSupport>()
        for (tag in (osSupport.keys + mlKitSupport.keys)) {
            val osVal = osSupport[tag]
            val mlVal = mlKitSupport[tag]
            merged[tag] = when {
                selectedEngine == OnDeviceTranscriptionEngine.ML_KIT &&
                    mlVal != null &&
                    mlVal != RecognitionModelSupport.UNSUPPORTED -> mlVal

                osVal != null && osVal != RecognitionModelSupport.UNSUPPORTED -> osVal

                mlVal != null && mlVal != RecognitionModelSupport.UNSUPPORTED -> mlVal

                else -> osVal ?: mlVal ?: RecognitionModelSupport.UNSUPPORTED
            }
        }
        return merged
    }

    override suspend fun refreshRecognitionModelIssues() {
        val issues = modelIssuesState.value
        if (issues.isEmpty()) return
        for (language in issues.keys) {
            if (isSherpaInstalledFor(language)) {
                clearModelIssue(language)
            }
        }
        val remainingIssues = modelIssuesState.value
        if (remainingIssues.isEmpty() || (!onDeviceAvailabilityChecker() && !isMlKitPotentiallyAvailable())) return
        val now = SystemClock.elapsedRealtime()
        if (lastModelIssueRefreshRealtimeMs > 0L &&
            now - lastModelIssueRefreshRealtimeMs in 0L until MODEL_ISSUE_REFRESH_MIN_INTERVAL_MS
        ) {
            return
        }
        lastModelIssueRefreshRealtimeMs = now
        val selectedEngine = runCatching { engineProvider() }.getOrDefault(OnDeviceTranscriptionEngine.AUTO)
        val candidatesByLanguage = remainingIssues.keys.associateWith { candidateBcp47TagsFor(it.value) }
        // 認識中の SpeechRecognizer と同時にサービスへ接続しないよう、認識ロックを取ってから問い合わせる
        val support = recognitionLock.withLock(highPriority = false) {
            checkCombinedSupportUnderLock(
                bcp47Tags = candidatesByLanguage.values.flatten().distinct(),
                selectedEngine = selectedEngine,
            )
        }
        for ((language, candidates) in candidatesByLanguage) {
            val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
            val pendingTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.PENDING }
            val supportedTag = candidates.firstOrNull {
                support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }
            when {
                installedTag != null || isSherpaInstalledFor(language) -> {
                    if (installedTag != null) {
                        logInfo("modelSupport lang=$installedTag installed")
                        rememberResolvedBcp47Tag(language.value, installedTag)
                    }
                    clearModelIssue(language)
                }

                pendingTag != null -> {
                    rememberResolvedBcp47Tag(language.value, pendingTag)
                    setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                }

                supportedTag != null -> {
                    rememberResolvedBcp47Tag(language.value, supportedTag)
                    if (remainingIssues[language] == OnDeviceRecognitionModelState.UNSUPPORTED) {
                        setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                    }
                }
            }
        }
    }

    override suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> {
        if (languages.isEmpty()) return emptyList()
        val mlKitAvailable = isMlKitPotentiallyAvailable()
        val osAvailable = onDeviceAvailabilityChecker()

        // 同一言語の別表記（"en", "en-US", "en-us" など）を正規化コード単位で1回だけ走査する
        val canonicalLanguages = LinkedHashMap<String, LanguageTag>()
        for (lang in languages) {
            val normCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(lang.value)
                .ifBlank { lang.value.lowercase(Locale.ROOT) }
            canonicalLanguages.putIfAbsent(normCode, LanguageTag(normCode))
        }
        val uniqueCanonicalList = canonicalLanguages.values.toList()
        val extraStatusesByCanonical = buildExtraEngineStatusesByLanguage(uniqueCanonicalList)

        if (!osAvailable && !mlKitAvailable) {
            return languages.map { lang ->
                val normCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(lang.value)
                    .ifBlank { lang.value.lowercase(Locale.ROOT) }
                val canonicalTag = canonicalLanguages[normCode] ?: lang
                withSherpaAndMlKitModelFields(
                    language = lang,
                    osStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                    resolvedBcp47Tag = candidateBcp47TagsFor(lang.value).firstOrNull(),
                    mlKitSupport = Pair(OnDeviceLanguageModelStatus.UNSUPPORTED, null),
                    extraStatuses = extraStatusesByCanonical[canonicalTag].orEmpty(),
                )
            }
        }

        val candidatesByCanonical = uniqueCanonicalList.associateWith { candidateBcp47TagsFor(it.value) }
        val osBcp47Tags = candidatesByCanonical.values.flatten().distinct()

        // ML Kit は地域なしタグ ("en" 等) や SODA 用エイリアス ("cmn-Hans-CN" 等) まで毎回 checkStatus() すると
        // AICore IPC が直列にブロックするため、ML Kit 対応言語の代表 BCP-47 タグのみを問い合わせる
        val mlKitCandidatesByCanonical = uniqueCanonicalList.associateWith { canonicalLang ->
            val catalogEntry = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.findByCode(canonicalLang.value)
            if (catalogEntry != null && !catalogEntry.mlKitSupported) {
                emptyList()
            } else {
                val primaryTag = toBcp47LocaleTag(canonicalLang.value)
                val langKey = canonicalLang.value.trim().replace('_', '-').lowercase(Locale.ROOT)
                val resolvedTag = resolvedBcp47TagByLangKey[langKey]
                if (resolvedTag != null && !resolvedTag.equals(primaryTag, ignoreCase = true)) {
                    listOf(resolvedTag, primaryTag)
                } else {
                    listOf(primaryTag)
                }
            }
        }
        val mlKitBcp47Tags = mlKitCandidatesByCanonical.values.flatten().distinct()

        var osSupport: Map<String, RecognitionModelSupport> = emptyMap()
        var mlKitSupportRaw: Map<String, Pair<RecognitionModelSupport, com.miuraken.chunkflow.core.model.MlKitRecognitionMode?>> = emptyMap()

        if (osAvailable && osBcp47Tags.isNotEmpty()) {
            osSupport = runCatching { recognitionModelGateway.checkSupport(osBcp47Tags) }.getOrDefault(emptyMap())
        }
        if (mlKitAvailable && mlKitBcp47Tags.isNotEmpty()) {
            mlKitSupportRaw = runCatching { mlKitGateway?.checkMlKitSupport(mlKitBcp47Tags).orEmpty() }.getOrDefault(emptyMap())
        }

        val currentIssues = modelIssuesState.value
        val canonicalInfoByCode = LinkedHashMap<String, OnDeviceLanguageModelInfo>(uniqueCanonicalList.size)
        for ((normCode, canonicalLang) in canonicalLanguages) {
            val candidates = candidatesByCanonical[canonicalLang].orEmpty()
            val mlKitCandidates = mlKitCandidatesByCanonical[canonicalLang].orEmpty()

            val installedTag = candidates.firstOrNull { osSupport[it] == RecognitionModelSupport.INSTALLED }
            val pendingTag = candidates.firstOrNull { osSupport[it] == RecognitionModelSupport.PENDING }
            val supportedTag = candidates.firstOrNull {
                osSupport[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }
            val issue = currentIssues[canonicalLang]

            val osStatusAndTag = when {
                installedTag != null -> {
                    rememberResolvedBcp47Tag(canonicalLang.value, installedTag)
                    clearModelIssue(canonicalLang)
                    OnDeviceLanguageModelStatus.INSTALLED to installedTag
                }

                pendingTag != null ||
                    (
                        issue == OnDeviceRecognitionModelState.DOWNLOADING &&
                            sherpaModelManager?.getLanguageModelStatus(canonicalLang) != OnDeviceLanguageModelStatus.DOWNLOADING
                        )
                -> {
                    val tag = pendingTag ?: supportedTag ?: candidates.firstOrNull()
                    if (tag != null) rememberResolvedBcp47Tag(canonicalLang.value, tag)
                    OnDeviceLanguageModelStatus.DOWNLOADING to tag
                }

                supportedTag != null -> {
                    rememberResolvedBcp47Tag(canonicalLang.value, supportedTag)
                    val status = if (issue == OnDeviceRecognitionModelState.DOWNLOAD_FAILED) {
                        OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    } else {
                        OnDeviceLanguageModelStatus.NOT_INSTALLED
                    }
                    status to supportedTag
                }

                osSupport.isNotEmpty() || !osAvailable -> {
                    if (isSherpaInstalledFor(canonicalLang)) {
                        clearModelIssue(canonicalLang)
                    }
                    OnDeviceLanguageModelStatus.UNSUPPORTED to candidates.firstOrNull()
                }

                else -> {
                    val status = when (issue) {
                        OnDeviceRecognitionModelState.NOT_INSTALLED -> OnDeviceLanguageModelStatus.NOT_INSTALLED
                        OnDeviceRecognitionModelState.DOWNLOADING -> OnDeviceLanguageModelStatus.DOWNLOADING
                        OnDeviceRecognitionModelState.DOWNLOAD_FAILED -> OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                        OnDeviceRecognitionModelState.UNSUPPORTED -> OnDeviceLanguageModelStatus.UNSUPPORTED
                        null -> OnDeviceLanguageModelStatus.CHECKING
                    }
                    status to candidates.firstOrNull()
                }
            }

            val mlKitInstalled = mlKitCandidates.firstOrNull { mlKitSupportRaw[it]?.first == RecognitionModelSupport.INSTALLED }
            val mlKitPending = mlKitCandidates.firstOrNull { mlKitSupportRaw[it]?.first == RecognitionModelSupport.PENDING }
            val mlKitSupported = mlKitCandidates.firstOrNull {
                mlKitSupportRaw[it]?.first == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }

            val mlKitResolvedTag = mlKitInstalled ?: mlKitPending ?: mlKitSupported
            val mlKitMode = mlKitResolvedTag?.let { mlKitSupportRaw[it]?.second }
            val catalogEntry = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.findByCode(canonicalLang.value)
            val mlKitStatus = when {
                mlKitInstalled != null -> OnDeviceLanguageModelStatus.INSTALLED
                mlKitPending != null -> OnDeviceLanguageModelStatus.DOWNLOADING
                mlKitSupported != null -> OnDeviceLanguageModelStatus.NOT_INSTALLED
                mlKitAvailable && catalogEntry?.mlKitSupported == true && osStatusAndTag.first != OnDeviceLanguageModelStatus.UNSUPPORTED -> osStatusAndTag.first
                else -> OnDeviceLanguageModelStatus.UNSUPPORTED
            }

            canonicalInfoByCode[normCode] = withSherpaAndMlKitModelFields(
                language = canonicalLang,
                osStatus = osStatusAndTag.first,
                resolvedBcp47Tag = osStatusAndTag.second,
                mlKitSupport = Pair(mlKitStatus, mlKitMode),
                extraStatuses = extraStatusesByCanonical[canonicalLang].orEmpty(),
            )
        }

        return languages.map { requestedLang ->
            val normCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(requestedLang.value)
                .ifBlank { requestedLang.value.lowercase(Locale.ROOT) }
            val baseInfo = canonicalInfoByCode[normCode]
            if (baseInfo != null) {
                if (baseInfo.language == requestedLang) baseInfo else baseInfo.copy(language = requestedLang)
            } else {
                OnDeviceLanguageModelInfo(language = requestedLang)
            }
        }
    }

    private suspend fun buildExtraEngineStatusesByLanguage(
        languages: List<LanguageTag>,
    ): Map<LanguageTag, Map<com.miuraken.chunkflow.core.model.SttEngine, OnDeviceLanguageModelStatus>> {
        val manager = sherpaModelManager
        val cloud = cloudSpeechTranscriberInstance
        val openAiConfigured = cloud?.isEngineConfigured(com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API) == true
        val groqConfigured = cloud?.isEngineConfigured(com.miuraken.chunkflow.core.model.SttEngine.GROQ_API) == true
        val geminiConfigured = cloud?.isEngineConfigured(com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API) == true

        return languages.associateWith { lang ->
            val map = LinkedHashMap<com.miuraken.chunkflow.core.model.SttEngine, OnDeviceLanguageModelStatus>()
            val localEngines = listOf(
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE,
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE,
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL,
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE,
            )
            for (engine in localEngines) {
                val model = engine.downloadableModel ?: continue
                map[engine] = manager?.getLanguageModelStatus(lang, model) ?: OnDeviceLanguageModelStatus.UNSUPPORTED
            }
            map[com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API] =
                if (openAiConfigured) OnDeviceLanguageModelStatus.INSTALLED else OnDeviceLanguageModelStatus.NOT_INSTALLED
            map[com.miuraken.chunkflow.core.model.SttEngine.GROQ_API] =
                if (groqConfigured) OnDeviceLanguageModelStatus.INSTALLED else OnDeviceLanguageModelStatus.NOT_INSTALLED
            map[com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API] =
                if (geminiConfigured) OnDeviceLanguageModelStatus.INSTALLED else OnDeviceLanguageModelStatus.NOT_INSTALLED
            map
        }
    }

    private fun withSherpaAndMlKitModelFields(
        language: LanguageTag,
        osStatus: OnDeviceLanguageModelStatus,
        resolvedBcp47Tag: String?,
        mlKitSupport: Pair<OnDeviceLanguageModelStatus, com.miuraken.chunkflow.core.model.MlKitRecognitionMode?>?,
        extraStatuses: Map<com.miuraken.chunkflow.core.model.SttEngine, OnDeviceLanguageModelStatus> = emptyMap(),
    ): OnDeviceLanguageModelInfo {
        val manager = sherpaModelManager
        val mlKitStatusVal = mlKitSupport?.first ?: OnDeviceLanguageModelStatus.UNSUPPORTED
        val mlKitModeVal = mlKitSupport?.second
        val rawInfo = if (manager == null) {
            OnDeviceLanguageModelInfo(
                language = language,
                status = osStatus,
                resolvedBcp47Tag = resolvedBcp47Tag,
                sherpaModelStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                sherpaModelId = null,
                sherpaModelSizeBytes = 0L,
                canDeleteSherpaModel = false,
                mlKitStatus = mlKitStatusVal,
                mlKitMode = mlKitModeVal,
                extraEngineStatuses = extraStatuses,
            )
        } else {
            val sherpaStatus = manager.getLanguageModelStatus(language)
            val installed = sherpaStatus == OnDeviceLanguageModelStatus.INSTALLED
            OnDeviceLanguageModelInfo(
                language = language,
                status = osStatus,
                resolvedBcp47Tag = resolvedBcp47Tag,
                sherpaModelStatus = sherpaStatus,
                sherpaModelId = SherpaOnnxModelManager.MODEL_ID,
                sherpaModelSizeBytes = manager.totalModelSizeBytes,
                canDeleteSherpaModel = installed,
                mlKitStatus = mlKitStatusVal,
                mlKitMode = mlKitModeVal,
                extraEngineStatuses = extraStatuses,
            )
        }
        return rawInfo.enrichedWithCatalog(language)
    }

    private fun candidateBcp47TagsFor(
        rawLang: String?,
        fallbackBcp47Tag: String = toBcp47LocaleTag(rawLang ?: ""),
    ): List<String> {
        val source = rawLang?.takeIf { it.isNotBlank() } ?: fallbackBcp47Tag
        val langKey = source.trim().replace('_', '-').lowercase(Locale.ROOT)
        val resolved = resolvedBcp47TagByLangKey[langKey]
        val candidates = toBcp47CandidateLocaleTags(source)
        return if (resolved != null) {
            (listOf(resolved) + candidates).distinct()
        } else {
            candidates
        }
    }

    private fun rememberResolvedBcp47Tag(rawLang: String?, resolvedTag: String) {
        val source = rawLang?.takeIf { it.isNotBlank() } ?: return
        val langKey = source.trim().replace('_', '-').lowercase(Locale.ROOT)
        resolvedBcp47TagByLangKey[langKey] = resolvedTag
    }

    private suspend fun downloadMlKitModel(language: LanguageTag, bcp47Tag: String): Boolean {
        val gateway = mlKitGateway ?: return false
        logInfo("mlKitModelDownload request lang=$bcp47Tag")
        setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
        val ok = try {
            gateway.downloadModel(bcp47Tag) { event ->
                onRecognitionModelDownloadEvent(language, bcp47Tag, event)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
        if (ok) {
            clearModelIssue(language)
            clearAllSttCache()
        } else {
            setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
        return ok
    }

    private fun startRecognitionModelDownload(language: LanguageTag, bcp47Tag: String): Boolean {
        logInfo("modelDownload request lang=$bcp47Tag")
        setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
        val requested = runCatching {
            recognitionModelGateway.requestDownload(bcp47Tag) { event ->
                onRecognitionModelDownloadEvent(language, bcp47Tag, event)
            }
        }.getOrDefault(false)
        if (!requested) {
            setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
        return requested
    }

    private fun onRecognitionModelDownloadEvent(
        language: LanguageTag,
        bcp47Tag: String,
        event: RecognitionModelDownloadEvent,
    ) {
        if (event != RecognitionModelDownloadEvent.PROGRESS) {
            logInfo("modelDownload lang=$bcp47Tag event=$event")
        }
        when (event) {
            RecognitionModelDownloadEvent.PROGRESS,
            RecognitionModelDownloadEvent.SCHEDULED,
            -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)

            RecognitionModelDownloadEvent.SUCCESS -> clearModelIssue(language)

            RecognitionModelDownloadEvent.FAILED -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
    }

    /**
     * 1 回の認識試行の結果から、[language] の言語モデルの状態を更新する。
     *
     * 言語モデルの未ダウンロード（`ERROR_LANGUAGE_UNAVAILABLE`）を初めて検出した言語は、ダウンロードを自動で要求する。
     */
    private fun noteRecognitionAttemptOutcome(
        language: LanguageTag?,
        bcp47Tag: String,
        attempt: SpeechRecognitionAttemptResult?,
        selectedEngine: OnDeviceTranscriptionEngine = OnDeviceTranscriptionEngine.AUTO,
    ) {
        if (language == null || attempt == null) return
        when {
            attempt.text.isNotBlank() -> clearModelIssue(language)

            attempt.errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                if (selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER &&
                    isSherpaSupportedFor(language)
                ) {
                    val sherpaStatus = sherpaModelManager?.getLanguageModelStatus(language)
                    when (sherpaStatus) {
                        OnDeviceLanguageModelStatus.INSTALLED -> clearModelIssue(language)
                        OnDeviceLanguageModelStatus.DOWNLOADING -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                        OnDeviceLanguageModelStatus.DOWNLOAD_FAILED -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
                        else -> setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                    }
                } else {
                    setModelIssue(language, OnDeviceRecognitionModelState.UNSUPPORTED)
                }
            }

            attempt.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                val current = modelIssuesState.value[language]
                if (current != OnDeviceRecognitionModelState.DOWNLOADING &&
                    current != OnDeviceRecognitionModelState.DOWNLOAD_FAILED
                ) {
                    setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                }
                if (selectedEngine != OnDeviceTranscriptionEngine.SHERPA_ONNX &&
                    onDeviceAvailabilityChecker() &&
                    autoDownloadRequestedTags.add(bcp47Tag.lowercase(Locale.ROOT))
                ) {
                    startRecognitionModelDownload(language, bcp47Tag)
                }
            }
        }
    }

    private fun setModelIssue(language: LanguageTag, state: OnDeviceRecognitionModelState) {
        modelIssuesState.update { current ->
            if (current[language] == state) current else current + (language to state)
        }
    }

    private fun clearModelIssue(language: LanguageTag) {
        modelIssuesState.update { current ->
            if (language in current) current - language else current
        }
    }

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = false,
        highPriority = false,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
    )

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
        precomputedLanguage = null,
        precomputedLanguageConfidence = null,
    )

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
        precomputedLanguage = precomputedLanguage,
        precomputedLanguageConfidence = precomputedLanguageConfidence,
        compareBothLanguages = false,
    )

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
        compareBothLanguages: Boolean,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = forceRefresh,
        highPriority = highPriority,
        itemSttOverride = itemSttOverride,
        engineByLanguage = engineByLanguage,
        precomputedLanguage = precomputedLanguage,
        precomputedLanguageConfidence = precomputedLanguageConfidence,
        compareBothLanguages = compareBothLanguages,
        precedingContextByLanguage = emptyMap(),
    )

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
        precomputedLanguage: LanguageTag?,
        precomputedLanguageConfidence: Float?,
        compareBothLanguages: Boolean,
        precedingContextByLanguage: Map<LanguageTag, String>,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
        }

        val startedAtEpochMs = System.currentTimeMillis()
        val currentJob = currentCoroutineContext()[Job]
        val jobHandle = currentJob?.let {
            ActiveJobHandle(
                job = it,
                highPriority = highPriority,
                startedAtEpochMs = startedAtEpochMs,
            )
        }
        if (jobHandle != null) {
            synchronized(activeJobsLock) {
                activeJobHandles.add(jobHandle)
            }
        }

        val cacheScope = ChunkAudioCacheScope(
            audioPath = audioFile.absolutePath,
            lastModified = audioFile.lastModified(),
            startMs = startMs,
            endMs = endMs,
        )
        val targetLang = languagePair?.target
        val baseLang = languagePair?.base?.takeIf {
            targetLang == null || !it.value.equals(targetLang.value, ignoreCase = true)
        }
        try {
            if (forceRefresh) {
                evictCachedSttResults(cacheScope)
            } else if (targetLang == null || baseLang == null) {
                val singleLang = targetLang ?: baseLang
                val cached = getCachedSttResult(cacheScope, singleLang)
                if (cached != null) {
                    logInfo("singleLang cacheHit startMs=$startMs endMs=$endMs lang=${singleLang?.value ?: "default"}")
                    return cached
                }
            } else {
                getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                    logInfo("bilingual bothCacheHit startMs=$startMs endMs=$endMs winner=${cachedBoth.language?.value}")
                    return cachedBoth
                }
            }

            updateActiveStep(
                step = TranscriptionStep.EXTRACTING_PCM,
                detail = "${startMs}ms..${endMs}ms",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = highPriority,
            )
            val extractStartRealtimeMs = SystemClock.elapsedRealtime()
            val pcmBytes = try {
                ChunkPcmExtractor.extractChunkPcm16LeBytes(
                    decoder = audioStreamDecoder,
                    audioFile = audioFile,
                    startMs = startMs,
                    endMs = endMs,
                    sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
                )
            } catch (ce: CancellationException) {
                appendDiagnostic(
                    stage = "EXTRACT_PCM",
                    message = "音声切り出しを中断 ($startMs..${endMs}ms)",
                    isWarningOrError = false,
                )
                throw ce
            } catch (e: Exception) {
                appendDiagnostic(
                    stage = "EXTRACT_PCM",
                    message = "音声切り出しエラー ($startMs..${endMs}ms, ${e.javaClass.simpleName})",
                    isWarningOrError = true,
                )
                null
            }
            if (pcmBytes == null) {
                appendDiagnostic(
                    stage = "EXTRACT_PCM",
                    message = "音声切り出し結果が空でした ($startMs..${endMs}ms)",
                    isWarningOrError = true,
                )
                return null
            }
            val extractElapsedMs = SystemClock.elapsedRealtime() - extractStartRealtimeMs
            if (extractElapsedMs >= 1_500L) {
                appendDiagnostic(
                    stage = "EXTRACT_PCM",
                    message = "音声切り出し完了 ($startMs..${endMs}ms, ${extractElapsedMs}ms)",
                    isWarningOrError = false,
                )
            }

            return transcribePcm16LeInternal(
                pcm16LeBytes = pcmBytes,
                sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
                languagePair = languagePair,
                cacheScope = cacheScope,
                forceRefresh = forceRefresh,
                highPriority = highPriority,
                startedAtEpochMs = startedAtEpochMs,
                itemSttOverride = itemSttOverride,
                engineByLanguage = engineByLanguage,
                precomputedLanguage = precomputedLanguage,
                precomputedLanguageConfidence = precomputedLanguageConfidence,
                compareBothLanguages = compareBothLanguages,
                precedingContextByLanguage = precedingContextByLanguage,
            )
        } finally {
            if (jobHandle != null) {
                synchronized(activeJobsLock) {
                    activeJobHandles.remove(jobHandle)
                }
            }
            clearActiveStepIfMatches(startedAtEpochMs)
        }
    }

    override suspend fun transcribeChunkTrial(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        engineOverride: OnDeviceTranscriptionEngine?,
        audioSpeed: Float,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
        }
        val rawPcmBytes = try {
            ChunkPcmExtractor.extractChunkPcm16LeBytes(
                decoder = audioStreamDecoder,
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return null

        val scaledPcmBytes = timeStretchPcm16LeBytes(
            pcm16LeBytes = rawPcmBytes,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            speed = audioSpeed,
        )
        val trialScope = ChunkAudioCacheScope(
            audioPath = "trial:${SystemClock.elapsedRealtimeNanos()}:${audioFile.absolutePath}",
            lastModified = audioFile.lastModified(),
            startMs = startMs,
            endMs = endMs,
        )
        return transcribePcm16LeInternal(
            pcm16LeBytes = scaledPcmBytes,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            languagePair = languagePair,
            cacheScope = trialScope,
            forceRefresh = true,
            highPriority = true,
            engineOverride = engineOverride,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
            engineByLanguage = emptyMap(),
            compareBothLanguages = languagePair?.base != null,
        )
    }

    override suspend fun detectChunkLanguage(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair,
    ): OnDeviceChunkTranscriptionResult? = detectChunkLanguageObserved(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        index = null,
    )

    override suspend fun detectChunkLanguageFromPcm(
        audioScopeKey: String,
        pcm16kMono: ShortArray,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair,
        audioFile: File?,
    ): OnDeviceChunkTranscriptionResult? {
        if (startMs < 0L || endMs <= startMs || pcm16kMono.isEmpty()) {
            return null
        }
        val targetLang = languagePair.target
        val baseLang = distinctBaseLanguage(languagePair)
            ?: return OnDeviceChunkTranscriptionResult(
                text = "",
                language = targetLang,
                confidence = 1.0f,
                engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
            )
        if (languageDetector == null && languageAnalyzer == null) return null
        val startedAtEpochMs = System.currentTimeMillis()
        try {
            updateActiveStep(
                step = TranscriptionStep.DETECTING_LANGUAGE,
                detail = "${targetLang.value} / ${baseLang.value}",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = false,
            )
            val analysis = analyzeWhisperLidSafely(pcm16kMono, startMs, endMs, targetLang, baseLang) ?: return null
            val adaptStartNanos = System.nanoTime()
            return try {
                adaptLanguageOnlyResult(
                    analysis = analysis,
                    audioScopeKey = audioScopeKey,
                    startMs = startMs,
                    endMs = endMs,
                    targetLang = targetLang,
                    baseLang = baseLang,
                )
            } finally {
                languageOnlyTimings.adaptNanos.addAndGet(System.nanoTime() - adaptStartNanos)
            }
        } finally {
            clearActiveStepIfMatches(startedAtEpochMs)
        }
    }

    override suspend fun detectChunkLanguagesFromPcmStream(
        audioScopeKey: String,
        requests: ReceiveChannel<PcmChunkLanguageRequest>,
        languagePair: LanguagePair,
        parallelism: Int,
        computeDispatcher: CoroutineDispatcher,
        audioFile: File?,
        onResult: suspend (request: PcmChunkLanguageRequest, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        val targetLang = languagePair.target
        val baseLang = distinctBaseLanguage(languagePair)
        if (baseLang == null || (languageDetector == null && languageAnalyzer == null)) {
            for (req in requests) {
                onResult(req, null)
            }
            return
        }
        val statsBefore = languageDetectionStats?.let { provider -> runCatching { provider() }.getOrNull() }
        val requestedWorkers = parallelism.coerceIn(1, OnDeviceChunkTranscriber.MAX_LANGUAGE_DETECTION_PARALLELISM)
        val workers = if (requestedWorkers > 1 && isDeviceConstrainedForParallelInference()) {
            appendDiagnostic(
                stage = "LID_ONLY",
                message = PARALLEL_LID_CONSTRAINED_MESSAGE,
            )
            1
        } else {
            requestedWorkers
        }
        val inferenceDispatcher = computeDispatcher.limitedParallelism(workers)
        val startedAtEpochMs = System.currentTimeMillis()
        updateActiveStep(
            step = TranscriptionStep.DETECTING_LANGUAGE,
            detail = "${targetLang.value} / ${baseLang.value}（$workers 並列）",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = false,
        )
        try {
            coroutineScope {
                val inFlight = Channel<Pair<PcmChunkLanguageRequest, Deferred<WhisperLidAnalysis?>?>>(
                    capacity = (workers * 2).coerceAtLeast(2),
                )
                launch {
                    try {
                        for (req in requests) {
                            val validRange = req.startMs >= 0L && req.endMs > req.startMs && req.pcm16kMono.isNotEmpty()
                            if (validRange) {
                                languageOnlyPcmObserver?.invoke(req.chunkId.toInt(), req.pcm16kMono)
                            }
                            val deferred = if (validRange) {
                                async(inferenceDispatcher) {
                                    analyzeWhisperLidSafely(
                                        shorts = req.pcm16kMono,
                                        startMs = req.startMs,
                                        endMs = req.endMs,
                                        targetLang = targetLang,
                                        baseLang = baseLang,
                                    )
                                }
                            } else {
                                null
                            }
                            inFlight.send(req to deferred)
                        }
                    } finally {
                        inFlight.close()
                    }
                }

                for ((req, deferred) in inFlight) {
                    val awaitStartNanos = System.nanoTime()
                    val analysis = deferred?.await()
                    languageOnlyTimings.awaitNanos.addAndGet(System.nanoTime() - awaitStartNanos)
                    val adaptStartNanos = System.nanoTime()
                    val result = analysis?.let {
                        adaptLanguageOnlyResult(
                            analysis = it,
                            audioScopeKey = audioScopeKey,
                            startMs = req.startMs,
                            endMs = req.endMs,
                            targetLang = targetLang,
                            baseLang = baseLang,
                        )
                    }
                    onResult(req, result)
                    languageOnlyTimings.adaptNanos.addAndGet(System.nanoTime() - adaptStartNanos)
                }
            }
        } finally {
            clearActiveStepIfMatches(startedAtEpochMs)
            if (statsBefore != null) appendLanguageDetectionEngineDiagnostic(statsBefore)
        }
    }

    /**
     * [detectChunkLanguage] の本体。[index]（一括言語判定のチャンクの番号）があれば、切り出した PCM を [languageOnlyPcmObserver] に渡す。
     */
    private suspend fun detectChunkLanguageObserved(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair,
        index: Int?,
    ): OnDeviceChunkTranscriptionResult? {
        if (startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
        }
        val targetLang = languagePair.target
        val baseLang = distinctBaseLanguage(languagePair)
            ?: return OnDeviceChunkTranscriptionResult(
                text = "",
                language = targetLang,
                confidence = 1.0f,
                engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
            )
        if (languageDetector == null && languageAnalyzer == null) return null
        val startedAtEpochMs = System.currentTimeMillis()
        try {
            updateActiveStep(
                step = TranscriptionStep.EXTRACTING_PCM,
                detail = "${startMs}ms..${languageOnlyExtractEndMs(startMs, endMs)}ms",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = false,
            )
            val shorts = extractLanguageOnlyPcm(audioFile, startMs, endMs) ?: return null
            if (index != null) languageOnlyPcmObserver?.invoke(index, shorts)

            updateActiveStep(
                step = TranscriptionStep.DETECTING_LANGUAGE,
                detail = "${targetLang.value} / ${baseLang.value}",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = false,
            )
            val analysis = analyzeWhisperLidSafely(shorts, startMs, endMs, targetLang, baseLang) ?: return null
            val adaptStartNanos = System.nanoTime()
            return try {
                adaptLanguageOnlyResult(
                    analysis = analysis,
                    audioScopeKey = audioFile.absolutePath,
                    startMs = startMs,
                    endMs = endMs,
                    targetLang = targetLang,
                    baseLang = baseLang,
                )
            } finally {
                languageOnlyTimings.adaptNanos.addAndGet(System.nanoTime() - adaptStartNanos)
            }
        } finally {
            clearActiveStepIfMatches(startedAtEpochMs)
        }
    }

    /**
     * 複数チャンクの言語判定（[detectChunkLanguage] と同じ判定）を、Whisper Tiny の推論を並列にして行う。
     *
     * - 音声の切り出しの方式は [languageOnlyExtractionMethod]（[LidExtractionMethod]）。今の方式では、切り出しはチャンクの順に
     *   呼び出し側で 1 件ずつ行う。並列の切り出しと順に流す切り出しは [detectChunkLanguagesPipelined]。
     * - 推論は [computeDispatcher] 上で最大 `workers` 件まで同時に進める。
     * - ファイル内の補正（アンカーの登録を含む）と [onResult] はチャンクの順に行うため、結果は 1 件ずつ処理した場合と同じになる。
     *
     * 2 言語でない、判定器がない場合は 1 件ずつ処理する。今の方式で、同時に 1 件しか推論しない（端末が熱い・省電力モードを含む）
     * 場合も 1 件ずつ処理する。終わりに、推論エンジン（onnxruntime / kotlin）と推論時間（[describeLanguageDetectionEngine]）、
     * 切り出しの方式と各段階の時間（[describeLanguageOnlyExtraction]）を診断ログに残す。
     */
    override suspend fun detectChunkLanguages(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        languagePair: LanguagePair,
        parallelism: Int,
        computeDispatcher: CoroutineDispatcher,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        val statsBefore = languageDetectionStats?.let { provider -> runCatching { provider() }.getOrNull() }
        val timingsBefore = languageOnlyTimings.snapshot()
        val startedNanos = System.nanoTime()
        var method: LidExtractionMethod? = null
        try {
            detectChunkLanguagesInChunkOrder(audioFile, chunks, languagePair, parallelism, computeDispatcher, onResult) { chosen ->
                method = chosen
                lastLanguageOnlyExtractionMethod = chosen
            }
        } finally {
            if (statsBefore != null) appendLanguageDetectionEngineDiagnostic(statsBefore)
            method?.let { chosen ->
                appendLanguageOnlyExtractionDiagnostic(chosen, languageOnlyTimings.snapshot().since(timingsBefore), System.nanoTime() - startedNanos)
            }
        }
    }

    /**
     * 一括言語判定の推論エンジンと推論時間（[before] からの増分）を診断ログに残す。この間に推論しなかった場合は何もしない。
     *
     * 純 Kotlin で推論し、ONNX Runtime を使えない理由がある場合は警告にする。
     */
    private fun appendLanguageDetectionEngineDiagnostic(before: LidInferenceStats) {
        val provider = languageDetectionStats ?: return
        val after = runCatching { provider() }.getOrNull() ?: return
        val delta = after.since(before)
        if (delta.onnxRuns + delta.kotlinRuns <= 0L) return
        appendDiagnostic(
            stage = "LID_ONLY",
            message = describeLanguageDetectionEngine(delta),
            isWarningOrError = delta.kotlinRuns > 0L && delta.onnxUnavailableReason != null,
        )
    }

    /** 一括言語判定の切り出しの方式と各段階の時間（[delta]）を診断ログに残す。切り出さなかった場合は何もしない。 */
    private fun appendLanguageOnlyExtractionDiagnostic(method: LidExtractionMethod, delta: LidExtractionTimingsSnapshot, elapsedNanos: Long) {
        if (delta.extractions <= 0L && delta.streamWindows <= 0L) return
        appendDiagnostic(
            stage = "LID_ONLY",
            message = describeLanguageOnlyExtraction(method, delta, elapsedNanos),
        )
    }

    /**
     * [detectChunkLanguages] の本体。進め方（同時に推論する数と切り出しの方式）を決め、[onMethodChosen] に実際の方式を渡してから判定する。
     */
    private suspend fun detectChunkLanguagesInChunkOrder(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        languagePair: LanguagePair,
        parallelism: Int,
        computeDispatcher: CoroutineDispatcher,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
        onMethodChosen: (LidExtractionMethod) -> Unit,
    ) {
        val targetLang = languagePair.target
        val baseLang = distinctBaseLanguage(languagePair)
        if (baseLang == null || chunks.size <= 1 || !audioFile.isFile || (languageDetector == null && languageAnalyzer == null)) {
            onMethodChosen(LidExtractionMethod.CURRENT)
            detectChunkLanguagesOneByOne(audioFile, chunks, languagePair, onResult)
            return
        }
        val requestedWorkers = parallelism.coerceIn(1, OnDeviceChunkTranscriber.MAX_LANGUAGE_DETECTION_PARALLELISM)
        val workers = if (requestedWorkers > 1 && isDeviceConstrainedForParallelInference()) {
            appendDiagnostic(
                stage = "LID_ONLY",
                message = PARALLEL_LID_CONSTRAINED_MESSAGE,
            )
            1
        } else {
            requestedWorkers
        }
        val decoder = audioStreamDecoder as? MediaCodecAudioStreamDecoder
        val method = resolveLanguageOnlyExtractionMethod(chunks, decoder)
        onMethodChosen(method)
        when {
            method != LidExtractionMethod.CURRENT && decoder != null -> detectChunkLanguagesPipelined(
                audioFile = audioFile,
                chunks = chunks,
                targetLang = targetLang,
                baseLang = baseLang,
                workers = workers,
                computeDispatcher = computeDispatcher,
                method = method,
                decoder = decoder,
                onResult = onResult,
            )

            workers <= 1 -> detectChunkLanguagesOneByOne(audioFile, chunks, languagePair, onResult)

            else -> detectChunkLanguagesWithCurrentExtraction(audioFile, chunks, targetLang, baseLang, workers, computeDispatcher, onResult)
        }
    }

    /**
     * 指定された切り出しの方式（[languageOnlyExtractionMethod]）を、このチャンクの並びで使えるかを見て、実際の方式を決める。
     *
     * - 速い方式はデコーダが [MediaCodecAudioStreamDecoder] のときだけ使う（それ以外は今の方式）。
     * - 順に流す切り出しは、チャンクが開始位置の順に並んでいて、1 つの MediaCodec で平均 2 窓以上を続けて切り出せるときだけ使う。
     *   まばらなとき（間が大きく空いて run が短い）や並んでいないときは、並列の切り出しにする。
     */
    private fun resolveLanguageOnlyExtractionMethod(
        chunks: List<ChunkTimeRange>,
        decoder: MediaCodecAudioStreamDecoder?,
    ): LidExtractionMethod {
        val requested = languageOnlyExtractionMethod
        if (requested == LidExtractionMethod.CURRENT || decoder == null) return LidExtractionMethod.CURRENT
        if (requested == LidExtractionMethod.PARALLEL) return LidExtractionMethod.PARALLEL
        val windows = streamWindowsFor(chunks)
        val sorted = windows.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
        if (!sorted) {
            appendDiagnostic(stage = "LID_ONLY", message = STREAMING_UNSORTED_MESSAGE)
            return LidExtractionMethod.PARALLEL
        }
        val runs = planStreamRuns(windows, MediaCodecAudioStreamDecoder.SEEK_PRE_ROLL_MS, streamingExtractionOptions)
        if (windows.size >= 2 && runs.size * 2 > windows.size) {
            appendDiagnostic(stage = "LID_ONLY", message = STREAMING_SPARSE_MESSAGE)
            return LidExtractionMethod.PARALLEL
        }
        return LidExtractionMethod.STREAMING
    }

    /** 順に流す切り出しで切り出す窓（区間が正しいチャンクだけ。窓の終わりはチャンクの先頭から最大 [LID_ONLY_MAX_EXTRACT_DURATION_MS]）。 */
    private fun streamWindowsFor(chunks: List<ChunkTimeRange>): List<StreamWindowRequest> = chunks.mapIndexedNotNull { index, chunk ->
        if (chunk.startMs >= 0L && chunk.endMs > chunk.startMs) {
            StreamWindowRequest(index = index, startMs = chunk.startMs, endMs = languageOnlyExtractEndMs(chunk.startMs, chunk.endMs))
        } else {
            null
        }
    }

    /** チャンクを 1 件ずつ判定する（[OnDeviceChunkTranscriber.detectChunkLanguages] の既定と同じ。切り出した PCM を観察できる）。 */
    private suspend fun detectChunkLanguagesOneByOne(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        languagePair: LanguagePair,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        for ((index, chunk) in chunks.withIndex()) {
            val result = try {
                detectChunkLanguageObserved(audioFile, chunk.startMs, chunk.endMs, languagePair, index)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            val deliverStartNanos = System.nanoTime()
            onResult(index, result)
            languageOnlyTimings.adaptNanos.addAndGet(System.nanoTime() - deliverStartNanos)
        }
    }

    /** 今の方式: 切り出しはチャンクの順に呼び出し側で 1 件ずつ行い、推論だけを並列にする。補正と [onResult] はチャンクの順に行う。 */
    private suspend fun detectChunkLanguagesWithCurrentExtraction(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        workers: Int,
        computeDispatcher: CoroutineDispatcher,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        val timings = languageOnlyTimings
        val audioScopeKey = audioFile.absolutePath
        val inferenceDispatcher = computeDispatcher.limitedParallelism(workers)
        val startedAtEpochMs = System.currentTimeMillis()
        updateActiveStep(
            step = TranscriptionStep.DETECTING_LANGUAGE,
            detail = "${targetLang.value} / ${baseLang.value}（$workers 並列）",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = false,
        )
        try {
            coroutineScope {
                // 切り出せなかったチャンクは推論せず、null を入れておく
                val pending = ArrayDeque<Deferred<WhisperLidAnalysis?>?>()
                var nextToStart = 0
                for ((index, chunk) in chunks.withIndex()) {
                    // 推論中と推論待ちを合わせて (workers + 1) 件まで用意する
                    while (nextToStart < chunks.size && pending.size <= workers) {
                        val nextIndex = nextToStart
                        val next = chunks[nextIndex]
                        val shorts = if (next.startMs >= 0L && next.endMs > next.startMs) {
                            extractLanguageOnlyPcm(audioFile, next.startMs, next.endMs)
                        } else {
                            null
                        }
                        if (shorts != null) languageOnlyPcmObserver?.invoke(nextIndex, shorts)
                        pending.addLast(
                            shorts?.let { pcm ->
                                async(inferenceDispatcher) {
                                    analyzeWhisperLidSafely(pcm, next.startMs, next.endMs, targetLang, baseLang)
                                }
                            },
                        )
                        nextToStart += 1
                    }
                    val awaitStartNanos = System.nanoTime()
                    val analysis = pending.removeFirst()?.await()
                    timings.awaitNanos.addAndGet(System.nanoTime() - awaitStartNanos)
                    val adaptStartNanos = System.nanoTime()
                    val result = analysis?.let {
                        adaptLanguageOnlyResult(
                            analysis = it,
                            audioScopeKey = audioScopeKey,
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                            targetLang = targetLang,
                            baseLang = baseLang,
                        )
                    }
                    onResult(index, result)
                    timings.adaptNanos.addAndGet(System.nanoTime() - adaptStartNanos)
                }
            }
        } finally {
            clearActiveStepIfMatches(startedAtEpochMs)
        }
    }

    /** 速い方式で、ワーカーに渡す 1 件（[detectChunkLanguagesPipelined]）。 */
    private sealed interface LanguageOnlyWorkItem {
        /** チャンクの番号。 */
        val index: Int

        /**
         * ワーカーが切り出してから推論する。
         *
         * @property viaLane 並列の切り出しのレーンで切り出す（`false` なら今の方式。順に流す切り出しで切り出せなかった窓）。
         */
        class Extract(override val index: Int, val viaLane: Boolean) : LanguageOnlyWorkItem

        /** 切り出し済みの PCM（順に流す切り出し。切り出せなかった場合は `null`）を推論する。 */
        class Analyze(override val index: Int, val shorts: ShortArray?) : LanguageOnlyWorkItem
    }

    /** 並列の切り出しのワーカー 1 つが持つレーン。[usable] が false になったら、以後は今の方式（共有の抽出器）で切り出す。 */
    private class LaneState(val lane: RangeDecodeLane) {
        var usable: Boolean = true
    }

    /**
     * 速い方式（[LidExtractionMethod.PARALLEL] / [LidExtractionMethod.STREAMING]）で一括言語判定を行う。
     *
     * - 並列の切り出し: `workers` 個のワーカーが、それぞれ独立した抽出器（[MediaCodecAudioStreamDecoder.openRangeDecodeLane]）で
     *   チャンクを切り出してから推論する。MediaCodec はチャンクごとに作るので、切り出す PCM は今の方式と同じ。
     * - 順に流す切り出し: 呼び出し側のコルーチンで [MediaCodecAudioStreamDecoder.streamLanguageWindows] が窓を切り出し、
     *   上限付きの待ち行列（[StreamingExtractionOptions.queueCapacity]）でワーカーに渡す。ワーカーは推論だけを行う。
     * - どちらも、速い方式で切り出せなかったチャンクは今の方式で切り出し直す。
     * - 結果はチャンクの順に受け取り、補正と [onResult] をチャンクの順に行う。終わったら（キャンセル・エラーでも）レーンを閉じる。
     */
    @Suppress("LongParameterList")
    private suspend fun detectChunkLanguagesPipelined(
        audioFile: File,
        chunks: List<ChunkTimeRange>,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        workers: Int,
        computeDispatcher: CoroutineDispatcher,
        method: LidExtractionMethod,
        decoder: MediaCodecAudioStreamDecoder,
        onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
    ) {
        val timings = languageOnlyTimings
        val audioScopeKey = audioFile.absolutePath
        val inferenceDispatcher = computeDispatcher.limitedParallelism(workers)
        val results = arrayOfNulls<CompletableDeferred<WhisperLidAnalysis?>>(chunks.size)
        val validIndices = ArrayList<Int>(chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            val deferred = CompletableDeferred<WhisperLidAnalysis?>()
            results[index] = deferred
            if (chunk.startMs >= 0L && chunk.endMs > chunk.startMs) validIndices += index else deferred.complete(null)
        }
        val startedAtEpochMs = System.currentTimeMillis()
        updateActiveStep(
            step = TranscriptionStep.DETECTING_LANGUAGE,
            detail = "${targetLang.value} / ${baseLang.value}（$workers 並列）",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = false,
        )
        try {
            coroutineScope {
                val capacity = if (method == LidExtractionMethod.STREAMING) streamingExtractionOptions.queueCapacity else workers
                val queue = Channel<LanguageOnlyWorkItem>(capacity = capacity)
                launch {
                    try {
                        if (method == LidExtractionMethod.STREAMING) {
                            decoder.streamLanguageWindows(
                                file = audioFile,
                                windows = streamWindowsFor(chunks),
                                outputSampleRate = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
                                options = streamingExtractionOptions,
                                timings = timings,
                            ) { result ->
                                val item = when (result) {
                                    is StreamWindowResult.Pcm -> LanguageOnlyWorkItem.Analyze(result.index, result.shorts)
                                    is StreamWindowResult.Fallback -> LanguageOnlyWorkItem.Extract(result.index, viaLane = false)
                                }
                                queue.send(item)
                            }
                        } else {
                            for (index in validIndices) queue.send(LanguageOnlyWorkItem.Extract(index, viaLane = true))
                        }
                    } finally {
                        queue.close()
                    }
                }
                repeat(workers) {
                    launch(inferenceDispatcher) {
                        val laneState = if (method == LidExtractionMethod.PARALLEL) LaneState(decoder.openRangeDecodeLane()) else null
                        try {
                            for (item in queue) {
                                val chunk = chunks[item.index]
                                val shorts = when (item) {
                                    is LanguageOnlyWorkItem.Analyze -> item.shorts

                                    is LanguageOnlyWorkItem.Extract -> if (item.viaLane && laneState != null) {
                                        extractWithLane(audioFile, chunk, laneState)
                                    } else {
                                        extractLanguageOnlyPcm(audioFile, chunk.startMs, chunk.endMs)
                                    }
                                }
                                if (shorts != null) languageOnlyPcmObserver?.invoke(item.index, shorts)
                                val analysis = shorts?.let { pcm ->
                                    analyzeWhisperLidSafely(pcm, chunk.startMs, chunk.endMs, targetLang, baseLang)
                                }
                                results[item.index]?.complete(analysis)
                            }
                        } finally {
                            laneState?.lane?.close()
                        }
                    }
                }
                for ((index, chunk) in chunks.withIndex()) {
                    val awaitStartNanos = System.nanoTime()
                    val analysis = results[index]?.await()
                    results[index] = null
                    timings.awaitNanos.addAndGet(System.nanoTime() - awaitStartNanos)
                    val adaptStartNanos = System.nanoTime()
                    val result = analysis?.let {
                        adaptLanguageOnlyResult(
                            analysis = it,
                            audioScopeKey = audioScopeKey,
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                            targetLang = targetLang,
                            baseLang = baseLang,
                        )
                    }
                    onResult(index, result)
                    timings.adaptNanos.addAndGet(System.nanoTime() - adaptStartNanos)
                }
            }
        } finally {
            clearActiveStepIfMatches(startedAtEpochMs)
        }
    }

    /**
     * 並列の切り出しのワーカーが 1 チャンクを切り出す。
     *
     * レーンで切り出せなければ、今の方式（共有の抽出器）で切り出し直す。今の方式では切り出せた場合は、レーンに問題がある
     * （MediaCodec を作れないなどの資源不足を含む）とみなして、以後このレーンは使わない。
     */
    private suspend fun extractWithLane(audioFile: File, chunk: ChunkTimeRange, state: LaneState): ShortArray? {
        if (state.usable) {
            extractLanguageOnlyPcm(audioFile, chunk.startMs, chunk.endMs, lane = state.lane)?.let { return it }
        }
        languageOnlyTimings.fallbacks.incrementAndGet()
        val shorts = extractLanguageOnlyPcm(audioFile, chunk.startMs, chunk.endMs)
        if (shorts != null && state.usable) {
            state.usable = false
            state.lane.close()
            appendDiagnostic(
                stage = "LID_ONLY",
                message = "並列の切り出しに失敗したため、以後このワーカーは今の方式で切り出す (${chunk.startMs}ms)",
                isWarningOrError = true,
            )
        }
        return shorts
    }

    /** [languagePair] の解説言語が学習対象言語と異なる場合はそれを返し、1 言語だけの場合は `null` を返す。 */
    private fun distinctBaseLanguage(languagePair: LanguagePair): LanguageTag? = languagePair.base?.takeIf {
        !it.value.equals(languagePair.target.value, ignoreCase = true)
    }

    /** 言語判定のみの処理で切り出す区間の終わり（チャンクの先頭から最大 [LID_ONLY_MAX_EXTRACT_DURATION_MS]）。 */
    private fun languageOnlyExtractEndMs(startMs: Long, endMs: Long): Long = min(endMs, startMs + LID_ONLY_MAX_EXTRACT_DURATION_MS)

    /**
     * 言語判定のみの処理のために、チャンクの先頭（[languageOnlyExtractEndMs] まで）を 16kHz モノラルの PCM に切り出す。
     *
     * @param lane 並列の切り出しのレーン。`null` なら共有の抽出器を使う（今の方式）。
     * @return 切り出した PCM。切り出せなかった場合は `null`（例外の場合は診断ログに残す）。
     */
    private suspend fun extractLanguageOnlyPcm(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        lane: RangeDecodeLane? = null,
    ): ShortArray? {
        val timings = languageOnlyTimings
        val startNanos = System.nanoTime()
        try {
            val pcmBytes = try {
                ChunkPcmExtractor.extractChunkPcm16LeBytes(
                    decoder = audioStreamDecoder,
                    audioFile = audioFile,
                    startMs = startMs,
                    endMs = languageOnlyExtractEndMs(startMs, endMs),
                    sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
                    lane = lane,
                    timings = timings,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                appendDiagnostic(
                    stage = "LID_ONLY",
                    message = "音声切り出しエラー ($startMs..${endMs}ms, ${e.javaClass.simpleName})",
                    isWarningOrError = true,
                )
                null
            } ?: return null
            val handoffStartNanos = System.nanoTime()
            val shorts = ChunkPcmExtractor.pcm16LeBytesToShorts(pcmBytes)
            timings.handoffNanos.addAndGet(System.nanoTime() - handoffStartNanos)
            return shorts
        } finally {
            timings.extractions.incrementAndGet()
            timings.extractNanos.addAndGet(System.nanoTime() - startNanos)
        }
    }

    /**
     * [analyzeWhisperLid] を呼び、失敗した場合は診断ログに残して `null` を返す。
     *
     * ファイル内の状態に触れないので、複数のチャンクを同時に呼んでよい。
     */
    private fun analyzeWhisperLidSafely(
        shorts: ShortArray,
        startMs: Long,
        endMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): WhisperLidAnalysis? = try {
        analyzeWhisperLid(
            shorts = shorts,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            targetLang = targetLang,
            baseLang = baseLang,
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        appendDiagnostic(
            stage = "LID_ONLY",
            message = "言語判定エラー ($startMs..${endMs}ms, ${e.javaClass.simpleName})",
            isWarningOrError = true,
        )
        null
    }

    /**
     * Whisper の解析結果 [analysis] にファイル内の補正をかけ、言語判定のみの結果にする。
     *
     * 補正はファイル内の学習状態（アンカー）を更新するため、同じファイルのチャンクはチャンクの順に呼ぶ。
     *
     * @return 言語判定のみの結果。補正に失敗した場合は診断ログに残して `null`。
     */
    private fun adaptLanguageOnlyResult(
        analysis: WhisperLidAnalysis,
        audioScopeKey: String,
        startMs: Long,
        endMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val prediction = try {
            applyFileAdaptation(
                analysis = analysis,
                audioScopeKey = audioScopeKey,
                observationKey = startMs,
                targetLang = targetLang,
                baseLang = baseLang,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            appendDiagnostic(
                stage = "LID_ONLY",
                message = "言語判定エラー ($startMs..${endMs}ms, ${e.javaClass.simpleName})",
                isWarningOrError = true,
            )
            return null
        }
        if (prediction.selectedProbability < MIN_DETERMINED_LID_PROBABILITY) {
            return null
        }
        val selectedLang = if (prediction.selectedTag.equals(baseLang.value, ignoreCase = true)) {
            baseLang
        } else {
            targetLang
        }
        return OnDeviceChunkTranscriptionResult(
            text = "",
            language = selectedLang,
            confidence = prediction.selectedProbability,
            engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
        )
    }

    /**
     * 端末が熱い（[PowerManager.THERMAL_STATUS_MODERATE] 以上）か省電力モードで、推論の並列化を控えるべきかどうか。
     *
     * 状態を取得できない場合は `false`（並列化してよい）とする。
     */
    private fun isDeviceConstrainedForParallelInference(): Boolean = runCatching {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return@runCatching false
        powerManager.isPowerSaveMode || powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }.getOrDefault(false)

    override suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || pcm16LeBytes.isEmpty() || sampleRateHz <= 0) {
            return null
        }
        val chunkDurationMs = ((pcm16LeBytes.size / 2).toLong() * 1_000L) / sampleRateHz.toLong()
        val syntheticScope = ChunkAudioCacheScope(
            audioPath = "pcm:${pcm16LeBytes.size}:${pcm16LeBytes.contentHashCode()}",
            lastModified = sampleRateHz.toLong(),
            startMs = 0L,
            endMs = chunkDurationMs,
        )
        return transcribePcm16LeInternal(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            languagePair = languagePair,
            cacheScope = syntheticScope,
            forceRefresh = false,
            highPriority = false,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
            engineByLanguage = emptyMap(),
        )
    }

    private suspend fun transcribePcm16LeInternal(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
        cacheScope: ChunkAudioCacheScope,
        forceRefresh: Boolean,
        highPriority: Boolean,
        engineOverride: OnDeviceTranscriptionEngine? = null,
        startedAtEpochMs: Long = System.currentTimeMillis(),
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine> = emptyMap(),
        precomputedLanguage: LanguageTag? = null,
        precomputedLanguageConfidence: Float? = null,
        compareBothLanguages: Boolean = false,
        precedingContextByLanguage: Map<LanguageTag, String> = emptyMap(),
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || pcm16LeBytes.isEmpty() || sampleRateHz <= 0) {
            return null
        }

        val onDeviceAvailable = onDeviceAvailabilityChecker()
        val standardAvailable = standardAvailabilityChecker()
        val sherpaAvailable = sherpaModelManager?.areCoreModelFilesPresent() == true || sherpaTranscriber != null
        val mlKitAvailable = isMlKitPotentiallyAvailable()
        if (!onDeviceAvailable && !standardAvailable && !sherpaAvailable && !mlKitAvailable) return null

        // isOnDeviceRecognitionAvailable == true の端末では物理マイクが開くのを防ぐため preferOnDevice = true のみを使用する
        val preferOnDevice = onDeviceAvailable

        val targetLang = languagePair?.target
        val baseLang = languagePair?.base?.takeIf {
            targetLang == null || !it.value.equals(targetLang.value, ignoreCase = true)
        }
        val chunkDurationMs = ((pcm16LeBytes.size / 2).toLong() * 1_000L) / sampleRateHz.toLong()

        if (!forceRefresh) {
            if (targetLang == null || baseLang == null) {
                val singleLang = targetLang ?: baseLang
                getCachedSttResult(cacheScope, singleLang)?.let { cached ->
                    logInfo(
                        "singleLang cacheHit chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: "default"}",
                    )
                    return cached
                }
            } else {
                getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                    return cachedBoth
                }
            }
        }

        updateActiveStep(
            step = TranscriptionStep.WAITING_LOCK,
            detail = "${cacheScope.startMs}ms..${cacheScope.endMs}ms",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = highPriority,
        )

        return recognitionLock.withLock(highPriority = highPriority) {
            activeTrialEngineOverride = engineOverride
            activePrecedingContextByLanguage = if (precedingContextByLanguage.isEmpty()) {
                emptyMap()
            } else {
                buildMap {
                    for ((lang, text) in precedingContextByLanguage) {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            val normKey = com.miuraken.chunkflow.core.model.LanguageSettings.normalize(lang).value
                            put(normKey, trimmed)
                            put(lang.value.trim().lowercase(Locale.ROOT), trimmed)
                        }
                    }
                }
            }
            sherpaWhisperTranscriberInstance?.activeContextHint = activeTrialContextHint
            val lockAcquiredRealtimeMs = SystemClock.elapsedRealtime()
            try {
                if (forceRefresh) {
                    evictCachedSttResults(cacheScope)
                } else if (targetLang == null || baseLang == null) {
                    val singleLang = targetLang ?: baseLang
                    getCachedSttResult(cacheScope, singleLang)?.let { cached ->
                        return@withLock cached
                    }
                } else {
                    getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                        return@withLock cachedBoth
                    }
                }

                val selectedEngine =
                    activeTrialEngineOverride ?: runCatching { engineProvider() }.getOrDefault(OnDeviceTranscriptionEngine.AUTO)

                val result = if (targetLang != null && baseLang != null) {
                    val shorts by lazy(LazyThreadSafetyMode.NONE) {
                        ChunkPcmExtractor.pcm16LeBytesToShorts(pcm16LeBytes)
                    }
                    val precomputedPrediction = buildPrecomputedLanguagePairPrediction(
                        precomputedLanguage = precomputedLanguage,
                        precomputedConfidence = precomputedLanguageConfidence,
                        targetLang = targetLang,
                        baseLang = baseLang,
                    )
                    val lidPrediction = if (precomputedPrediction != null) {
                        logDebug(
                            "whisperLid skipped (reusing precomputed language=${precomputedPrediction.selectedTag} " +
                                "prob=${precomputedPrediction.selectedProbability}) chunkDurationMs=$chunkDurationMs",
                        )
                        precomputedPrediction
                    } else if (languageDetector != null || languageAnalyzer != null) {
                        updateActiveStep(
                            step = TranscriptionStep.DETECTING_LANGUAGE,
                            detail = "${targetLang.value} / ${baseLang.value}",
                            startedAtEpochMs = startedAtEpochMs,
                            highPriority = highPriority,
                        )
                        runCatching {
                            resolveLanguagePairPrediction(
                                shorts = shorts,
                                sampleRateHz = sampleRateHz,
                                audioScopeKey = cacheScope.audioPath,
                                observationKey = cacheScope.startMs,
                                targetLang = targetLang,
                                baseLang = baseLang,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    val rawResult = if (lidPrediction != null) {
                        transcribeWithWhisperLidPrediction(
                            pcm16LeBytes = pcm16LeBytes,
                            sampleRateHz = sampleRateHz,
                            chunkDurationMs = chunkDurationMs,
                            targetLang = targetLang,
                            baseLang = baseLang,
                            prediction = lidPrediction,
                            preferOnDevice = preferOnDevice,
                            cacheScope = cacheScope,
                            startedAtEpochMs = startedAtEpochMs,
                            highPriority = highPriority,
                            selectedEngine = selectedEngine,
                            itemSttOverride = itemSttOverride,
                            engineByLanguage = engineByLanguage,
                            compareBothLanguages = compareBothLanguages,
                        )
                    } else {
                        transcribeWithTwoSecondLanguageProbe(
                            pcm16LeBytes = pcm16LeBytes,
                            sampleRateHz = sampleRateHz,
                            chunkDurationMs = chunkDurationMs,
                            targetLang = targetLang,
                            baseLang = baseLang,
                            preferOnDevice = preferOnDevice,
                            cacheScope = cacheScope,
                            startedAtEpochMs = startedAtEpochMs,
                            highPriority = highPriority,
                            selectedEngine = selectedEngine,
                            itemSttOverride = itemSttOverride,
                            engineByLanguage = engineByLanguage,
                        )
                    }
                    val adjustedResult = adjustBilingualResultScriptLanguage(rawResult, targetLang, baseLang)
                    val preLang = precomputedLanguage?.value?.trim()?.takeIf {
                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                    }
                    val finalLang = adjustedResult?.language?.value?.trim()?.takeIf {
                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                    }
                    if (preLang != null &&
                        finalLang != null &&
                        !preLang.equals(finalLang, ignoreCase = true) &&
                        isTrackableAudioScope(cacheScope.audioPath)
                    ) {
                        voiceProfileTracker.removeObservation(cacheScope.audioPath, cacheScope.startMs)
                    }
                    adjustedResult
                } else {
                    val singleLang = targetLang ?: baseLang
                    val bcp47Tag = singleLang?.value?.let(::toBcp47LocaleTag)
                        ?: toBcp47LocaleTag(Locale.getDefault().toLanguageTag())
                    updateActiveStep(
                        step = TranscriptionStep.RECOGNIZING_PRIMARY,
                        detail = "${singleLang?.value ?: bcp47Tag} (${selectedEngine.name})",
                        startedAtEpochMs = startedAtEpochMs,
                        highPriority = highPriority,
                    )
                    val attempt = executeAttempt(
                        pcm16LeBytes = pcm16LeBytes,
                        sampleRateHz = sampleRateHz,
                        bcp47LocaleTag = bcp47Tag,
                        preferOnDevice = preferOnDevice,
                        isProbe = false,
                        language = singleLang,
                        tryOrder = resolveTryOrder(singleLang, languagePair, selectedEngine, itemSttOverride, engineByLanguage),
                    )
                    if (attempt != null && attempt.text.isNotBlank()) {
                        logInfo(
                            "singleLang chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag} chars=${attempt.text.trim().length}",
                        )
                        val res = OnDeviceChunkTranscriptionResult(
                            text = attempt.text.trim(),
                            language = singleLang,
                            confidence = attempt.confidence,
                            engineId = attempt.engineId,
                        )
                        putCachedSttResult(cacheScope, singleLang?.value ?: bcp47Tag, res)
                        res
                    } else {
                        logInfo(
                            "singleLang empty chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag} " +
                                "modelUnavailable=${attempt?.isLanguageModelUnavailable == true}",
                        )
                        null
                    }
                }
                val totalElapsedMs = SystemClock.elapsedRealtime() - lockAcquiredRealtimeMs
                appendDiagnostic(
                    stage = if (highPriority) "FORCE_STT" else "LOOKAHEAD_STT",
                    message = buildString {
                        append("${cacheScope.startMs}..${cacheScope.endMs}ms (${totalElapsedMs}ms): ")
                        when {
                            result == null -> append("認識結果なし（無音または認識不能）")
                            result.isLanguageOnly -> append("言語判定のみ (${result.language?.value ?: "-"} / モデル未導入)")
                            else -> append("成功 lang=${result.language?.value ?: "-"} chars=${result.text.length} engine=${result.engineId.value}")
                        }
                    },
                    isWarningOrError = result == null || result.isLanguageOnly,
                )
                result
            } catch (ce: CancellationException) {
                val elapsedMs = SystemClock.elapsedRealtime() - lockAcquiredRealtimeMs
                appendDiagnostic(
                    stage = if (highPriority) "FORCE_STT" else "LOOKAHEAD_STT",
                    message = "認識を中断 (${cacheScope.startMs}..${cacheScope.endMs}ms, ${elapsedMs}ms)",
                    isWarningOrError = false,
                )
                throw ce
            } finally {
                activeTrialEngineOverride = null
                activePrecedingContextByLanguage = emptyMap()
                sherpaWhisperTranscriberInstance?.activeContextHint = null
            }
        }
    }

    private fun isTrackableAudioScope(audioScopeKey: String): Boolean = audioScopeKey.isNotBlank() &&
        !audioScopeKey.startsWith("pcm:") &&
        !audioScopeKey.startsWith("trial:")

    /**
     * Whisper Tiny による 1 チャンク分の解析結果（ファイル内の補正の前）。
     *
     * @property prediction Whisper 単体の 2 言語判定（同系の言語を合算した確率と pairMass）。
     * @property embedding エンコーダ出力の平均ベクトル（[languageAnalyzer] がない場合は `null`）。
     * @property analyzedDurationMs 解析した音声の長さ（ミリ秒、不明な場合は `null`）。
     */
    private class WhisperLidAnalysis(
        val prediction: LanguagePairPrediction,
        val embedding: FloatArray?,
        val analyzedDurationMs: Long?,
    )

    /**
     * チャンクの音声 [shorts] の 2 言語判定を求める（[analyzeWhisperLid] のあと [applyFileAdaptation]）。
     *
     * @return 判定結果。判定器がない、または無音などで判定できない場合は `null`。
     */
    private fun resolveLanguagePairPrediction(
        shorts: ShortArray,
        sampleRateHz: Int,
        audioScopeKey: String,
        observationKey: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): LanguagePairPrediction? {
        val analysis = analyzeWhisperLid(shorts, sampleRateHz, targetLang, baseLang) ?: return null
        return applyFileAdaptation(
            analysis = analysis,
            audioScopeKey = audioScopeKey,
            observationKey = observationKey,
            targetLang = targetLang,
            baseLang = baseLang,
        )
    }

    /**
     * チャンクの音声 [shorts] を Whisper Tiny で解析する（ファイル内の補正はしない）。
     *
     * ファイル内の状態に触れず、判定器（[WhisperTinyLanguageDetector]）もスレッドセーフなので、複数のチャンクを同時に解析してよい。
     *
     * @return 解析結果。判定器がない、または無音などで判定できない場合は `null`。
     */
    private fun analyzeWhisperLid(
        shorts: ShortArray,
        sampleRateHz: Int,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): WhisperLidAnalysis? {
        val analyzer = languageAnalyzer
        val analysis = analyzer?.invoke(shorts, targetLang.value, baseLang.value)
        val whisperPred = if (analyzer != null) {
            analysis?.prediction
        } else {
            languageDetector?.invoke(shorts, targetLang.value, baseLang.value)
        } ?: return null
        return WhisperLidAnalysis(
            prediction = whisperPred,
            embedding = analysis?.encoderEmbedding,
            analyzedDurationMs = if (sampleRateHz > 0) shorts.size.toLong() * 1_000L / sampleRateHz.toLong() else null,
        )
    }

    /**
     * Whisper の解析結果 [analysis] に、[audioScopeKey] のファイル内で学習した声と言語の特徴
     * （[VoiceProfileLanguageTracker]）の判定を足す。Whisper 単体で高確信のチャンクは、
     * [observationKey]（チャンクの開始時刻）をキーにしてファイル内のアンカーとして登録する。
     *
     * アンカーの登録は後のチャンクの判定に影響するため、同じファイルのチャンクはチャンクの順に呼ぶ。
     */
    private fun applyFileAdaptation(
        analysis: WhisperLidAnalysis,
        audioScopeKey: String,
        observationKey: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): LanguagePairPrediction {
        val whisperPred = analysis.prediction
        val embedding = analysis.embedding?.takeIf { isTrackableAudioScope(audioScopeKey) }
        val speakerPred = embedding?.let {
            voiceProfileTracker.predictLanguageFromVoiceProfile(
                audioScopeKey = audioScopeKey,
                voiceProfile = it,
                targetTag = targetLang.value,
                baseTag = baseLang.value,
            )
        }
        val combined = voiceProfileTracker.combineWithWhisperPrediction(
            whisperPrediction = whisperPred,
            speakerPrediction = speakerPred,
            targetTag = targetLang.value,
            baseTag = baseLang.value,
            analyzedDurationMs = analysis.analyzedDurationMs,
        )
        if (embedding != null && VoiceProfileLanguageTracker.isAnchorEligible(whisperPred)) {
            voiceProfileTracker.recordSpeakerObservation(
                audioScopeKey = audioScopeKey,
                observationKey = observationKey,
                languageTag = whisperPred.selectedTag,
                voiceProfile = embedding,
            )
        }
        logDebug(
            "lid whisper=${whisperPred.selectedTag}:${whisperPred.selectedProbability} mass=${whisperPred.pairMass} " +
                "speakerLogOdds=${speakerPred?.logOdds} anchors=${speakerPred?.targetAnchorCount}/${speakerPred?.baseAnchorCount} " +
                "combined=${combined.selectedTag}:${combined.selectedProbability} durationMs=${analysis.analyzedDurationMs}",
        )
        return combined
    }

    private fun buildPrecomputedLanguagePairPrediction(
        precomputedLanguage: LanguageTag?,
        precomputedConfidence: Float?,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): LanguagePairPrediction? {
        val rawTag = precomputedLanguage?.value?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("und", ignoreCase = true)
        } ?: return null
        val isBase = rawTag.equals(baseLang.value, ignoreCase = true)
        val isTarget = rawTag.equals(targetLang.value, ignoreCase = true)
        if (!isBase && !isTarget) return null

        val selectedProb = (precomputedConfidence?.takeIf { it.isFinite() } ?: DEFAULT_PRECOMPUTED_LID_CONFIDENCE)
            .coerceIn(0.5f, 1.0f)
        val otherProb = (1.0f - selectedProb).coerceIn(0.0f, 0.5f)
        return if (isBase) {
            LanguagePairPrediction(
                selectedTag = baseLang.value,
                fallbackTag = targetLang.value,
                selectedProbability = selectedProb,
                targetProbability = otherProb,
                baseProbability = selectedProb,
                inferenceTimeMs = 0L,
            )
        } else {
            LanguagePairPrediction(
                selectedTag = targetLang.value,
                fallbackTag = baseLang.value,
                selectedProbability = selectedProb,
                targetProbability = selectedProb,
                baseProbability = otherProb,
                inferenceTimeMs = 0L,
            )
        }
    }

    private suspend fun transcribeWithWhisperLidPrediction(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        chunkDurationMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        prediction: LanguagePairPrediction,
        preferOnDevice: Boolean,
        cacheScope: ChunkAudioCacheScope,
        startedAtEpochMs: Long,
        highPriority: Boolean,
        selectedEngine: OnDeviceTranscriptionEngine,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine> = emptyMap(),
        compareBothLanguages: Boolean = false,
    ): OnDeviceChunkTranscriptionResult? {
        val selectedLang = if (prediction.selectedTag.equals(baseLang.value, ignoreCase = true)) {
            baseLang
        } else {
            targetLang
        }
        val fallbackLang = if (selectedLang == targetLang) baseLang else targetLang

        logDebug(
            "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                "targetProb=${prediction.targetProbability} baseProb=${prediction.baseProbability} " +
                "lidMs=${prediction.inferenceTimeMs} chunkDurationMs=$chunkDurationMs",
        )

        var primaryModelUnavailable = false
        val primaryResult = getCachedSttResult(cacheScope, selectedLang) ?: run {
            val selectedBcp47 = toBcp47LocaleTag(selectedLang.value)
            updateActiveStep(
                step = TranscriptionStep.RECOGNIZING_PRIMARY,
                detail = "${selectedLang.value} (${selectedEngine.name})",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = highPriority,
            )
            val attempt = executeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = selectedBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = false,
                language = selectedLang,
                tryOrder = resolveTryOrder(selectedLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )
            primaryModelUnavailable = attempt?.isLanguageModelUnavailable == true
            attempt?.takeIf { it.text.isNotBlank() }?.let {
                OnDeviceChunkTranscriptionResult(
                    text = it.text.trim(),
                    language = selectedLang,
                    confidence = it.confidence,
                    engineId = it.engineId,
                ).also { res ->
                    putCachedSttResult(cacheScope, selectedLang.value, res)
                }
            }
        }

        val isLidConfident = prediction.selectedProbability >= AMBIGUOUS_LID_PROB_THRESHOLD
        if (primaryResult == null && primaryModelUnavailable && isLidConfident && !compareBothLanguages) {
            // 選ばれた言語の音声認識モデルがない。もう一方の言語で認識するとカタカナ・ハングルの音写になり
            // 言語ラベルも誤るため、言語判定の結果だけを返す（モデルのダウンロード後に本文を認識し直す）
            logInfo(
                "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                    "modelUnavailable=true result=languageOnly chunkDurationMs=$chunkDurationMs",
            )
            return languageOnlyResult(selectedLang, prediction.selectedProbability)
        }

        val shouldVerifyFallback = primaryResult != null && (
            compareBothLanguages ||
                isUnnaturalOrTransliterationSuspected(primaryResult.text, selectedLang.value) ||
                resolveLanguageByRecognizedScript(primaryResult.text, selectedLang, fallbackLang) != selectedLang ||
                (primaryResult.confidence != null && primaryResult.confidence < LOW_STT_CONFIDENCE_THRESHOLD) ||
                (
                    prediction.selectedProbability < AMBIGUOUS_LID_PROB_THRESHOLD &&
                        (primaryResult.confidence == null || primaryResult.confidence < MODERATE_STT_CONFIDENCE_THRESHOLD)
                    )
            )

        if (primaryResult != null && !shouldVerifyFallback) {
            return adjustBilingualResultScriptLanguage(primaryResult, targetLang, baseLang)
        }

        val fallbackResult = getCachedSttResult(cacheScope, fallbackLang) ?: run {
            val fallbackBcp47 = toBcp47LocaleTag(fallbackLang.value)
            updateActiveStep(
                step = TranscriptionStep.RECOGNIZING_FALLBACK,
                detail = "${fallbackLang.value} (${selectedEngine.name})",
                startedAtEpochMs = startedAtEpochMs,
                highPriority = highPriority,
            )
            val attempt = executeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = fallbackBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = false,
                language = fallbackLang,
                tryOrder = resolveTryOrder(fallbackLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )?.takeIf { it.text.isNotBlank() }
            attempt?.let {
                OnDeviceChunkTranscriptionResult(
                    text = it.text.trim(),
                    language = fallbackLang,
                    confidence = it.confidence,
                    engineId = it.engineId,
                ).also { res ->
                    putCachedSttResult(cacheScope, fallbackLang.value, res)
                }
            }
        }

        if (primaryResult == null) {
            // 選ばれた言語で認識できなかった場合、もう一方の言語の結果は、自然な発話に見える（音写の疑いがない）ときか、
            // 言語判定が曖昧で、かつ選ばれた言語のモデル自体は使える（単に聞き取れなかった）ときだけ採用する
            val acceptFallback = fallbackResult != null && (
                isNaturalRecognitionFor(fallbackResult, fallbackLang) ||
                    (!isLidConfident && !primaryModelUnavailable)
                )
            if (acceptFallback) {
                return adjustBilingualResultScriptLanguage(fallbackResult, targetLang, baseLang)
            }
            if (primaryModelUnavailable || fallbackResult != null) {
                logInfo(
                    "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                        "modelUnavailable=$primaryModelUnavailable fallbackRejected=${fallbackResult != null} " +
                        "result=languageOnly chunkDurationMs=$chunkDurationMs",
                )
                return languageOnlyResult(selectedLang, prediction.selectedProbability)
            }
            return null
        }
        if (fallbackResult == null) {
            return adjustBilingualResultScriptLanguage(primaryResult, targetLang, baseLang)
        }

        val primaryScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = primaryResult.text, confidence = primaryResult.confidence),
            selectedLang.value,
        )
        val fallbackScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = fallbackResult.text, confidence = fallbackResult.confidence),
            fallbackLang.value,
        )
        val chosen = if (fallbackScore > primaryScore) fallbackResult else primaryResult
        return adjustBilingualResultScriptLanguage(chosen, targetLang, baseLang)
    }

    /** 認識結果 [result] が言語 [language] の自然な発話に見える（音写の疑いがなく、信頼度が低すぎない）かどうか。 */
    private fun isNaturalRecognitionFor(
        result: OnDeviceChunkTranscriptionResult,
        language: LanguageTag,
    ): Boolean = !isUnnaturalOrTransliterationSuspected(result.text, language.value) &&
        (result.confidence == null || result.confidence >= LOW_STT_CONFIDENCE_THRESHOLD)

    /** 本文なしで、言語判定の結果だけを持つ認識結果を作る。キャッシュには入れない。 */
    private fun languageOnlyResult(
        language: LanguageTag,
        lidProbability: Float,
    ): OnDeviceChunkTranscriptionResult = OnDeviceChunkTranscriptionResult(
        text = "",
        language = language,
        confidence = lidProbability.coerceIn(0f, 1f),
        engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
    )

    private fun adjustBilingualResultScriptLanguage(
        result: OnDeviceChunkTranscriptionResult?,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        if (result == null || result.isLanguageOnly || result.text.isBlank()) return result
        val currentLang = result.language ?: return result
        val otherLang = if (currentLang.value.equals(baseLang.value, ignoreCase = true)) {
            targetLang
        } else {
            baseLang
        }
        val correctedLang = resolveLanguageByRecognizedScript(result.text, currentLang, otherLang)
        return if (correctedLang != currentLang) {
            result.copy(language = correctedLang)
        } else {
            result
        }
    }

    private fun getCachedBilingualResultIfBothPresent(
        cacheScope: ChunkAudioCacheScope,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val cachedTarget = getCachedSttResult(cacheScope, targetLang) ?: return null
        val cachedBase = getCachedSttResult(cacheScope, baseLang) ?: return null
        val targetScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = cachedTarget.text, confidence = cachedTarget.confidence),
            targetLang.value,
        )
        val baseScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = cachedBase.text, confidence = cachedBase.confidence),
            baseLang.value,
        )
        val chosen = if (baseScore > targetScore) cachedBase else cachedTarget
        return adjustBilingualResultScriptLanguage(chosen, targetLang, baseLang)
    }

    private suspend fun transcribeWithTwoSecondLanguageProbe(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        chunkDurationMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        preferOnDevice: Boolean,
        cacheScope: ChunkAudioCacheScope,
        startedAtEpochMs: Long,
        highPriority: Boolean,
        selectedEngine: OnDeviceTranscriptionEngine,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine> = emptyMap(),
    ): OnDeviceChunkTranscriptionResult? {
        val probeSlice = ChunkPcmExtractor.extractLeadingSpeechProbePcm(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
        )
        val probeBytes = probeSlice.probePcm16LeBytes
        val targetBcp47 = toBcp47LocaleTag(targetLang.value)
        val baseBcp47 = toBcp47LocaleTag(baseLang.value)

        updateActiveStep(
            step = TranscriptionStep.DETECTING_LANGUAGE,
            detail = "Probe ${targetLang.value}",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = highPriority,
        )

        val targetProbe = if (probeSlice.isFullChunkCovered) {
            getCachedSttResult(cacheScope, targetLang)?.let {
                SpeechRecognitionAttemptResult(text = it.text, confidence = it.confidence, engineId = it.engineId)
            } ?: executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = targetBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = targetLang,
                tryOrder = resolveTryOrder(targetLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )?.takeIf { it.text.isNotBlank() }?.also { probe ->
                putCachedSttResult(
                    cacheScope = cacheScope,
                    rawLangTag = targetLang.value,
                    result = OnDeviceChunkTranscriptionResult(
                        text = probe.text.trim(),
                        language = targetLang,
                        confidence = probe.confidence,
                        engineId = probe.engineId,
                    ),
                )
            }
        } else {
            executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = targetBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = targetLang,
                tryOrder = resolveTryOrder(targetLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )?.takeIf { it.text.isNotBlank() }
        }

        updateActiveStep(
            step = TranscriptionStep.DETECTING_LANGUAGE,
            detail = "Probe ${baseLang.value}",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = highPriority,
        )

        val baseProbe = if (probeSlice.isFullChunkCovered) {
            getCachedSttResult(cacheScope, baseLang)?.let {
                SpeechRecognitionAttemptResult(text = it.text, confidence = it.confidence, engineId = it.engineId)
            } ?: executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = baseBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = baseLang,
                tryOrder = resolveTryOrder(baseLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )?.takeIf { it.text.isNotBlank() }?.also { probe ->
                putCachedSttResult(
                    cacheScope = cacheScope,
                    rawLangTag = baseLang.value,
                    result = OnDeviceChunkTranscriptionResult(
                        text = probe.text.trim(),
                        language = baseLang,
                        confidence = probe.confidence,
                        engineId = probe.engineId,
                    ),
                )
            }
        } else {
            executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = baseBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = baseLang,
                tryOrder = resolveTryOrder(baseLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
            )?.takeIf { it.text.isNotBlank() }
        }

        if (targetProbe == null && baseProbe == null) {
            logInfo(
                "probe empty chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} baseLang=${baseLang.value}",
            )
            return null
        }

        val targetScore = targetProbe?.let { scoreProbeCandidateForLanguage(it, targetLang.value) }
            ?: Float.NEGATIVE_INFINITY
        val baseScore = baseProbe?.let { scoreProbeCandidateForLanguage(it, baseLang.value) }
            ?: Float.NEGATIVE_INFINITY

        val winningLang: LanguageTag
        val winningProbe: SpeechRecognitionAttemptResult
        if (baseProbe != null && (targetProbe == null || baseScore > targetScore)) {
            winningLang = baseLang
            winningProbe = baseProbe
        } else if (targetProbe != null) {
            winningLang = targetLang
            winningProbe = targetProbe
        } else {
            return null
        }

        if (probeSlice.isFullChunkCovered) {
            val text = winningProbe.text.trim()
            logInfo(
                "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} targetScore=$targetScore baseLang=${baseLang.value} baseScore=$baseScore fullChunkUsed=false chars=${text.length}",
            )
            val result = OnDeviceChunkTranscriptionResult(
                text = text,
                language = winningLang,
                confidence = winningProbe.confidence,
                engineId = winningProbe.engineId,
            )
            putCachedSttResult(cacheScope, winningLang.value, result)
            return result
        }

        getCachedSttResult(cacheScope, winningLang)?.let { cached ->
            logInfo(
                "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs fullChunkCacheHit=true chars=${cached.text.length}",
            )
            return cached
        }

        val winningBcp47 = toBcp47LocaleTag(winningLang.value)
        updateActiveStep(
            step = TranscriptionStep.RECOGNIZING_PRIMARY,
            detail = "${winningLang.value} (${selectedEngine.name})",
            startedAtEpochMs = startedAtEpochMs,
            highPriority = highPriority,
        )
        val fullAttempt = executeAttempt(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = winningBcp47,
            preferOnDevice = preferOnDevice,
            isProbe = false,
            language = winningLang,
            tryOrder = resolveTryOrder(winningLang, com.miuraken.chunkflow.core.model.LanguagePair(targetLang, baseLang), selectedEngine, itemSttOverride, engineByLanguage),
        )?.takeIf { it.text.isNotBlank() }

        val finalAttempt = fullAttempt ?: winningProbe
        val finalText = finalAttempt.text.trim()
        logInfo(
            "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} targetScore=$targetScore baseLang=${baseLang.value} baseScore=$baseScore fullChunkUsed=${fullAttempt != null} chars=${finalText.length}",
        )
        val result = OnDeviceChunkTranscriptionResult(
            text = finalText,
            language = winningLang,
            confidence = finalAttempt.confidence,
            engineId = finalAttempt.engineId,
        )
        putCachedSttResult(cacheScope, winningLang.value, result)
        return result
    }

    private fun normalizeLangCacheKey(rawLangTag: String?): String {
        val tag = rawLangTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
        return toBcp47LocaleTag(tag).lowercase(Locale.ROOT)
    }

    private fun getCachedSttResult(
        cacheScope: ChunkAudioCacheScope,
        requestedLang: LanguageTag?,
    ): OnDeviceChunkTranscriptionResult? {
        val key = ChunkSttCacheKey(
            audioPath = cacheScope.audioPath,
            lastModified = cacheScope.lastModified,
            startMs = cacheScope.startMs,
            endMs = cacheScope.endMs,
            normalizedLangTag = normalizeLangCacheKey(requestedLang?.value),
        )
        val cached = synchronized(sttCacheLock) {
            fullChunkSttCache[key]
        } ?: return null
        return if (requestedLang != null && cached.language != requestedLang) {
            cached.copy(language = requestedLang)
        } else {
            cached
        }
    }

    private fun putCachedSttResult(
        cacheScope: ChunkAudioCacheScope,
        rawLangTag: String?,
        result: OnDeviceChunkTranscriptionResult,
    ) {
        if (result.text.isBlank()) return
        val key = ChunkSttCacheKey(
            audioPath = cacheScope.audioPath,
            lastModified = cacheScope.lastModified,
            startMs = cacheScope.startMs,
            endMs = cacheScope.endMs,
            normalizedLangTag = normalizeLangCacheKey(rawLangTag),
        )
        synchronized(sttCacheLock) {
            fullChunkSttCache[key] = result
        }
    }

    private fun evictCachedSttResults(cacheScope: ChunkAudioCacheScope) {
        synchronized(sttCacheLock) {
            val iterator = fullChunkSttCache.entries.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next().key
                if (key.audioPath == cacheScope.audioPath &&
                    key.lastModified == cacheScope.lastModified &&
                    key.startMs == cacheScope.startMs &&
                    key.endMs == cacheScope.endMs
                ) {
                    iterator.remove()
                }
            }
        }
    }

    private fun resolveEffectiveWhisperContextHint(language: LanguageTag?): String? {
        val trialHint = activeTrialContextHint?.takeIf { it.isNotBlank() }
        if (trialHint != null) return trialHint
        if (language == null) return null
        val contextMap = activePrecedingContextByLanguage
        if (contextMap.isEmpty()) return null
        val normKey = com.miuraken.chunkflow.core.model.LanguageSettings.normalize(language).value
        val rawKey = language.value.trim().lowercase(Locale.ROOT)
        val precedingText = contextMap[normKey] ?: contextMap[rawKey] ?: return null
        return "PROMPT:$precedingText"
    }

    private fun trySherpaInference(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        language: LanguageTag?,
    ): SpeechRecognitionAttemptResult? {
        if (language == null) return null
        val runner = sherpaTranscriber ?: return null
        if (sherpaModelManager != null &&
            sherpaModelManager.getLanguageModelStatus(language) != OnDeviceLanguageModelStatus.INSTALLED
        ) {
            return null
        }
        val whisperTranscriber = sherpaWhisperTranscriberInstance
        val prevHint = whisperTranscriber?.activeContextHint
        whisperTranscriber?.activeContextHint = resolveEffectiveWhisperContextHint(language)
        val result = try {
            runCatching {
                runner(pcm16LeBytes, sampleRateHz, language)
            }.getOrNull()
        } finally {
            whisperTranscriber?.activeContextHint = prevHint
        } ?: return null
        if (result.text.isBlank()) return null
        clearModelIssue(language)
        return SpeechRecognitionAttemptResult(
            text = result.text.trim(),
            confidence = result.confidence,
            errorCode = null,
            engineId = result.engineId,
        )
    }

    private suspend fun tryMlKitInference(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        candidates: List<String>,
        isProbe: Boolean,
        language: LanguageTag?,
    ): SpeechRecognitionAttemptResult? {
        val gateway = mlKitGateway ?: return null
        if (!gateway.isDevicePotentiallySupported()) return null
        var lastMlKitAttempt: SpeechRecognitionAttemptResult? = null
        for ((index, candidateTag) in candidates.withIndex()) {
            val attempt = try {
                gateway.recognizePcm16Le(
                    pcm16LeBytes = pcm16LeBytes,
                    sampleRateHz = sampleRateHz,
                    bcp47LocaleTag = candidateTag,
                    isProbe = isProbe,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            lastMlKitAttempt = attempt
            if (attempt?.errorCode != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                if (index > 0 ||
                    attempt?.text?.isNotBlank() == true ||
                    attempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    rememberResolvedBcp47Tag(language?.value, candidateTag)
                }
                if (attempt?.text?.isNotBlank() == true) {
                    if (language != null) clearModelIssue(language)
                    return attempt
                }
                if (attempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && language != null) {
                    val current = modelIssuesState.value[language]
                    if (current != OnDeviceRecognitionModelState.DOWNLOADING &&
                        current != OnDeviceRecognitionModelState.DOWNLOAD_FAILED
                    ) {
                        setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                    }
                    if (autoDownloadMlKitRequestedTags.add(candidateTag.lowercase(Locale.ROOT))) {
                        downloadMlKitModel(language, candidateTag)
                        val refreshedAttempt = try {
                            gateway.recognizePcm16Le(
                                pcm16LeBytes = pcm16LeBytes,
                                sampleRateHz = sampleRateHz,
                                bcp47LocaleTag = candidateTag,
                                isProbe = isProbe,
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                        if (refreshedAttempt?.text?.isNotBlank() == true) {
                            clearModelIssue(language)
                            return refreshedAttempt
                        }
                    }
                }
                return attempt
            }
        }
        return lastMlKitAttempt
    }

    private fun resolveTryOrder(
        language: LanguageTag?,
        languagePair: LanguagePair?,
        selectedEngine: OnDeviceTranscriptionEngine,
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
    ): List<com.miuraken.chunkflow.core.model.SttEngine> {
        when (selectedEngine) {
            OnDeviceTranscriptionEngine.SHERPA_ONNX -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX)
            OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE)
            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE)
            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL)
            OnDeviceTranscriptionEngine.MOONSHINE_TINY -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY)
            OnDeviceTranscriptionEngine.MOONSHINE_BASE -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE)
            OnDeviceTranscriptionEngine.OPENAI_API -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API)
            OnDeviceTranscriptionEngine.GROQ_API -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.GROQ_API)
            OnDeviceTranscriptionEngine.GEMINI_API -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API)
            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER -> return listOf(com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER)
            OnDeviceTranscriptionEngine.ML_KIT -> return com.miuraken.chunkflow.core.model.SttEngine.tryOrder(com.miuraken.chunkflow.core.model.SttEngine.ML_KIT)
            OnDeviceTranscriptionEngine.AUTO -> Unit
        }
        if (language == null) return com.miuraken.chunkflow.core.model.SttEngine.AUTO_ORDER
        return com.miuraken.chunkflow.core.model.SttEngineSelection.tryOrder(
            language = language,
            languagePair = languagePair ?: com.miuraken.chunkflow.core.model.LanguagePair(language, null),
            itemOverride = itemSttOverride,
            engineByLanguage = engineByLanguage,
        )
    }

    private suspend fun executeAttempt(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
        language: LanguageTag? = null,
        tryOrder: List<com.miuraken.chunkflow.core.model.SttEngine> = com.miuraken.chunkflow.core.model.SttEngine.AUTO_ORDER,
    ): SpeechRecognitionAttemptResult? {
        val candidates = candidateBcp47TagsFor(language?.value, bcp47LocaleTag)
        var lastAttempt: SpeechRecognitionAttemptResult? = null
        val catalogEntry = language?.value?.let { com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.findByCode(it) }

        val effectiveTryOrder = when (activeTrialEngineOverride) {
            OnDeviceTranscriptionEngine.AUTO -> com.miuraken.chunkflow.core.model.SttEngine.AUTO_ORDER

            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER -> listOf(com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER)

            OnDeviceTranscriptionEngine.SHERPA_ONNX -> listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX)

            OnDeviceTranscriptionEngine.SHERPA_ONNX_SENSE_VOICE -> listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE)

            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_BASE -> listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE)

            OnDeviceTranscriptionEngine.SHERPA_ONNX_WHISPER_SMALL -> listOf(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL)

            OnDeviceTranscriptionEngine.MOONSHINE_TINY -> listOf(com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY)

            OnDeviceTranscriptionEngine.MOONSHINE_BASE -> listOf(com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE)

            OnDeviceTranscriptionEngine.OPENAI_API -> listOf(com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API)

            OnDeviceTranscriptionEngine.GROQ_API -> listOf(com.miuraken.chunkflow.core.model.SttEngine.GROQ_API)

            OnDeviceTranscriptionEngine.GEMINI_API -> listOf(com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API)

            OnDeviceTranscriptionEngine.ML_KIT -> listOf(
                com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
                com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER,
            )

            null -> tryOrder
        }

        for (engine in effectiveTryOrder) {
            if (!isProbe) {
                activeStepMutableState.update { current ->
                    if (current != null &&
                        (current.step == TranscriptionStep.RECOGNIZING_PRIMARY || current.step == TranscriptionStep.RECOGNIZING_FALLBACK)
                    ) {
                        val langLabel = language?.value ?: bcp47LocaleTag
                        val engineLabel = when (engine) {
                            com.miuraken.chunkflow.core.model.SttEngine.ML_KIT -> "ML Kit GenAI"
                            com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER -> "SpeechRecognizer"
                            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX -> "sherpa-onnx Whisper Tiny"
                            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE -> "sherpa-onnx SenseVoice Small"
                            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE -> "sherpa-onnx Whisper Base"
                            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL -> "sherpa-onnx Whisper Small"
                            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY -> "Moonshine ONNX Tiny"
                            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE -> "Moonshine ONNX Base"
                            com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API -> "OpenAI API"
                            com.miuraken.chunkflow.core.model.SttEngine.GROQ_API -> "Groq API"
                            com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API -> "Gemini API"
                        }
                        current.copy(detail = "$langLabel ($engineLabel)")
                    } else {
                        current
                    }
                }
            }
            when (engine) {
                com.miuraken.chunkflow.core.model.SttEngine.ML_KIT -> {
                    if (catalogEntry != null && !catalogEntry.mlKitSupported) {
                        lastAttempt = SpeechRecognitionAttemptResult(
                            text = "",
                            errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            engineId = MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID,
                        )
                        recordExternalDiagnostic("fallback", "Fallback from ML_KIT: ML_KIT is unsupported for ${language.value}", true)
                    } else if (isMlKitPotentiallyAvailable()) {
                        val mlKitAttempt = tryMlKitInference(
                            pcm16LeBytes = pcm16LeBytes,
                            sampleRateHz = sampleRateHz,
                            candidates = candidates,
                            isProbe = isProbe,
                            language = language,
                        )
                        lastAttempt = mlKitAttempt
                        if (mlKitAttempt?.text?.isNotBlank() == true) {
                            return mlKitAttempt
                        }
                        if (mlKitAttempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            recordExternalDiagnostic("fallback", "Fallback from ML_KIT: ML_KIT is unavailable", true)
                        } else if (
                            activeTrialEngineOverride == OnDeviceTranscriptionEngine.ML_KIT &&
                            mlKitAttempt != null &&
                            mlKitAttempt.errorCode != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        ) {
                            return mlKitAttempt
                        } else if (mlKitAttempt?.errorCode != null) {
                            recordExternalDiagnostic("fallback", "Fallback from ML_KIT: ML_KIT failed with ${mlKitAttempt.errorCode}", true)
                        }
                    } else {
                        recordExternalDiagnostic("fallback", "Fallback from ML_KIT: ML_KIT is not potentially available", true)
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER -> {
                    if (catalogEntry != null && !catalogEntry.speechRecognizerSupported) {
                        lastAttempt = SpeechRecognitionAttemptResult(
                            text = "",
                            errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            engineId = OnDeviceChunkTranscriptionResult.DEFAULT_ON_DEVICE_ENGINE_ID,
                        )
                        recordExternalDiagnostic(
                            "fallback",
                            "Fallback from SPEECH_RECOGNIZER: SPEECH_RECOGNIZER is unsupported for ${language.value}",
                            true,
                        )
                    } else if (onDeviceAvailabilityChecker() || standardAvailabilityChecker() || attemptRunner != null) {
                        var osAttempt: SpeechRecognitionAttemptResult? = null
                        var lastCandidate = bcp47LocaleTag
                        for ((index, candidateTag) in candidates.withIndex()) {
                            val attempt = runAttemptWithTag(
                                pcm16LeBytes = pcm16LeBytes,
                                sampleRateHz = sampleRateHz,
                                bcp47LocaleTag = candidateTag,
                                preferOnDevice = preferOnDevice,
                                isProbe = isProbe,
                            )
                            osAttempt = attempt
                            lastCandidate = candidateTag
                            if (attempt?.errorCode != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                                if (index > 0 ||
                                    attempt?.text?.isNotBlank() == true ||
                                    attempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                                ) {
                                    rememberResolvedBcp47Tag(language?.value, candidateTag)
                                }
                                break
                            }
                            if (index + 1 < candidates.size) {
                                logInfo(
                                    "bcp47Fallback lang=${language?.value ?: bcp47LocaleTag} " +
                                        "unsupportedTag=$candidateTag nextTag=${candidates[index + 1]}",
                                )
                            }
                        }

                        if (osAttempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED && language != null && onDeviceAvailabilityChecker()) {
                            val support = try {
                                recognitionModelGateway.checkSupport(candidates)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                emptyMap()
                            }
                            val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
                            val pendingTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.PENDING }
                            val downloadableTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED }

                            when {
                                installedTag != null -> {
                                    rememberResolvedBcp47Tag(language.value, installedTag)
                                    clearModelIssue(language)
                                    return osAttempt
                                }

                                pendingTag != null -> {
                                    rememberResolvedBcp47Tag(language.value, pendingTag)
                                    setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                                    val unavailableAttempt = SpeechRecognitionAttemptResult(
                                        text = "",
                                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    )
                                    noteRecognitionAttemptOutcome(language, pendingTag, unavailableAttempt, OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER)
                                    return unavailableAttempt
                                }

                                downloadableTag != null -> {
                                    rememberResolvedBcp47Tag(language.value, downloadableTag)
                                    val unavailableAttempt = SpeechRecognitionAttemptResult(
                                        text = "",
                                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    )
                                    noteRecognitionAttemptOutcome(language, downloadableTag, unavailableAttempt, OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER)
                                    return unavailableAttempt
                                }
                            }
                        }

                        lastAttempt = osAttempt

                        noteRecognitionAttemptOutcome(language, lastCandidate, osAttempt, OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER)

                        if (osAttempt?.text?.isNotBlank() == true) {
                            return osAttempt
                        }

                        if (osAttempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            recordExternalDiagnostic("fallback", "Fallback from SPEECH_RECOGNIZER: SPEECH_RECOGNIZER is unavailable", true)
                        } else if (osAttempt?.errorCode != null) {
                            recordExternalDiagnostic("fallback", "Fallback from SPEECH_RECOGNIZER: SPEECH_RECOGNIZER failed with ${osAttempt.errorCode}", true)
                        } else {
                            recordExternalDiagnostic("fallback", "Fallback from SPEECH_RECOGNIZER: SPEECH_RECOGNIZER is not supported", true)
                        }
                    } else {
                        recordExternalDiagnostic("fallback", "Fallback from SPEECH_RECOGNIZER: SPEECH_RECOGNIZER is not available", true)
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX -> {
                    if (language != null) {
                        if (catalogEntry != null && catalogEntry.whisperCode == null) {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from SHERPA_ONNX: SHERPA_ONNX is unsupported for ${language.value}", true)
                        } else {
                            trySherpaInference(pcm16LeBytes, sampleRateHz, language)?.let { return it }
                            if (isSherpaSupportedFor(language) && !isSherpaInstalledFor(language)) {
                                requestSttModelDownload(language, com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX)
                                recordExternalDiagnostic("fallback", "Fallback from SHERPA_ONNX: SHERPA_ONNX is unavailable", true)
                                lastAttempt = SpeechRecognitionAttemptResult(
                                    text = "",
                                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                                )
                            } else {
                                recordExternalDiagnostic("fallback", "Fallback from SHERPA_ONNX: SHERPA_ONNX inference failed or unsupported", true)
                            }
                        }
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE,
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_SMALL,
                -> {
                    if (language != null) {
                        val model = engine.downloadableModel ?: continue
                        val engineId = if (engine == com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_WHISPER_BASE) {
                            SherpaOnnxWhisperTranscriber.SHERPA_ONNX_BASE_ENGINE_ID
                        } else {
                            SherpaOnnxWhisperTranscriber.SHERPA_ONNX_SMALL_ENGINE_ID
                        }
                        if (catalogEntry != null && catalogEntry.whisperCode == null) {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                engineId = engineId,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from ${engine.name}: unsupported for ${language.value}", true)
                        } else {
                            val whisperTranscriber = sherpaWhisperTranscriberInstance
                            if (whisperTranscriber != null && whisperTranscriber.isReadyForLanguage(language.value, model)) {
                                val prevHint = whisperTranscriber.activeContextHint
                                whisperTranscriber.activeContextHint = resolveEffectiveWhisperContextHint(language)
                                val res = try {
                                    runCatching {
                                        whisperTranscriber.transcribePcm16Le(
                                            pcm16LeBytes = pcm16LeBytes,
                                            sampleRateHz = sampleRateHz,
                                            language = language,
                                            model = model,
                                        )
                                    }.getOrNull()
                                } finally {
                                    whisperTranscriber.activeContextHint = prevHint
                                }
                                if (res != null && res.text.isNotBlank()) {
                                    clearModelIssue(language)
                                    return SpeechRecognitionAttemptResult(
                                        text = res.text.trim(),
                                        confidence = res.confidence,
                                        errorCode = null,
                                        engineId = res.engineId,
                                    )
                                }
                            } else {
                                lastAttempt = SpeechRecognitionAttemptResult(
                                    text = "",
                                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    engineId = engineId,
                                )
                                recordExternalDiagnostic("fallback", "Fallback from ${engine.name}: model not downloaded", true)
                            }
                        }
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE -> {
                    if (language != null) {
                        val senseVoice = senseVoiceTranscriberInstance
                        val model = com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL
                        if (!model.supportsLanguage(language.value)) {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                engineId = SherpaOnnxSenseVoiceTranscriber.SENSE_VOICE_ENGINE_ID,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from SENSE_VOICE: unsupported for ${language.value}", true)
                        } else if (senseVoice != null && senseVoice.isReadyForLanguage(language.value)) {
                            val res = runCatching {
                                senseVoice.transcribePcm16Le(
                                    pcm16LeBytes = pcm16LeBytes,
                                    sampleRateHz = sampleRateHz,
                                    channelCount = 1,
                                    language = language,
                                )
                            }.getOrNull()
                            if (res != null && res.text.isNotBlank()) {
                                clearModelIssue(language)
                                return SpeechRecognitionAttemptResult(
                                    text = res.text.trim(),
                                    confidence = res.confidence,
                                    errorCode = null,
                                    engineId = res.engineId,
                                )
                            }
                        } else {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                engineId = SherpaOnnxSenseVoiceTranscriber.SENSE_VOICE_ENGINE_ID,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from SENSE_VOICE: model not downloaded", true)
                        }
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE,
                -> {
                    if (language != null) {
                        val model = engine.downloadableModel ?: continue
                        val engineId = if (engine == com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_BASE) {
                            MoonshineOnnxTranscriber.MOONSHINE_BASE_ENGINE_ID
                        } else {
                            MoonshineOnnxTranscriber.MOONSHINE_TINY_ENGINE_ID
                        }
                        if (!model.supportsLanguage(language.value)) {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                engineId = engineId,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from ${engine.name}: only English supported", true)
                        } else {
                            val moonshine = moonshineTranscriberInstance
                            if (moonshine != null && moonshine.isReadyForLanguage(language.value, model)) {
                                val res = runCatching {
                                    moonshine.transcribePcm16Le(
                                        pcm16LeBytes = pcm16LeBytes,
                                        sampleRateHz = sampleRateHz,
                                        channelCount = 1,
                                        language = language,
                                        model = model,
                                    )
                                }.getOrNull()
                                if (res != null && res.text.isNotBlank()) {
                                    clearModelIssue(language)
                                    return SpeechRecognitionAttemptResult(
                                        text = res.text.trim(),
                                        confidence = res.confidence,
                                        errorCode = null,
                                        engineId = res.engineId,
                                    )
                                }
                            } else {
                                lastAttempt = SpeechRecognitionAttemptResult(
                                    text = "",
                                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    engineId = engineId,
                                )
                                recordExternalDiagnostic("fallback", "Fallback from ${engine.name}: model not downloaded", true)
                            }
                        }
                    }
                }

                com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API,
                com.miuraken.chunkflow.core.model.SttEngine.GROQ_API,
                com.miuraken.chunkflow.core.model.SttEngine.GEMINI_API,
                -> {
                    if (language != null) {
                        val cloud = cloudSpeechTranscriberInstance
                        val engineId = when (engine) {
                            com.miuraken.chunkflow.core.model.SttEngine.OPENAI_API -> CloudSpeechTranscriber.OPENAI_ENGINE_ID
                            com.miuraken.chunkflow.core.model.SttEngine.GROQ_API -> CloudSpeechTranscriber.GROQ_ENGINE_ID
                            else -> CloudSpeechTranscriber.GEMINI_ENGINE_ID
                        }
                        if (cloud != null && cloud.isEngineConfigured(engine)) {
                            val res = try {
                                cloud.transcribePcm16Le(
                                    engine = engine,
                                    pcm16LeBytes = pcm16LeBytes,
                                    sampleRateHz = sampleRateHz,
                                    channelCount = 1,
                                    language = language,
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                null
                            }
                            if (res != null && res.text.isNotBlank()) {
                                clearModelIssue(language)
                                return SpeechRecognitionAttemptResult(
                                    text = res.text.trim(),
                                    confidence = res.confidence,
                                    errorCode = null,
                                    engineId = res.engineId,
                                )
                            }
                        } else {
                            lastAttempt = SpeechRecognitionAttemptResult(
                                text = "",
                                errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                engineId = engineId,
                            )
                            recordExternalDiagnostic("fallback", "Fallback from ${engine.name}: API key not configured", true)
                        }
                    }
                }
            }
        }

        return lastAttempt ?: SpeechRecognitionAttemptResult(
            text = "",
            errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }

    private suspend fun runAttemptWithTag(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        val runner = attemptRunner
        return if (runner != null) {
            runner(pcm16LeBytes, sampleRateHz, bcp47LocaleTag, preferOnDevice)
        } else {
            runSinglePipeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = bcp47LocaleTag,
                preferOnDevice = preferOnDevice,
                isProbe = isProbe,
            )
        }
    }

    private suspend fun runSinglePipeAttempt(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        if (lastPipeAttemptCompletedRealtimeMs > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - lastPipeAttemptCompletedRealtimeMs
            if (elapsed in 0L until INTER_RECOGNITION_SETTLE_DELAY_MS) {
                delay(INTER_RECOGNITION_SETTLE_DELAY_MS - elapsed)
            }
        }

        val leadingSilenceMs = if (isProbe) {
            ChunkPcmExtractor.PROBE_LEADING_SILENCE_MS
        } else {
            ChunkPcmExtractor.LEADING_SILENCE_MS
        }
        val trailingSilenceMs = if (isProbe) {
            ChunkPcmExtractor.PROBE_TRAILING_SILENCE_MS
        } else {
            ChunkPcmExtractor.TRAILING_SILENCE_MS
        }
        val audioPaceMs = if (isProbe) PROBE_PIPE_AUDIO_PACE_MS else PIPE_AUDIO_PACE_MS
        val trailingPaceMs = if (isProbe) PROBE_PIPE_TRAILING_SILENCE_PACE_MS else PIPE_TRAILING_SILENCE_PACE_MS

        val paddedBytes = ChunkPcmExtractor.padPcmWithSilence(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            leadingSilenceMs = leadingSilenceMs,
            trailingSilenceMs = trailingSilenceMs,
        )
        val audioDurationMs = ((paddedBytes.size / 2).toLong() * 1_000L) / sampleRateHz.coerceAtLeast(1).toLong()
        val attemptTimeoutMs = ((audioDurationMs / 2L) + 4_500L).coerceIn(5_000L, RECOGNITION_TIMEOUT_MS)

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            return null
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val listeningStarted = CompletableDeferred<Boolean>()

        return try {
            coroutineScope {
                val writerJob = launch {
                    if (!listeningStarted.await()) return@launch
                    runCatching {
                        // 1 回の書き込みを PIPE_BUF 以下にする（POLLOUT のあとならブロックしない）
                        val frameBytes = ((sampleRateHz * 2 * PIPE_FRAME_DURATION_MS) / 1_000)
                            .coerceIn(MIN_PIPE_FRAME_BYTES, PipeWriteGuard.PIPE_BUF_BYTES)
                        val trailingSilenceBytes = ((sampleRateHz * trailingSilenceMs) / 1_000) * 2
                        val trailingStartOffset = (paddedBytes.size - trailingSilenceBytes).coerceAtLeast(0)

                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                            val writeFd = writeSide.fileDescriptor
                            var offset = 0
                            while (offset < paddedBytes.size) {
                                // 読み手（SpeechRecognizer）が読まないと write が永久にブロックするので、
                                // ブロックせずに書けることを確かめてから書く。読まれないまま時間が過ぎたら流すのをやめる
                                if (!PipeWriteGuard.awaitPipeWritable(writeFd)) {
                                    logInfo("pipeStalled lang=$bcp47LocaleTag writtenBytes=$offset totalBytes=${paddedBytes.size}")
                                    break
                                }
                                val len = min(frameBytes, paddedBytes.size - offset)
                                output.write(paddedBytes, offset, len)
                                offset += len
                                if (offset <= trailingStartOffset) {
                                    delay(audioPaceMs)
                                } else {
                                    delay(trailingPaceMs)
                                }
                            }
                            output.flush()
                        }
                    }
                }

                var timedOut = true
                val recognitionResult = withTimeoutOrNull(attemptTimeoutMs) {
                    val res = recognizeFromPipeOnMainLooper(
                        readSide = readSide,
                        sampleRateHz = sampleRateHz,
                        bcp47LocaleTag = bcp47LocaleTag,
                        preferOnDevice = preferOnDevice,
                        onListeningStarted = { started ->
                            listeningStarted.complete(started)
                        },
                    )
                    timedOut = false
                    res
                }
                if (timedOut) {
                    appendDiagnostic(
                        stage = "STT_TIMEOUT",
                        message = "SpeechRecognizer が ${attemptTimeoutMs}ms 以内に応答しませんでした (lang=$bcp47LocaleTag)",
                        isWarningOrError = true,
                    )
                }
                if (!listeningStarted.isCompleted) {
                    listeningStarted.complete(false)
                }
                writerJob.cancel()
                recognitionResult
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            lastPipeAttemptCompletedRealtimeMs = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun recognizeFromPipeOnMainLooper(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        onListeningStarted: (Boolean) -> Unit,
    ): SpeechRecognitionAttemptResult? = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var latestPartialText: String = ""

        val cleanup = Runnable {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }

        continuation.invokeOnCancellation {
            mainHandler.post(cleanup)
        }

        mainHandler.post {
            if (!continuation.isActive) {
                onListeningStarted(false)
                return@post
            }
            try {
                val created = if (preferOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = created

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = extractBestRecognitionText(partialResults)
                        if (partial.isNotEmpty()) {
                            latestPartialText = partial
                        }
                    }

                    override fun onError(error: Int) {
                        val outcome = resolveRecognitionCallbackOutcome(
                            resultsText = null,
                            confidence = null,
                            partialText = latestPartialText,
                            errorCode = error,
                        )
                        logInfo(
                            "onError preferOnDevice=$preferOnDevice lang=$bcp47LocaleTag error=$error fallbackChars=${outcome?.text?.length ?: 0}",
                        )
                        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            appendDiagnostic(
                                stage = "STT_ERROR",
                                message = "SpeechRecognizer エラー lang=$bcp47LocaleTag code=$error (${speechErrorName(error)})",
                                isWarningOrError = true,
                            )
                        }
                        cleanup.run()
                        if (continuation.isActive) {
                            continuation.resume(outcome)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val bestText = extractBestRecognitionText(results)
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val bestConfidence = scores?.firstOrNull()?.takeIf { it in 0.0f..1.0f }
                        val outcome = resolveRecognitionCallbackOutcome(
                            resultsText = bestText,
                            confidence = bestConfidence,
                            partialText = latestPartialText,
                            errorCode = null,
                        )
                        logInfo(
                            "onResults preferOnDevice=$preferOnDevice lang=$bcp47LocaleTag chars=${outcome?.text?.length ?: 0}",
                        )
                        cleanup.run()
                        if (continuation.isActive) {
                            continuation.resume(outcome)
                        }
                    }
                }

                created.setRecognitionListener(listener)
                val intent = buildRecognizerIntent(
                    readSide = readSide,
                    sampleRateHz = sampleRateHz,
                    bcp47LocaleTag = bcp47LocaleTag,
                    preferOnDevice = preferOnDevice,
                )
                created.startListening(intent)
                onListeningStarted(true)
            } catch (_: Exception) {
                onListeningStarted(false)
                cleanup.run()
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    internal fun buildRecognizerIntent(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47LocaleTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47LocaleTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
        val rawHint = activeTrialContextHint?.trim().orEmpty()
        if (rawHint.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val biasingPhrases = ArrayList<String>()
            for (part in rawHint.split("||")) {
                val trimmed = part.trim()
                val body = when {
                    trimmed.startsWith("BIAS:", ignoreCase = true) -> trimmed.substringAfter(":").trim()
                    trimmed.startsWith("PROMPT:", ignoreCase = true) -> trimmed.substringAfter(":").trim()
                    else -> trimmed
                }
                for (phrase in body.split(',', ';', '|')) {
                    val p = phrase.trim()
                    if (p.isNotEmpty() && p !in biasingPhrases) {
                        biasingPhrases.add(p)
                    }
                }
            }
            if (biasingPhrases.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasingPhrases)
            }
        }
    }

    private fun extractBestRecognitionText(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

    private fun logInfo(message: String) {
        runCatching {
            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, message)
            }
        }
    }

    private fun logDebug(message: String) {
        runCatching {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, message)
            }
        }
    }

    companion object {
        /** 端末が熱いか省電力モードのため、一括言語判定を並列にせず 1 件ずつ行うときに診断ログに出す文（計測ツールもこれで見分ける）。 */
        internal const val PARALLEL_LID_CONSTRAINED_MESSAGE: String = "端末の温度が高いか省電力モードのため、言語判定を 1 件ずつ実行"

        /**
         * 一括言語判定の音声の切り出しの方式の本番の既定（ADR 0010 §2.7、docs/spikes/S3-onnx-whisper-lid.md §10）。
         * Pixel 11 実機で 30 分教材全体が 23.4〜28.9 秒 → 4.9〜6.9 秒（4.2〜4.8 倍）になり、全チャンクで判定が一致した `STREAMING` を既定にする。
         */
        internal val DEFAULT_LID_EXTRACTION_METHOD: LidExtractionMethod = LidExtractionMethod.STREAMING

        /** 順に流す切り出しを指定されたが、チャンクが開始位置の順に並んでいないので並列の切り出しにしたときの診断ログの文。 */
        internal const val STREAMING_UNSORTED_MESSAGE: String = "チャンクが時刻の順に並んでいないため、順に流す切り出しの代わりに並列の切り出しを使う"

        /** 順に流す切り出しを指定されたが、対象のチャンクがまばらなので並列の切り出しにしたときの診断ログの文。 */
        internal const val STREAMING_SPARSE_MESSAGE: String = "対象のチャンクがまばらなため、順に流す切り出しの代わりに並列の切り出しを使う"

        /**
         * 一括言語判定の切り出しの方式と各段階の時間（[delta] は 1 回の一括判定の間の増分、[elapsedNanos] はその全体時間）を、診断ログの 1 行にする。
         *
         * 例: `切り出し: 方式 cur（60 件、全体 3.6 秒。1 件あたり 61.2 ms = 位置合わせ 1.2 + MediaCodec 作成 25.3 + デコード待ち 20.1 + …。MediaCodec 60 回）`
         * 並列の方式では複数のスレッドの時間を足すので、1 件あたりの合計が全体時間 ÷ 件数より長くなる。
         */
        internal fun describeLanguageOnlyExtraction(
            method: LidExtractionMethod,
            delta: LidExtractionTimingsSnapshot,
            elapsedNanos: Long,
        ): String {
            val count = if (method == LidExtractionMethod.STREAMING) delta.streamWindows + delta.extractions else delta.extractions
            val divisor = count.coerceAtLeast(1L).toDouble() * 1_000_000.0
            fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / divisor)
            return buildString {
                append("切り出し: 方式 ").append(method.label)
                append("（").append(count).append(" 件、全体 ")
                append(String.format(Locale.ROOT, "%.1f", elapsedNanos / 1_000_000_000.0)).append(" 秒。1 件あたり ")
                append(ms(delta.busyNanos)).append(" ms = ")
                append("抽出器 ").append(ms(delta.extractorOpenNanos))
                append(" + 位置合わせ ").append(ms(delta.positionNanos))
                append(" + MediaCodec 作成 ").append(ms(delta.codecCreateNanos))
                append(" + デコード待ち ").append(ms(delta.codecWaitNanos))
                append(" + モノラル化 ").append(ms(delta.mixNanos))
                append(" + リサンプル ").append(ms(delta.resampleOnlyNanos))
                append(" + PCM ").append(ms(delta.pcmNanos))
                append(" + 解放 ").append(ms(delta.codecReleaseNanos))
                append(" + 受け渡し ").append(ms(delta.handoffNanos))
                append(" + その他 ").append(ms(delta.otherNanos))
                append("。MediaCodec ").append(delta.codecCreates).append(" 回")
                if (method == LidExtractionMethod.STREAMING) {
                    append("、run ").append(delta.streamRuns).append("、窓 ").append(delta.streamWindows)
                    append("、待ち行列の待ち ").append(String.format(Locale.ROOT, "%.1f", delta.queueWaitNanos / 1_000_000.0)).append(" ms")
                }
                if (delta.fallbacks > 0L) append("、今の方式で切り出し直し ").append(delta.fallbacks).append(" 件")
                append("）")
            }
        }

        /**
         * 一括言語判定の推論エンジンと推論時間（[delta] は 1 回の一括判定の間の増分）を、診断ログの 1 行にする。
         *
         * 例:
         * - `推論エンジン: onnxruntime（58 件、推論 平均 31.2 ms/チャンク、log-mel 平均 4.8 ms、ONNX の読み込み 142 ms）`
         * - `推論エンジン: kotlin（58 件、推論 平均 826.0 ms/チャンク、log-mel 平均 4.8 ms）。onnxruntime を使えない: ONNX のファイルがない`
         */
        internal fun describeLanguageDetectionEngine(delta: LidInferenceStats): String {
            fun averageMs(nanos: Long, runs: Long): String = String.format(
                Locale.ROOT,
                "%.1f",
                if (runs > 0L) nanos.toDouble() / runs.toDouble() / 1_000_000.0 else 0.0,
            )
            val engines = when {
                delta.kotlinRuns == 0L ->
                    "${OnnxWhisperLidEngine.ENGINE_NAME}（${delta.onnxRuns} 件、" +
                        "推論 平均 ${averageMs(delta.onnxNanos, delta.onnxRuns)} ms/チャンク"

                delta.onnxRuns == 0L ->
                    "${WhisperTinyLanguageDetector.KOTLIN_ENGINE_NAME}（${delta.kotlinRuns} 件、" +
                        "推論 平均 ${averageMs(delta.kotlinNanos, delta.kotlinRuns)} ms/チャンク"

                else ->
                    "${OnnxWhisperLidEngine.ENGINE_NAME} ${delta.onnxRuns} 件と" +
                        "${WhisperTinyLanguageDetector.KOTLIN_ENGINE_NAME} ${delta.kotlinRuns} 件（" +
                        "推論 平均 ${averageMs(delta.onnxNanos, delta.onnxRuns)} ms / " +
                        "${averageMs(delta.kotlinNanos, delta.kotlinRuns)} ms/チャンク"
            }
            val mel = if (delta.melRuns > 0L) "、log-mel 平均 ${averageMs(delta.melNanos, delta.melRuns)} ms" else ""
            val load = delta.onnxLoadTimeMs?.takeIf { delta.onnxRuns > 0L }?.let { "、ONNX の読み込み $it ms" }.orEmpty()
            val reason = delta.onnxUnavailableReason?.takeIf { delta.kotlinRuns > 0L }
                ?.let { "。${OnnxWhisperLidEngine.ENGINE_NAME} を使えない: $it" }
                .orEmpty()
            return "推論エンジン: $engines$mel$load）$reason"
        }

        private const val LOG_TAG: String = "ChunkFlowSpeech"
        private const val PIPE_FRAME_DURATION_MS: Int = 20
        private const val MIN_PIPE_FRAME_BYTES: Int = 640
        private const val PIPE_AUDIO_PACE_MS: Long = 10L
        private const val PIPE_TRAILING_SILENCE_PACE_MS: Long = 15L
        private const val PROBE_PIPE_AUDIO_PACE_MS: Long = 8L
        private const val PROBE_PIPE_TRAILING_SILENCE_PACE_MS: Long = 10L
        internal const val INTER_RECOGNITION_SETTLE_DELAY_MS: Long = 200L
        private const val RECOGNITION_TIMEOUT_MS: Long = 12_000L
        private const val MAX_STT_CACHE_ENTRIES: Int = 2_048
        private const val MAX_DIAGNOSTIC_ENTRIES: Int = 40
        internal const val MIN_DETERMINED_LID_PROBABILITY: Float = 0.54f
        private const val AMBIGUOUS_LID_PROB_THRESHOLD: Float = 0.75f
        private const val DEFAULT_PRECOMPUTED_LID_CONFIDENCE: Float = 0.85f
        internal const val LID_ONLY_MAX_EXTRACT_DURATION_MS: Long = 2_800L
        private const val LOW_STT_CONFIDENCE_THRESHOLD: Float = 0.68f
        private const val MODERATE_STT_CONFIDENCE_THRESHOLD: Float = 0.80f
        private const val MODEL_ISSUE_REFRESH_MIN_INTERVAL_MS: Long = 15_000L

        private fun speechErrorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            else -> "ERROR_$error"
        }

        /**
         * `onResults` または `onError` コールバック時点で最終結果テキストと `onPartialResults` の保持テキストを統合する。
         *
         * 言語モデルが使えないエラー（`ERROR_LANGUAGE_UNAVAILABLE` / `ERROR_LANGUAGE_NOT_SUPPORTED`）は、
         * 呼び出し側で区別できるよう、本文が空でエラーコードを持つ結果として返す。
         */
        internal fun resolveRecognitionCallbackOutcome(
            resultsText: String?,
            confidence: Float?,
            partialText: String,
            errorCode: Int?,
        ): SpeechRecognitionAttemptResult? {
            val trimmedFinal = resultsText?.trim().orEmpty()
            if (trimmedFinal.isNotEmpty()) {
                return SpeechRecognitionAttemptResult(
                    text = trimmedFinal,
                    confidence = confidence,
                )
            }
            if (errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            ) {
                return SpeechRecognitionAttemptResult(text = "", errorCode = errorCode)
            }
            val trimmedPartial = partialText.trim()
            if (trimmedPartial.isEmpty()) return null

            return if (
                errorCode == null ||
                errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                SpeechRecognitionAttemptResult(
                    text = trimmedPartial,
                    confidence = null,
                )
            } else {
                null
            }
        }
    }
}

/**
 * `highPriority = true` の待機コルーチンが `highPriority = false`（バックグラウンド先読み）の待機コルーチンより
 * 必ず先にロックを取得できる優先度付き非同期排他ロック。
 */
internal class PriorityRecognitionLock {
    private val stateLock = Any()
    private var isLocked = false
    private val highPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()
    private val normalPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withLock(highPriority: Boolean, block: suspend () -> T): T {
        acquire(highPriority)
        return try {
            block()
        } finally {
            release()
        }
    }

    fun cancelWaiters(onlyLowPriority: Boolean) {
        val toCancel = mutableListOf<CompletableDeferred<Unit>>()
        synchronized(stateLock) {
            toCancel.addAll(normalPriorityQueue)
            normalPriorityQueue.clear()
            if (!onlyLowPriority) {
                toCancel.addAll(highPriorityQueue)
                highPriorityQueue.clear()
            }
        }
        val reason = CancellationException("Recognition lock waiter cancelled")
        for (waiter in toCancel) {
            waiter.cancel(reason)
        }
    }

    fun forceResetLock() {
        val toCancel = mutableListOf<CompletableDeferred<Unit>>()
        synchronized(stateLock) {
            toCancel.addAll(highPriorityQueue)
            toCancel.addAll(normalPriorityQueue)
            highPriorityQueue.clear()
            normalPriorityQueue.clear()
            isLocked = false
        }
        val reason = CancellationException("Recognition lock reset")
        for (waiter in toCancel) {
            waiter.cancel(reason)
        }
    }

    private suspend fun acquire(highPriority: Boolean) {
        val waiter: CompletableDeferred<Unit>? = synchronized(stateLock) {
            if (!isLocked && highPriorityQueue.isEmpty() && normalPriorityQueue.isEmpty()) {
                isLocked = true
                null
            } else {
                CompletableDeferred<Unit>().also { deferred ->
                    if (highPriority) {
                        highPriorityQueue.addLast(deferred)
                    } else {
                        normalPriorityQueue.addLast(deferred)
                    }
                }
            }
        }
        if (waiter == null) return
        try {
            waiter.await()
        } catch (ce: CancellationException) {
            var acquiredBeforeCancel = false
            synchronized(stateLock) {
                val removed = if (highPriority) {
                    highPriorityQueue.remove(waiter)
                } else {
                    normalPriorityQueue.remove(waiter)
                }
                if (!removed && waiter.isCompleted) {
                    acquiredBeforeCancel = true
                }
            }
            if (acquiredBeforeCancel) {
                release()
            }
            throw ce
        }
    }

    private fun release() {
        while (true) {
            val next: CompletableDeferred<Unit>? = synchronized(stateLock) {
                val waiter = highPriorityQueue.removeFirstOrNull()
                    ?: normalPriorityQueue.removeFirstOrNull()
                if (waiter == null) {
                    isLocked = false
                    null
                } else {
                    waiter
                }
            }
            if (next == null) return
            if (next.complete(Unit)) return
        }
    }
}

/**
 * 16-bit signed LE モノラル PCM 音声に対し、ピッチを維持したまま再生速度を伸縮する（WSOLA: Waveform Similarity Overlap-Add）。
 *
 * `speed` が `1.0f` 付近（±0.02 未満）の場合は元のバイト配列をそのまま返す。
 * `speed > 1.0f` で早回し（再生時間が短くなる）、`speed < 1.0f` でスロー（再生時間が長くなる）。
 */
internal fun timeStretchPcm16LeBytes(
    pcm16LeBytes: ByteArray,
    sampleRateHz: Int,
    speed: Float,
): ByteArray {
    val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
    if (abs(clampedSpeed - 1.0f) < 0.02f || sampleRateHz <= 0 || pcm16LeBytes.size < 4) {
        return pcm16LeBytes
    }
    val input = ChunkPcmExtractor.pcm16LeBytesToShorts(pcm16LeBytes)
    val windowSamples = (sampleRateHz * 30 / 1000).coerceAtLeast(64)
    if (input.size < windowSamples * 2) {
        return pcm16LeBytes
    }
    val synthesisHop = windowSamples / 2
    val searchRadius = (sampleRateHz * 10 / 1000).coerceAtLeast(16)
    val outputSamples = (input.size / clampedSpeed).roundToInt().coerceAtLeast(windowSamples)

    val hannWindow = FloatArray(windowSamples) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (windowSamples - 1)))).toFloat()
    }
    val outAccum = FloatArray(outputSamples)
    val weightAccum = FloatArray(outputSamples)

    var prevInputPos = 0
    var outPos = 0
    var isFirstFrame = true

    while (outPos < outputSamples) {
        val targetInPos = (outPos * clampedSpeed).roundToInt().coerceIn(0, input.size - windowSamples)
        val bestInPos = if (isFirstFrame) {
            isFirstFrame = false
            targetInPos
        } else {
            val naturalNextPos = (prevInputPos + synthesisHop).coerceIn(0, input.size - windowSamples)
            val searchStart = (targetInPos - searchRadius).coerceAtLeast(0)
            val searchEnd = (targetInPos + searchRadius).coerceAtMost(input.size - windowSamples)
            var bestCandidate = targetInPos
            var bestScore = Float.NEGATIVE_INFINITY
            val compareLen = min(synthesisHop, windowSamples)
            for (candidate in searchStart..searchEnd) {
                var dot = 0f
                var candEnergy = 0f
                for (k in 0 until compareLen) {
                    val a = input[naturalNextPos + k].toFloat()
                    val b = input[candidate + k].toFloat()
                    dot += a * b
                    candEnergy += b * b
                }
                val score = if (candEnergy > 1f) dot / sqrt(candEnergy) else 0f
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }
            bestCandidate
        }

        val copyLen = min(windowSamples, outputSamples - outPos)
        for (i in 0 until copyLen) {
            val w = hannWindow[i]
            outAccum[outPos + i] += input[bestInPos + i].toFloat() * w
            weightAccum[outPos + i] += w
        }
        prevInputPos = bestInPos
        outPos += synthesisHop
    }

    val outBytes = ByteArray(outputSamples * 2)
    val byteBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until outputSamples) {
        val w = weightAccum[i]
        val sampleFloat = if (w > 1e-4f) outAccum[i] / w else 0f
        val clampedShort = sampleFloat.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        byteBuffer.putShort(clampedShort)
    }
    return outBytes
}
