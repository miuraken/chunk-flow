package com.miuraken.chunkflow.core.playback

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.Transcript
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [ExoPlayer] の [PlayerMessage] を用いてチャンク境界イベントを予約・処理するスケジューラ（§7.2.4、ADR 0007）。
 *
 * - `createMessage` は [ExoPlayer] 固有の API のため、[LearningPlayer] ではなく内側の [ExoPlayer] を直接保持する。
 * - 常に最大 1 件の境界 [PlayerMessage] のみを予約し、再予約の前に必ず前回のメッセージを `cancel()` する。
 * - [LoopMode.AbRange] では `setDeleteAfterDelivery(false)` を用いてメッセージを 1 件使い回す（ADR 0007）。
 * - 自身の `seekTo()` による `onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)` とユーザの手動シークを
 *   整数カウンタ [pendingSelfSeekCount] で区別し、自身のシークではリピート回数をリセットしない（ADR 0007）。
 * - 境界での優先順位は `AbRange > SingleChunk / ChunkRange > LanguageFilter` とし、
 *   [AutoPause] はリピートや言語フィルタのシークを実行する前にチャンク終端で適用する（§7.2.4）。
 * - 自動ポーズ中は画面オフでの CPU スリープによる再開遅延を防ぐため、`actualPauseMs + 5_000ms` のタイムアウト付き
 *   [PowerManager.PARTIAL_WAKE_LOCK] を保持し、再開またはユーザ操作によるキャンセル時に解放する（§7.2.4）。
 * - 全体設定「ベース言語のみ別の速度で再生する」が有効なときは、ベース言語のチャンクに入ったら速度をその速度に切り替え、
 *   ほかのチャンクでは利用者が選んだ速度（[userSpeed]）に戻す。
 */
@OptIn(UnstableApi::class)
class ChunkScheduler(
    private val exoPlayer: ExoPlayer,
    private val practiceController: PracticeController,
    private val analysisRepository: AnalysisRepository? = null,
    private val libraryRepository: LibraryRepository? = null,
    powerManager: PowerManager? = null,
    private val mainLooper: Looper = exoPlayer.applicationLooper,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val onStateChanged: (currentChunkId: ChunkId?, autoPauseEndsAtMs: Long?) -> Unit = { _, _ -> },
    private val onFilePlaybackEnded: () -> Unit = {},
) : Player.Listener {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var mediaObservationJob: Job? = null

    private val mainHandler = Handler(mainLooper)

    private val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        WAKE_LOCK_TAG,
    )?.apply {
        setReferenceCounted(false)
    }

    private var chunks: List<Chunk> = emptyList()
    private var transcripts: Map<ChunkId, Transcript> = emptyMap()
    private var bookmarks: List<Bookmark> = emptyList()
    private var languagePair: LanguagePair? = null
    private var practiceSettings: PracticeSettings = practiceController.settings.value

    /**
     * 利用者が選んだ再生速度（1x・もうひとつの再生速度）。ベース言語以外のチャンクはこの速度で再生する。
     * ベース言語の速度を当てているあいだも、UI に出す速度や保存する速度にはこの値を使う。
     */
    var userSpeed: Float = exoPlayer.playbackParameters.speed
        private set

    /** ベース言語のチャンクだけに使う速度（全体設定「ベース言語のみ別の速度で再生する」）。`null` なら使わない。 */
    private var baseLanguageSpeed: Float? = null

    /** いまベース言語の速度を当てているかどうか。`true` のあいだ、ExoPlayer の速度は [userSpeed] と違うことがある。 */
    var isBaseLanguageSpeedApplied: Boolean = false
        private set

    private var pendingMessage: PlayerMessage? = null
    private var pendingMessagePayload: BoundaryPayload? = null

    /**
     * [ChunkScheduler] 自身が発行し、まだ `onPositionDiscontinuity` で消費されていないシークの件数（ADR 0007）。
     */
    var pendingSelfSeekCount: Int = 0
        private set

    /**
     * [LoopMode.SingleChunk] または [LoopMode.ChunkRange] で完了したリピート回数。
     */
    var completedLoopCount: Int = 0
        private set

    internal var singleChunkPlaybackId: ChunkId? = null

    /** 単一チャンク再生モード（停止中のチャンククリック再生）が有効かどうか。 */
    val isSingleChunkPlaybackActive: Boolean
        get() = singleChunkPlaybackId != null

    private var isUpdatingLoopInternally: Boolean = false
    private var isHandlingAutoPause: Boolean = false
    private var isResumingFromAutoPause: Boolean = false
    private var autoPauseResumeRunnable: Runnable? = null
    private var autoPausedChunk: Chunk? = null
    private var pendingPostPauseAction: PostChunkAction? = null
    private var lastAutoPausedChunkEndMs: Long? = null
    private var pausedAtChunkEndPositionMs: Long? = null
    private var shouldRestartSelectedChunksOnPlay: Boolean = true

    private val _currentChunkId = MutableStateFlow<ChunkId?>(null)

    /** 現在のチャンク ID。チャンク境界およびシーク時に更新される。 */
    val currentChunkId: StateFlow<ChunkId?> = _currentChunkId.asStateFlow()

    private val _autoPauseEndsAtMs = MutableStateFlow<Long?>(null)

    /** 自動ポーズが終了する予定時刻（`elapsedRealtime` 基準）。自動ポーズ中以外は `null`。 */
    val autoPauseEndsAtMs: StateFlow<Long?> = _autoPauseEndsAtMs.asStateFlow()

    /** 自動ポーズ用の [PowerManager.PARTIAL_WAKE_LOCK] が現在保持されているかどうか。 */
    val isWakeLockHeld: Boolean
        get() = wakeLock?.isHeld == true

    init {
        exoPlayer.addListener(this)
        scope.launch {
            practiceController.settings.collect { newSettings ->
                onPracticeSettingsUpdated(newSettings)
            }
        }
        reschedule()
    }

    /**
     * 指定した [mediaId] のチャンク・文字起こし・言語ペアの購読を開始する。
     */
    fun bindMedia(mediaId: MediaId?, initialLanguagePair: LanguagePair? = null) {
        mediaObservationJob?.cancel()
        mediaObservationJob = null
        cancelAutoPause()
        completedLoopCount = 0
        singleChunkPlaybackId = null
        pausedAtChunkEndPositionMs = null
        shouldRestartSelectedChunksOnPlay = true
        languagePair = initialLanguagePair
        chunks = emptyList()
        transcripts = emptyMap()
        bookmarks = emptyList()
        cancelPendingMessage()
        // 前の教材のチャンクの言語でベース言語の速度が残らないよう、利用者の速度に戻す
        applyEffectiveSpeed()

        if (mediaId == null) {
            updateState(chunkId = null, autoPauseEndsAt = null)
            return
        }

        val analysisRepo = analysisRepository ?: return
        mediaObservationJob = scope.launch {
            launch {
                analysisRepo.observeChunks(mediaId).collect { newChunks ->
                    setChunks(newChunks)
                }
            }
            launch {
                analysisRepo.observeTranscripts(mediaId).collect { newTranscripts ->
                    setTranscripts(newTranscripts)
                }
            }
            launch {
                analysisRepo.observeBookmarks(mediaId).collect { newBookmarks ->
                    setBookmarks(newBookmarks)
                }
            }
            libraryRepository?.let { libRepo ->
                launch {
                    libRepo.observeItem(mediaId).collect { item ->
                        if (item != null) {
                            setLanguagePair(item.languagePair)
                        }
                    }
                }
            }
        }
    }

    /**
     * 現在有効なチャンク一覧を更新し、次の境界予約を再計算する。
     */
    fun setChunks(newChunks: List<Chunk>) {
        val sorted = newChunks.sortedBy { it.startMs }
        if (chunks == sorted && pendingMessage != null) return
        chunks = sorted
        val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        updateState(
            chunkId = findChunkAt(nowMs)?.id,
            autoPauseEndsAt = _autoPauseEndsAtMs.value,
        )
        reschedule()
    }

    /**
     * チャンクごとの文字起こしマップを更新する。
     */
    fun setTranscripts(newTranscripts: Map<ChunkId, Transcript>) {
        if (transcripts == newTranscripts) return
        transcripts = newTranscripts
        applyEffectiveSpeed()
        if (practiceSettings.languageFilter != LanguageFilter.ALL &&
            practiceSettings.languageFilter != LanguageFilter.CHECKED_ONLY
        ) {
            reschedule()
        }
    }

    /**
     * 教材のブックマーク一覧を更新する（F8.1）。
     */
    fun setBookmarks(newBookmarks: List<Bookmark>) {
        if (bookmarks == newBookmarks) return
        bookmarks = newBookmarks
        reschedule()
    }

    /**
     * 教材の言語ペア（学習言語・母語）を更新する。
     */
    fun setLanguagePair(newLanguagePair: LanguagePair?) {
        if (languagePair == newLanguagePair) return
        languagePair = newLanguagePair
        reschedule()
        applyEffectiveSpeed()
    }

    /**
     * 利用者が選んだ再生速度を設定する。ベース言語の速度を当てているチャンクの再生中は、そのチャンクが終わるまで
     * ベース言語の速度のままにする。
     *
     * 速度の変更は、すべてこの関数（または [LearningPlayer] の速度のコマンド）を通すこと。内側の [ExoPlayer] の速度を
     * 直接変えると、次のチャンクの境界で [userSpeed] に戻される。
     */
    fun setUserSpeed(speed: Float) {
        userSpeed = speed
        applyEffectiveSpeed(userSpeedChanged = true)
    }

    /**
     * ベース言語（言語ペアの `base`）のチャンクだけに使う速度を設定する。`null` ならベース言語のチャンクも利用者の速度で再生する。
     */
    fun setBaseLanguageSpeed(speed: Float?) {
        if (baseLanguageSpeed == speed) return
        baseLanguageSpeed = speed
        applyEffectiveSpeed()
    }

    /**
     * 再生停止中にチャンクリストの項目（または拡大波形のチャンク）がタップされた際、指定した [chunkId] のチャンクのみを再生する。
     *
     * - 「リピート」が ON で、対象チャンクが選択されている（[PracticeSettings.repeatChunkIds]）場合は、ユーザが停止するまで
     *   そのチャンクのみを繰り返す。
     * - それ以外の場合は、そのチャンクを 1 回だけ再生して終端で停止する。
     */
    fun startSingleChunkPlayback(chunkId: ChunkId) {
        val targetChunk = chunks.firstOrNull { it.id == chunkId } ?: return
        cancelAutoPause()
        cancelPendingMessage()
        completedLoopCount = 0
        pausedAtChunkEndPositionMs = null
        shouldRestartSelectedChunksOnPlay = true
        singleChunkPlaybackId = targetChunk.id
        updateState(chunkId = targetChunk.id, autoPauseEndsAt = null)
        seekInternal(targetChunk.startMs)
        scheduleBoundaryMessage(
            payload = createChunkEndPayload(targetChunk),
            persistent = false,
        )
        exoPlayer.play()
    }

    /**
     * 複数チャンク選択中に特定の選択チャンク [chunkId] から順次再生を開始する。
     */
    fun playFromSelectedChunk(chunkId: ChunkId) {
        val targetChunk = chunks.firstOrNull { it.id == chunkId } ?: return
        cancelAutoPause()
        cancelPendingMessage()
        completedLoopCount = 0
        singleChunkPlaybackId = null
        pausedAtChunkEndPositionMs = null
        shouldRestartSelectedChunksOnPlay = false
        updateState(chunkId = targetChunk.id, autoPauseEndsAt = null)
        seekInternal(targetChunk.startMs)
        scheduleBoundaryMessage(
            payload = createChunkEndPayload(targetChunk),
            persistent = false,
        )
        exoPlayer.play()
    }

    /**
     * ユーザの手動による再生・一時停止操作を通知する。
     * - 自動ポーズ中であれば即座にタイマーを取り消す（§7.2.4）。
     * - 停止状態から再生を開始する際、現在位置が非選択チャンク上や最終選択チャンク終端にある場合は、
     *   `exoPlayer.setPlayWhenReady(true)` の前にシークを先行発行してデコード済みバッファの音声漏れを防ぐ。
     */
    fun onUserPlayPauseCommand(playWhenReady: Boolean = true) {
        singleChunkPlaybackId = null
        if (_autoPauseEndsAtMs.value != null && !isHandlingAutoPause && !isResumingFromAutoPause) {
            resumeFromAutoPauseEarly(ensurePlaying = false)
            return
        }
        if (playWhenReady && !exoPlayer.playWhenReady && _autoPauseEndsAtMs.value == null) {
            preparePlaybackOnPlayStart(forceEvaluateWhenStopped = true)
        }
    }

    /**
     * 再生中に現在の再生位置と予約済み境界を照合し、万一 [PlayerMessage] の配送が遅延またはスキップされた場合や
     * A-B リピート中に複数チャンクを跨いだ場合に [currentChunkId] と次の境界予約を同期する。
     */
    fun syncPlaybackPosition() {
        if (!exoPlayer.playWhenReady || _autoPauseEndsAtMs.value != null) return
        if (chunks.isEmpty()) return
        val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (practiceSettings.loop is LoopMode.AbRange) {
            val activeId = findChunkAt(nowMs)?.id
            if (activeId != null && activeId != _currentChunkId.value) {
                updateState(
                    chunkId = activeId,
                    autoPauseEndsAt = _autoPauseEndsAtMs.value,
                    flushBufferOnSpeedChange = true,
                )
            }
            return
        }
        val payload = pendingMessagePayload
        if (payload != null) {
            if (nowMs >= payload.boundaryMs + MISSED_BOUNDARY_THRESHOLD_MS) {
                cancelPendingMessage()
                onBoundaryDelivered(payload)
            }
            return
        }
        if (singleChunkPlaybackId == null) {
            val activeChunk = chunks.firstOrNull { nowMs in it.startMs until it.endMs }
            if (activeChunk != null) {
                if (_currentChunkId.value != activeChunk.id) {
                    updateState(
                        chunkId = activeChunk.id,
                        autoPauseEndsAt = _autoPauseEndsAtMs.value,
                        flushBufferOnSpeedChange = true,
                    )
                }
                reschedule()
            }
        }
    }

    /**
     * 予約済みの [PlayerMessage] と自動ポーズタイマーを取り消し、リスナーとコルーチンスコープを解放する。
     */
    fun release() {
        mediaObservationJob?.cancel()
        mediaObservationJob = null
        cancelAutoPause()
        cancelPendingMessage()
        exoPlayer.removeListener(this)
        scope.cancel()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return

        val newPosMs = newPosition.positionMs.coerceAtLeast(0L)
        if (pendingSelfSeekCount > 0) {
            pendingSelfSeekCount -= 1
            return
        }

        // ユーザによる手動シーク: リピート回数と単一チャンク再生をリセットし、自動ポーズ中なら解除して次の境界を予約し直す（§7.2.4、ADR 0007）
        completedLoopCount = 0
        singleChunkPlaybackId = null
        pausedAtChunkEndPositionMs = null
        if (!exoPlayer.playWhenReady) {
            shouldRestartSelectedChunksOnPlay = true
        }
        if (_autoPauseEndsAtMs.value != null) {
            cancelAutoPause()
        }
        cancelPendingMessage()
        updateState(
            chunkId = findChunkAt(newPosMs)?.id,
            autoPauseEndsAt = null,
        )
        reschedule()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (isHandlingAutoPause || isResumingFromAutoPause) return
        if (!playWhenReady) {
            singleChunkPlaybackId = null
        }
        if (_autoPauseEndsAtMs.value != null) {
            resumeFromAutoPauseEarly(ensurePlaying = playWhenReady)
            return
        }
        if (playWhenReady) {
            preparePlaybackOnPlayStart(forceEvaluateWhenStopped = false)
        }
    }

    /**
     * 再生開始時に現在位置が非選択チャンク上や最終選択チャンク終端付近にある場合、再生対象のチャンク先頭へシークして境界を予約する。
     *
     * @param forceEvaluateWhenStopped `LearningPlayer.play()` により `exoPlayer.playWhenReady` が `true` になる直前に呼ばれた場合は `true`。
     */
    private fun preparePlaybackOnPlayStart(forceEvaluateWhenStopped: Boolean) {
        // 「☑ のみ」（LanguageFilter.CHECKED_ONLY）を選択しているときは、選択変更後・手動シーク後・選択範囲外・最後の選択チャンク終端付近から再生を始めたら最初の選択チャンクへ移る（リピートの ON / OFF によらない）
        if (singleChunkPlaybackId == null &&
            _autoPauseEndsAtMs.value == null &&
            practiceSettings.loop !is LoopMode.AbRange &&
            practiceSettings.loop !is LoopMode.ChunkRange
        ) {
            val repeatChunks = activeRepeatChunks()
            if (repeatChunks.isNotEmpty()) {
                val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val lastRepeatChunk = repeatChunks.last()
                val lastRepeatEndMs = repeatChunkEffectiveEndMs(lastRepeatChunk)
                val nearLastChunkEndThresholdMs = max(
                    lastRepeatChunk.startMs + 1L,
                    lastRepeatEndMs - END_OF_CHUNK_MARGIN_MS,
                )
                val isAtEndOfLastRepeatChunk =
                    nowMs > lastRepeatChunk.startMs &&
                        (
                            pausedAtChunkEndPositionMs == nowMs ||
                                nowMs in nearLastChunkEndThresholdMs..lastRepeatEndMs
                            )
                val isOutsideRepeatChunks = repeatChunks.none {
                    isPositionWithinRepeatChunkOrSilence(it, nowMs)
                }
                val shouldStartFromFirst =
                    shouldRestartSelectedChunksOnPlay || isOutsideRepeatChunks || isAtEndOfLastRepeatChunk
                pausedAtChunkEndPositionMs = null
                shouldRestartSelectedChunksOnPlay = false
                if (shouldStartFromFirst) {
                    val target = repeatChunks.first()
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    seekInternal(target.startMs)
                    scheduleBoundaryMessage(
                        payload = createChunkEndPayload(target),
                        persistent = false,
                    )
                    return
                }
                reschedule(assumePlaying = forceEvaluateWhenStopped)
                return
            }
            pausedAtChunkEndPositionMs = null
            shouldRestartSelectedChunksOnPlay = false
            reschedule(
                wrapToFirstMatchingIfNoNext = true,
                assumePlaying = forceEvaluateWhenStopped,
            )
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && practiceSettings.repeatWholeFile) {
            singleChunkPlaybackId = null
            pausedAtChunkEndPositionMs = null
            if (_autoPauseEndsAtMs.value != null) {
                cancelAutoPause()
            }
            val firstChunk = chunks.firstOrNull { !it.isSkipped } ?: chunks.firstOrNull()
            val targetMs = if (practiceSettings.skipOriginalSilence && firstChunk != null) {
                firstChunk.startMs
            } else {
                0L
            }
            updateState(chunkId = findChunkAt(targetMs)?.id, autoPauseEndsAt = null)
            seekInternal(targetMs)
            exoPlayer.play()
            reschedule()
            return
        }
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            singleChunkPlaybackId = null
            pausedAtChunkEndPositionMs = null
            shouldRestartSelectedChunksOnPlay = true
            if (_autoPauseEndsAtMs.value != null) {
                cancelAutoPause()
            }
            if (playbackState == Player.STATE_ENDED) {
                onFilePlaybackEnded()
            }
        } else if (playbackState == Player.STATE_READY) {
            val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (_currentChunkId.value == null && chunks.isNotEmpty()) {
                updateState(
                    chunkId = findChunkAt(nowMs)?.id,
                    autoPauseEndsAt = _autoPauseEndsAtMs.value,
                )
            }
            if (pendingMessage == null && _autoPauseEndsAtMs.value == null) {
                reschedule()
            }
        }
    }

    private fun onPracticeSettingsUpdated(newSettings: PracticeSettings) {
        val oldLoop = practiceSettings.loop
        val oldAutoPause = practiceSettings.autoPause
        val oldRepeatChunkIds = practiceSettings.repeatChunkIds
        val oldRepeatCheckedOnly = practiceSettings.repeatCheckedOnly
        val oldRepeatWholeFile = practiceSettings.repeatWholeFile
        val oldLanguageFilter = practiceSettings.languageFilter
        practiceSettings = newSettings

        val isCheckedOnly = newSettings.languageFilter == LanguageFilter.CHECKED_ONLY
        if (newSettings.repeatWholeFile ||
            (isCheckedOnly && newSettings.repeatChunkIds.size >= 2) ||
            (isCheckedOnly && newSettings.temporaryRepeatChunkId != null && newSettings.repeatCheckedOnly)
        ) {
            singleChunkPlaybackId = null
        }

        if ((oldRepeatChunkIds != newSettings.repeatChunkIds || oldLanguageFilter != newSettings.languageFilter) &&
            !exoPlayer.playWhenReady &&
            _autoPauseEndsAtMs.value == null
        ) {
            shouldRestartSelectedChunksOnPlay = true
        }

        if (!isUpdatingLoopInternally && oldLoop != newSettings.loop) {
            completedLoopCount = 0
        }

        if (_autoPauseEndsAtMs.value != null) {
            if (oldRepeatChunkIds != newSettings.repeatChunkIds ||
                oldRepeatCheckedOnly != newSettings.repeatCheckedOnly ||
                oldRepeatWholeFile != newSettings.repeatWholeFile ||
                oldLanguageFilter != newSettings.languageFilter
            ) {
                autoPausedChunk?.let { chunk ->
                    pendingPostPauseAction = determinePostChunkAction(chunk)
                }
            }
            if (oldAutoPause != AutoPause.Off && newSettings.autoPause == AutoPause.Off) {
                resumeFromAutoPauseEarly(ensurePlaying = true)
                return
            }
        }

        if (!isUpdatingLoopInternally &&
            exoPlayer.playWhenReady &&
            singleChunkPlaybackId == null &&
            autoPauseResumeRunnable == null &&
            _autoPauseEndsAtMs.value == null &&
            newSettings.loop !is LoopMode.AbRange &&
            newSettings.loop !is LoopMode.ChunkRange
        ) {
            val repeatChunks = activeRepeatChunks(newSettings)
            val switchedToCheckedOnly =
                oldLanguageFilter != newSettings.languageFilter && newSettings.languageFilter == LanguageFilter.CHECKED_ONLY
            if (repeatChunks.size >= 2 || (switchedToCheckedOnly && repeatChunks.isNotEmpty())) {
                val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (repeatChunks.none { isPositionWithinRepeatChunkOrSilence(it, nowMs, newSettings) }) {
                    val target = repeatChunks.firstOrNull { it.startMs >= nowMs } ?: repeatChunks.first()
                    shouldRestartSelectedChunksOnPlay = false
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    seekInternal(target.startMs)
                    scheduleBoundaryMessage(
                        payload = createChunkEndPayload(target),
                        persistent = false,
                    )
                    return
                }
            }
        }

        if (!isUpdatingLoopInternally) {
            reschedule()
        }
    }

    /**
     * 現在の状態・位置から次に発火すべき境界を計算し、[PlayerMessage] を 1 件だけ予約する。
     *
     * @param minBoundaryAfterMs 直前に処理し終えたチャンク終端の時刻（シークを伴わずに次のチャンクへ進む場合に同じ終端を再予約しないため）。
     * @param wrapToFirstMatchingIfNoNext フィルタ再生開始時に現在位置以降に該当チャンクがない場合、先頭の該当チャンクへ戻るかどうか。
     */
    internal fun reschedule(
        minBoundaryAfterMs: Long? = null,
        wrapToFirstMatchingIfNoNext: Boolean = false,
        assumePlaying: Boolean = false,
    ) {
        // 自動ポーズのタイマー待機中は再開時に reschedule するため新規予約しない
        if (_autoPauseEndsAtMs.value != null) {
            cancelPendingMessage()
            return
        }

        val isPlayingOrStarting = exoPlayer.playWhenReady || assumePlaying
        val loopMode = practiceSettings.loop
        // 最優先: A-B リピート（チャンクが空でも動作する。§7.2.4、ADR 0007）
        if (loopMode is LoopMode.AbRange) {
            val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (chunks.isNotEmpty()) {
                updateState(
                    chunkId = findChunkAt(nowMs)?.id,
                    autoPauseEndsAt = _autoPauseEndsAtMs.value,
                )
            }
            scheduleBoundaryMessage(
                payload = BoundaryPayload.AbEnd(
                    startMs = loopMode.startMs,
                    boundaryMs = loopMode.endMs,
                ),
                persistent = true,
            )
            return
        }

        // チャンクがない間はチャンクを前提とするモードを無効化する（§7.2.4）
        if (chunks.isEmpty()) {
            cancelPendingMessage()
            updateState(chunkId = null, autoPauseEndsAt = null)
            return
        }

        val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val filter = practiceSettings.languageFilter
        val useSkipFilter = chunks.any { it.isSkipped }
        val useLanguageFilter = filter != LanguageFilter.ALL && isLanguageFilterApplicable(filter)
        val useBookmarkFilter = practiceSettings.bookmarksOnly && chunks.any(::isChunkBookmarked)
        val repeatChunks = activeRepeatChunks()
        val hasSelectedRepeatChunks = repeatChunks.isNotEmpty()
        if (singleChunkPlaybackId == null &&
            loopMode !is LoopMode.ChunkRange &&
            hasSelectedRepeatChunks
        ) {
            val effectivePosMs = if (minBoundaryAfterMs != null) max(nowMs, minBoundaryAfterMs) else nowMs
            val activeRepeatChunk = repeatChunks.firstOrNull {
                val endBoundaryMs = effectiveChunkEndMs(it)
                effectivePosMs in it.startMs until endBoundaryMs &&
                    (minBoundaryAfterMs == null || endBoundaryMs > minBoundaryAfterMs)
            }
            if (activeRepeatChunk != null) {
                updateState(chunkId = activeRepeatChunk.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(activeRepeatChunk),
                    persistent = false,
                )
                return
            }
            val ownerIndex = chunks.indexOfLast { it.startMs <= effectivePosMs }
            val owner = chunks.getOrNull(ownerIndex)
            if (!practiceSettings.skipOriginalSilence &&
                owner != null &&
                repeatChunks.any { it.id == owner.id }
            ) {
                val nextChunk = chunks.getOrNull(ownerIndex + 1)
                if (nextChunk != null) {
                    val selectedIndex = repeatChunks.indexOfFirst { it.id == owner.id }
                    val nextRepeatChunk = if (selectedIndex >= 0) {
                        repeatChunks.getOrNull(selectedIndex + 1)
                    } else {
                        null
                    }
                    if (nextRepeatChunk != null && nextRepeatChunk.id == nextChunk.id) {
                        if (nextChunk.startMs <= nowMs) {
                            updateState(
                                chunkId = nextChunk.id,
                                autoPauseEndsAt = _autoPauseEndsAtMs.value,
                                flushBufferOnSpeedChange = isPlayingOrStarting,
                            )
                            scheduleBoundaryMessage(
                                payload = createChunkEndPayload(nextChunk),
                                persistent = false,
                            )
                        } else {
                            updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                            prepareSpeedForUpcomingChunk(nextChunk, isPlayingOrStarting)
                            scheduleBoundaryMessage(
                                payload = BoundaryPayload.ChunkStart(nextChunk),
                                persistent = false,
                            )
                        }
                        return
                    }
                    val ownerEndMs = effectiveChunkEndMs(owner)
                    val silenceEndMs = trailingSilenceEndMs(owner, nextChunk)
                    if (silenceEndMs > ownerEndMs && effectivePosMs < silenceEndMs) {
                        updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                        scheduleBoundaryMessage(
                            payload = BoundaryPayload.PostChunkSilenceEnd(
                                chunk = owner,
                                boundaryMs = silenceEndMs,
                            ),
                            persistent = false,
                        )
                        return
                    }
                }
            }
            if (isPlayingOrStarting) {
                val nextTarget = repeatChunks.firstOrNull { it.startMs >= effectivePosMs }
                    ?: if (practiceSettings.repeatCheckedOnly || wrapToFirstMatchingIfNoNext) {
                        repeatChunks.first()
                    } else {
                        null
                    }
                if (nextTarget != null) {
                    shouldRestartSelectedChunksOnPlay = false
                    updateState(chunkId = nextTarget.id, autoPauseEndsAt = null)
                    seekInternal(nextTarget.startMs)
                    scheduleBoundaryMessage(
                        payload = createChunkEndPayload(nextTarget),
                        persistent = false,
                    )
                } else {
                    cancelPendingMessage()
                    shouldRestartSelectedChunksOnPlay = true
                    pausedAtChunkEndPositionMs = nowMs
                    exoPlayer.pause()
                }
                return
            }
            updateState(
                chunkId = findChunkAt(nowMs)?.id,
                autoPauseEndsAt = _autoPauseEndsAtMs.value,
            )
            cancelPendingMessage()
            return
        }

        if (singleChunkPlaybackId == null &&
            loopMode == LoopMode.Off &&
            !hasSelectedRepeatChunks &&
            (useSkipFilter || useLanguageFilter || useBookmarkFilter)
        ) {
            rescheduleWithPlaybackFilter(
                nowMs = nowMs,
                minBoundaryAfterMs = minBoundaryAfterMs,
                filter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
                wrapToFirstMatchingIfNoNext = wrapToFirstMatchingIfNoNext,
                isPlayingOrStarting = isPlayingOrStarting,
            )
            return
        }

        if (minBoundaryAfterMs != null) {
            val nextChunk = chunks.firstOrNull {
                it.startMs >= minBoundaryAfterMs && it.endMs > max(nowMs, minBoundaryAfterMs)
            }
            if (nextChunk == null) {
                cancelPendingMessage()
                return
            }
            if (nextChunk.startMs <= nowMs || nextChunk.startMs == minBoundaryAfterMs) {
                updateState(
                    chunkId = nextChunk.id,
                    autoPauseEndsAt = _autoPauseEndsAtMs.value,
                    flushBufferOnSpeedChange = isPlayingOrStarting,
                )
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(nextChunk),
                    persistent = false,
                )
            } else if (practiceSettings.skipOriginalSilence && isPlayingOrStarting) {
                updateState(chunkId = nextChunk.id, autoPauseEndsAt = null)
                seekInternal(nextChunk.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(nextChunk),
                    persistent = false,
                )
            } else {
                prepareSpeedForUpcomingChunk(nextChunk, isPlayingOrStarting)
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkStart(nextChunk),
                    persistent = false,
                )
            }
            return
        }

        val activeChunk = chunks.firstOrNull { nowMs in it.startMs until effectiveChunkEndMs(it) }
        if (activeChunk != null) {
            updateState(chunkId = activeChunk.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
            scheduleBoundaryMessage(
                payload = createChunkEndPayload(activeChunk),
                persistent = false,
            )
        } else {
            val nextChunk = chunks.firstOrNull { it.startMs > nowMs }
            if (nextChunk != null && practiceSettings.skipOriginalSilence && isPlayingOrStarting) {
                updateState(chunkId = nextChunk.id, autoPauseEndsAt = null)
                seekInternal(nextChunk.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(nextChunk),
                    persistent = false,
                )
                return
            }
            updateState(
                chunkId = chunks.lastOrNull { it.startMs <= nowMs }?.id,
                autoPauseEndsAt = _autoPauseEndsAtMs.value,
            )
            if (nextChunk != null) {
                prepareSpeedForUpcomingChunk(nextChunk, isPlayingOrStarting)
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkStart(nextChunk),
                    persistent = false,
                )
            } else {
                cancelPendingMessage()
            }
        }
    }

    private fun rescheduleWithPlaybackFilter(
        nowMs: Long,
        minBoundaryAfterMs: Long?,
        filter: LanguageFilter,
        useLanguageFilter: Boolean,
        useBookmarkFilter: Boolean,
        wrapToFirstMatchingIfNoNext: Boolean,
        isPlayingOrStarting: Boolean,
    ) {
        val effectivePosMs = if (minBoundaryAfterMs != null) max(nowMs, minBoundaryAfterMs) else nowMs
        val ownerIndex = chunks.indexOfLast { it.startMs <= effectivePosMs }
        val owner = chunks.getOrNull(ownerIndex)
        val shouldWrapAtEnd = useBookmarkFilter || practiceSettings.repeatWholeFile
        if (owner != null &&
            matchesPlaybackFilter(
                chunk = owner,
                languageFilter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
            )
        ) {
            val ownerEndMs = effectiveChunkEndMs(owner)
            val isStillInsideOwner = effectivePosMs < ownerEndMs &&
                (minBoundaryAfterMs == null || ownerEndMs > minBoundaryAfterMs)
            if (isStillInsideOwner) {
                updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(owner),
                    persistent = false,
                )
                return
            }

            // owner 直後の空白区間 [ownerEndMs, nextChunk.startMs) を再生中
            val nextChunk = chunks.getOrNull(ownerIndex + 1)
            if (nextChunk == null) {
                if (shouldWrapAtEnd && isPlayingOrStarting) {
                    val firstMatching = chunks.firstOrNull { candidate ->
                        matchesPlaybackFilter(
                            chunk = candidate,
                            languageFilter = filter,
                            useLanguageFilter = useLanguageFilter,
                            useBookmarkFilter = useBookmarkFilter,
                        )
                    }
                    if (firstMatching != null) {
                        updateState(chunkId = firstMatching.id, autoPauseEndsAt = null)
                        seekInternal(firstMatching.startMs)
                        scheduleBoundaryMessage(
                            payload = createChunkEndPayload(firstMatching),
                            persistent = false,
                        )
                        return
                    }
                }
                updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                cancelPendingMessage()
                return
            }

            val nextChunkMatches = matchesPlaybackFilter(
                chunk = nextChunk,
                languageFilter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
            )
            if (!practiceSettings.skipOriginalSilence) {
                if (nextChunkMatches) {
                    if (nextChunk.startMs <= nowMs) {
                        updateState(
                            chunkId = nextChunk.id,
                            autoPauseEndsAt = _autoPauseEndsAtMs.value,
                            flushBufferOnSpeedChange = isPlayingOrStarting,
                        )
                        scheduleBoundaryMessage(
                            payload = createChunkEndPayload(nextChunk),
                            persistent = false,
                        )
                    } else {
                        updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                        prepareSpeedForUpcomingChunk(nextChunk, isPlayingOrStarting)
                        scheduleBoundaryMessage(
                            payload = BoundaryPayload.ChunkStart(nextChunk),
                            persistent = false,
                        )
                    }
                    return
                }
                val silenceEndMs = trailingSilenceEndMs(owner, nextChunk)
                if (silenceEndMs > ownerEndMs && effectivePosMs < silenceEndMs) {
                    updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                    scheduleBoundaryMessage(
                        payload = BoundaryPayload.PostChunkSilenceEnd(
                            chunk = owner,
                            boundaryMs = silenceEndMs,
                        ),
                        persistent = false,
                    )
                    return
                }
            }

            if (isPlayingOrStarting) {
                val nextMatching = chunks.drop(ownerIndex + 1).firstOrNull { candidate ->
                    matchesPlaybackFilter(
                        chunk = candidate,
                        languageFilter = filter,
                        useLanguageFilter = useLanguageFilter,
                        useBookmarkFilter = useBookmarkFilter,
                    )
                }
                val firstMatching = chunks.firstOrNull { candidate ->
                    matchesPlaybackFilter(
                        chunk = candidate,
                        languageFilter = filter,
                        useLanguageFilter = useLanguageFilter,
                        useBookmarkFilter = useBookmarkFilter,
                    )
                }
                val targetMatching = nextMatching
                    ?: if (wrapToFirstMatchingIfNoNext || shouldWrapAtEnd) firstMatching else null
                if (targetMatching != null) {
                    updateState(chunkId = targetMatching.id, autoPauseEndsAt = null)
                    seekInternal(targetMatching.startMs)
                    scheduleBoundaryMessage(
                        payload = createChunkEndPayload(targetMatching),
                        persistent = false,
                    )
                } else {
                    cancelPendingMessage()
                    pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    exoPlayer.pause()
                }
                return
            }

            updateState(chunkId = owner.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
            cancelPendingMessage()
            return
        }

        // owner == null で先頭チャンクがフィルタ条件を満たす場合は、先頭チャンクの開始を待つ（無音スキップ時は即シーク）
        val firstChunk = chunks.first()
        if (owner == null &&
            matchesPlaybackFilter(
                chunk = firstChunk,
                languageFilter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
            )
        ) {
            if (practiceSettings.skipOriginalSilence && isPlayingOrStarting && firstChunk.startMs > nowMs) {
                updateState(chunkId = firstChunk.id, autoPauseEndsAt = null)
                seekInternal(firstChunk.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(firstChunk),
                    persistent = false,
                )
                return
            }
            updateState(chunkId = null, autoPauseEndsAt = _autoPauseEndsAtMs.value)
            prepareSpeedForUpcomingChunk(firstChunk, isPlayingOrStarting)
            scheduleBoundaryMessage(
                payload = BoundaryPayload.ChunkStart(firstChunk),
                persistent = false,
            )
            return
        }

        // owner == null（先頭チャンクが非該当）または owner が非該当チャンクの場合
        if (isPlayingOrStarting) {
            val candidatesAfterPos = if (ownerIndex >= 0) {
                chunks.drop(ownerIndex + 1)
            } else {
                chunks
            }
            val nextMatching = candidatesAfterPos.firstOrNull { candidate ->
                matchesPlaybackFilter(
                    chunk = candidate,
                    languageFilter = filter,
                    useLanguageFilter = useLanguageFilter,
                    useBookmarkFilter = useBookmarkFilter,
                )
            }
            val firstMatching = chunks.firstOrNull { candidate ->
                matchesPlaybackFilter(
                    chunk = candidate,
                    languageFilter = filter,
                    useLanguageFilter = useLanguageFilter,
                    useBookmarkFilter = useBookmarkFilter,
                )
            }
            val targetMatching = nextMatching
                ?: if (wrapToFirstMatchingIfNoNext || shouldWrapAtEnd) firstMatching else null

            if (targetMatching != null) {
                updateState(chunkId = targetMatching.id, autoPauseEndsAt = null)
                seekInternal(targetMatching.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(targetMatching),
                    persistent = false,
                )
            } else {
                cancelPendingMessage()
                pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.pause()
            }
        } else {
            updateState(chunkId = owner?.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
            cancelPendingMessage()
        }
    }

    private fun scheduleBoundaryMessage(payload: BoundaryPayload, persistent: Boolean) {
        val existing = pendingMessage
        if (existing != null &&
            !existing.isCanceled &&
            pendingMessagePayload == payload
        ) {
            return
        }

        cancelPendingMessage()

        val message = exoPlayer.createMessage { _, deliveredPayload ->
            val boundaryPayload = deliveredPayload as? BoundaryPayload ?: return@createMessage
            onBoundaryDelivered(boundaryPayload)
        }
            .setPayload(payload)
            .setPosition(payload.boundaryMs)
            .setLooper(mainLooper)
            .setDeleteAfterDelivery(!persistent)
            .send()

        pendingMessage = message
        pendingMessagePayload = payload
    }

    private fun cancelPendingMessage() {
        pendingMessage?.cancel()
        pendingMessage = null
        pendingMessagePayload = null
    }

    private fun onBoundaryDelivered(payload: BoundaryPayload) {
        when (payload) {
            is BoundaryPayload.AbEnd -> {
                val loop = practiceSettings.loop as? LoopMode.AbRange ?: return
                if (chunks.isNotEmpty()) {
                    updateState(
                        chunkId = findChunkAt(loop.startMs)?.id,
                        autoPauseEndsAt = null,
                    )
                }
                seekInternal(loop.startMs)
            }

            is BoundaryPayload.ChunkStart -> {
                pendingMessage = null
                pendingMessagePayload = null
                val chunk = payload.chunk
                updateState(
                    chunkId = chunk.id,
                    autoPauseEndsAt = _autoPauseEndsAtMs.value,
                    flushBufferOnSpeedChange = exoPlayer.playWhenReady,
                )
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(chunk),
                    persistent = false,
                )
            }

            is BoundaryPayload.ChunkEnd -> {
                pendingMessage = null
                pendingMessagePayload = null
                handleChunkEndBoundary(payload.chunk)
            }

            is BoundaryPayload.PostChunkSilenceEnd -> {
                pendingMessage = null
                pendingMessagePayload = null
                handlePostChunkSilenceEnd(payload.chunk, payload.boundaryMs)
            }
        }
    }

    private fun handleChunkEndBoundary(chunk: Chunk) {
        val postAction = determinePostChunkAction(chunk)
        if (!postAction.allowsAutoPause) {
            executePostChunkAction(postAction)
            return
        }
        val pauseDurationMs = computeAutoPauseDurationMs(chunk)

        if (pauseDurationMs > 0L) {
            startAutoPause(
                chunk = chunk,
                pauseDurationMs = pauseDurationMs,
                postAction = postAction,
            )
        } else {
            executePostChunkAction(postAction)
        }
    }

    private fun handlePostChunkSilenceEnd(chunk: Chunk, silenceEndMs: Long) {
        if (singleChunkPlaybackId == chunk.id) {
            if (!practiceSettings.repeatWholeFile &&
                practiceSettings.languageFilter == LanguageFilter.CHECKED_ONLY &&
                chunk.id in practiceSettings.repeatChunkIds &&
                practiceSettings.repeatCheckedOnly
            ) {
                updateState(chunkId = chunk.id, autoPauseEndsAt = null)
                seekInternal(chunk.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(chunk),
                    persistent = false,
                )
            } else {
                singleChunkPlaybackId = null
                shouldRestartSelectedChunksOnPlay = true
                pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.pause()
            }
            return
        }

        val repeatChunks = activeRepeatChunks()
        if (repeatChunks.isNotEmpty()) {
            val selectedIndex = repeatChunks.indexOfFirst { it.id == chunk.id }
            val nextTarget: Chunk? = if (selectedIndex >= 0) {
                repeatChunks.getOrNull(selectedIndex + 1)
            } else {
                repeatChunks.firstOrNull { it.startMs >= chunk.endMs }
            } ?: if (practiceSettings.repeatCheckedOnly) repeatChunks.first() else null
            if (nextTarget != null) {
                shouldRestartSelectedChunksOnPlay = false
                updateState(chunkId = nextTarget.id, autoPauseEndsAt = null)
                seekInternal(nextTarget.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(nextTarget),
                    persistent = false,
                )
            } else {
                shouldRestartSelectedChunksOnPlay = true
                pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.pause()
            }
            return
        }

        val filter = practiceSettings.languageFilter
        val useSkipFilter = chunks.any { it.isSkipped }
        val useLanguageFilter = filter != LanguageFilter.ALL && isLanguageFilterApplicable(filter)
        val useBookmarkFilter = practiceSettings.bookmarksOnly && chunks.any(::isChunkBookmarked)
        if (!useSkipFilter && !useLanguageFilter && !useBookmarkFilter) {
            reschedule(minBoundaryAfterMs = silenceEndMs)
            return
        }

        val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
        val candidates = if (currentIndex >= 0) {
            chunks.drop(currentIndex + 1)
        } else {
            chunks.filter { it.startMs >= silenceEndMs }
        }
        val nextMatching = candidates.firstOrNull { candidate ->
            matchesPlaybackFilter(
                chunk = candidate,
                languageFilter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
            )
        }
        if (nextMatching != null) {
            updateState(chunkId = nextMatching.id, autoPauseEndsAt = null)
            seekInternal(nextMatching.startMs)
            scheduleBoundaryMessage(
                payload = createChunkEndPayload(nextMatching),
                persistent = false,
            )
            return
        }
        if (useBookmarkFilter || practiceSettings.repeatWholeFile) {
            val firstMatching = chunks.firstOrNull { candidate ->
                matchesPlaybackFilter(
                    chunk = candidate,
                    languageFilter = filter,
                    useLanguageFilter = useLanguageFilter,
                    useBookmarkFilter = useBookmarkFilter,
                )
            }
            if (firstMatching != null) {
                updateState(chunkId = firstMatching.id, autoPauseEndsAt = null)
                seekInternal(firstMatching.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(firstMatching),
                    persistent = false,
                )
                return
            }
        }
        pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        exoPlayer.pause()
        onFilePlaybackEnded()
    }

    /**
     * チャンク終端でのリピート・言語フィルタ・通常進行の優先順位に従い、次の動作を決定する（§7.2.4）。
     *
     * 優先順位: `AbRange > ChunkRange > repeatChunkIds > SingleChunk > LanguageFilter / BookmarksOnly`。
     */
    private fun determinePostChunkAction(chunk: Chunk): PostChunkAction {
        if (singleChunkPlaybackId == chunk.id) {
            val singleLoop = (practiceSettings.loop as? LoopMode.SingleChunk)?.takeIf { it.chunkId == chunk.id }
            if (singleLoop != null) {
                val maxTimes = singleLoop.times
                if (maxTimes == null) {
                    return PostChunkAction.SeekToChunk(chunk)
                }
                completedLoopCount += 1
                return if (completedLoopCount < maxTimes) {
                    PostChunkAction.SeekToChunk(chunk)
                } else {
                    completedLoopCount = 0
                    resetLoopModeInternally()
                    singleChunkPlaybackId = null
                    PostChunkAction.PauseAtChunkEnd(allowsAutoPause = false)
                }
            }
            if (!practiceSettings.repeatWholeFile &&
                practiceSettings.languageFilter == LanguageFilter.CHECKED_ONLY &&
                chunk.id in practiceSettings.repeatChunkIds
            ) {
                val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
                val nextChunk = if (currentIndex >= 0) chunks.getOrNull(currentIndex + 1) else null
                if (!practiceSettings.skipOriginalSilence && nextChunk != null) {
                    val silenceEndMs = trailingSilenceEndMs(chunk, nextChunk)
                    if (silenceEndMs > chunk.endMs) {
                        return PostChunkAction.PlayTrailingSilenceUntil(
                            chunk = chunk,
                            silenceEndMs = silenceEndMs,
                        )
                    }
                }
                return if (practiceSettings.repeatCheckedOnly) {
                    PostChunkAction.SeekToChunk(chunk)
                } else {
                    singleChunkPlaybackId = null
                    PostChunkAction.PauseAtChunkEnd(allowsAutoPause = false)
                }
            }
            singleChunkPlaybackId = null
            return PostChunkAction.PauseAtChunkEnd(allowsAutoPause = false)
        }

        val loop = practiceSettings.loop
        if (loop is LoopMode.AbRange) {
            return PostChunkAction.SeekToPosition(loop.startMs)
        }

        if (loop is LoopMode.ChunkRange) {
            val firstIndex = chunks.indexOfFirst { it.id == loop.firstChunkId }
            val lastIndex = chunks.indexOfFirst { it.id == loop.lastChunkId }
            val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
            if (firstIndex >= 0 && lastIndex >= firstIndex && currentIndex in firstIndex..lastIndex) {
                if (currentIndex < lastIndex) {
                    val nextInRange = chunks[currentIndex + 1]
                    return if (practiceSettings.skipOriginalSilence && nextInRange.startMs > chunk.endMs) {
                        PostChunkAction.SeekToChunk(nextInRange)
                    } else {
                        PostChunkAction.AdvanceAfter(chunk.endMs)
                    }
                } else {
                    // lastChunkId の終端に到達
                    completedLoopCount += 1
                    val maxTimes = loop.times
                    if (maxTimes == null || completedLoopCount < maxTimes) {
                        return PostChunkAction.SeekToChunk(chunks[firstIndex])
                    } else {
                        completedLoopCount = 0
                        resetLoopModeInternally()
                    }
                }
            }
        }

        // 「☑ のみ」（LanguageFilter.CHECKED_ONLY）のときのみ、チェックボックス（または拡大波形の長押し）で選択したチャンク（repeatChunkIds）を時系列の順に再生する。
        // 最後の選択チャンクのあとは、リピートが ON なら最初へ戻り、OFF なら一時停止する。
        // ただしファイル全体リピート（repeatWholeFile）が ON の間はチェックボックスの状態を変更せずに全チャンク再生を行う。
        // LoopMode.SingleChunk が残っていても複数選択チャンクの順次再生・リピートが妨げられないよう SingleChunk より先に判定する。
        val repeatChunks = activeRepeatChunks()
        if (repeatChunks.isNotEmpty()) {
            val selectedIndex = repeatChunks.indexOfFirst { it.id == chunk.id }
            val nextAfterCurrent: Chunk? = if (selectedIndex >= 0) {
                repeatChunks.getOrNull(selectedIndex + 1)
            } else {
                repeatChunks.firstOrNull { it.startMs >= chunk.endMs }
            }
            val nextTarget: Chunk? = nextAfterCurrent
                ?: if (practiceSettings.repeatCheckedOnly) repeatChunks.first() else null
            val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
            val nextChunk = if (currentIndex >= 0) {
                chunks.getOrNull(currentIndex + 1)
            } else {
                chunks.firstOrNull { it.startMs >= chunk.endMs }
            }
            if (!practiceSettings.skipOriginalSilence && nextChunk != null) {
                if (nextAfterCurrent != null && nextAfterCurrent.id == nextChunk.id) {
                    return PostChunkAction.AdvanceAfter(chunk.endMs)
                }
                val silenceEndMs = trailingSilenceEndMs(chunk, nextChunk)
                if (silenceEndMs > chunk.endMs) {
                    return PostChunkAction.PlayTrailingSilenceUntil(
                        chunk = chunk,
                        silenceEndMs = silenceEndMs,
                    )
                }
            }
            return if (nextTarget == null) {
                PostChunkAction.PauseAtChunkEnd(allowsAutoPause = false)
            } else {
                PostChunkAction.SeekToChunk(nextTarget)
            }
        }

        if (loop is LoopMode.SingleChunk && loop.chunkId == chunk.id) {
            completedLoopCount += 1
            val maxTimes = loop.times
            if (maxTimes == null || completedLoopCount < maxTimes) {
                return PostChunkAction.SeekToChunk(chunk)
            } else {
                completedLoopCount = 0
                resetLoopModeInternally()
                // 指定回数に達したら LoopMode.Off に戻し、次のチャンク（言語フィルタがあれば適用）へ進む
            }
        }

        // チャンクスキップ・言語フィルタ（ALL / TARGET_ONLY / BASE_ONLY）・ブックマーク連続再生（F8.1）の適用
        val filter = practiceSettings.languageFilter
        val useSkipFilter = chunks.any { it.isSkipped }
        val useLanguageFilter = filter != LanguageFilter.ALL && isLanguageFilterApplicable(filter)
        val useBookmarkFilter = practiceSettings.bookmarksOnly && chunks.any(::isChunkBookmarked)
        val shouldWrapAtEnd = useBookmarkFilter || practiceSettings.repeatWholeFile
        if (useSkipFilter || useLanguageFilter || useBookmarkFilter) {
            val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
            val nextChunk = if (currentIndex >= 0) {
                chunks.getOrNull(currentIndex + 1)
            } else {
                chunks.firstOrNull { it.startMs >= chunk.endMs }
            }
            val firstMatching = chunks.firstOrNull { candidate ->
                matchesPlaybackFilter(
                    chunk = candidate,
                    languageFilter = filter,
                    useLanguageFilter = useLanguageFilter,
                    useBookmarkFilter = useBookmarkFilter,
                )
            }

            if (nextChunk == null) {
                return if (shouldWrapAtEnd && firstMatching != null) {
                    PostChunkAction.SeekToChunk(firstMatching)
                } else {
                    PostChunkAction.AdvanceAfter(chunk.endMs)
                }
            }

            val nextChunkMatches = matchesPlaybackFilter(
                chunk = nextChunk,
                languageFilter = filter,
                useLanguageFilter = useLanguageFilter,
                useBookmarkFilter = useBookmarkFilter,
            )
            if (nextChunkMatches) {
                return if (practiceSettings.skipOriginalSilence && nextChunk.startMs > chunk.endMs) {
                    PostChunkAction.SeekToChunk(nextChunk)
                } else {
                    PostChunkAction.AdvanceAfter(chunk.endMs)
                }
            }

            val silenceEndMs = trailingSilenceEndMs(chunk, nextChunk)
            if (!practiceSettings.skipOriginalSilence && silenceEndMs > chunk.endMs) {
                return PostChunkAction.PlayTrailingSilenceUntil(
                    chunk = chunk,
                    silenceEndMs = silenceEndMs,
                )
            }

            val candidates = if (currentIndex >= 0) {
                chunks.drop(currentIndex + 1)
            } else {
                chunks.filter { it.startMs >= chunk.endMs }
            }
            val nextMatching = candidates.firstOrNull { candidate ->
                matchesPlaybackFilter(
                    chunk = candidate,
                    languageFilter = filter,
                    useLanguageFilter = useLanguageFilter,
                    useBookmarkFilter = useBookmarkFilter,
                )
            }
            return when {
                nextMatching != null -> PostChunkAction.SeekToChunk(nextMatching)
                shouldWrapAtEnd && firstMatching != null -> PostChunkAction.SeekToChunk(firstMatching)
                else -> PostChunkAction.PauseAtChunkEnd(allowsAutoPause = true)
            }
        }

        val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
        val nextChunk = if (currentIndex >= 0) {
            chunks.getOrNull(currentIndex + 1)
        } else {
            chunks.firstOrNull { it.startMs >= chunk.endMs }
        }
        if (nextChunk == null && practiceSettings.repeatWholeFile && chunks.isNotEmpty()) {
            return PostChunkAction.SeekToChunk(chunks.first())
        }
        if (practiceSettings.skipOriginalSilence && nextChunk != null && nextChunk.startMs > chunk.endMs) {
            return PostChunkAction.SeekToChunk(nextChunk)
        }

        return PostChunkAction.AdvanceAfter(chunk.endMs)
    }

    private fun executePostChunkAction(action: PostChunkAction) {
        when (action) {
            is PostChunkAction.SeekToPosition -> {
                updateState(
                    chunkId = findChunkAt(action.positionMs)?.id,
                    autoPauseEndsAt = null,
                )
                seekInternal(action.positionMs)
                reschedule()
            }

            is PostChunkAction.SeekToChunk -> {
                val target = action.targetChunk
                updateState(chunkId = target.id, autoPauseEndsAt = null)
                seekInternal(target.startMs)
                scheduleBoundaryMessage(
                    payload = createChunkEndPayload(target),
                    persistent = false,
                )
            }

            is PostChunkAction.AdvanceAfter -> {
                reschedule(minBoundaryAfterMs = action.finishedChunkEndMs)
            }

            is PostChunkAction.PlayTrailingSilenceUntil -> {
                if (exoPlayer.currentPosition + effectiveMinTrailingSilenceMs() >= action.silenceEndMs) {
                    handlePostChunkSilenceEnd(action.chunk, action.silenceEndMs)
                } else {
                    scheduleBoundaryMessage(
                        payload = BoundaryPayload.PostChunkSilenceEnd(
                            chunk = action.chunk,
                            boundaryMs = action.silenceEndMs,
                        ),
                        persistent = false,
                    )
                }
            }

            is PostChunkAction.PauseAtChunkEnd -> {
                singleChunkPlaybackId = null
                shouldRestartSelectedChunksOnPlay = true
                pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.pause()
                if (action.allowsAutoPause) {
                    onFilePlaybackEnded()
                }
            }
        }
    }

    private fun computeAutoPauseDurationMs(chunk: Chunk): Long {
        val autoPause = practiceSettings.autoPause
        if (autoPause is AutoPause.Off) return 0L

        val scopeMatches = when (practiceSettings.autoPauseScope) {
            AutoPauseScope.ALL_CHUNKS -> true

            AutoPauseScope.AFTER_TARGET_LANGUAGE -> {
                val targetLang = languagePair?.target
                val chunkLang = transcripts[chunk.id]?.language
                targetLang != null && chunkLang != null && isSameOrCompatibleLanguage(chunkLang, targetLang)
            }

            AutoPauseScope.AFTER_BASE_LANGUAGE -> {
                val baseLang = languagePair?.base
                val chunkLang = transcripts[chunk.id]?.language
                baseLang != null && chunkLang != null && isSameOrCompatibleLanguage(chunkLang, baseLang)
            }
        }
        if (!scopeMatches) return 0L

        return when (autoPause) {
            AutoPause.Off -> 0L

            is AutoPause.Fixed -> autoPause.pauseMs.coerceAtLeast(0L)

            is AutoPause.Proportional -> {
                val currentSpeed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
                max(
                    autoPause.minMs,
                    ((chunk.durationMs / currentSpeed) * autoPause.factor).toLong(),
                ).coerceAtLeast(0L)
            }

            is AutoPause.OriginalSilence -> {
                val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
                val nextChunk = if (currentIndex >= 0) {
                    chunks.getOrNull(currentIndex + 1)
                } else {
                    chunks.firstOrNull { it.startMs >= chunk.endMs }
                }
                val originalSilenceMs = if (nextChunk != null) {
                    (nextChunk.startMs - chunk.endMs).coerceAtLeast(0L)
                } else {
                    0L
                }
                (originalSilenceMs * autoPause.factor).roundToLong().coerceAtLeast(0L)
            }
        }
    }

    private fun startAutoPause(
        chunk: Chunk,
        pauseDurationMs: Long,
        postAction: PostChunkAction,
    ) {
        cancelAutoPauseTimerOnly()
        autoPausedChunk = chunk
        pendingPostPauseAction = postAction
        lastAutoPausedChunkEndMs = chunk.endMs

        isHandlingAutoPause = true
        try {
            exoPlayer.pause()
        } finally {
            isHandlingAutoPause = false
        }

        wakeLock?.acquire(pauseDurationMs + WAKE_LOCK_MARGIN_MS)
        val endsAtMs = elapsedRealtimeMs() + pauseDurationMs
        updateState(chunkId = chunk.id, autoPauseEndsAt = endsAtMs)

        val runnable = Runnable { resumeFromAutoPause() }
        autoPauseResumeRunnable = runnable
        mainHandler.postDelayed(runnable, pauseDurationMs)
    }

    private fun resumeFromAutoPause() {
        if (_autoPauseEndsAtMs.value == null) return
        resumeFromAutoPauseEarly(ensurePlaying = true)
    }

    private fun resumeFromAutoPauseEarly(ensurePlaying: Boolean) {
        if (_autoPauseEndsAtMs.value == null) return
        val action = pendingPostPauseAction
        val resumeAfterMs = lastAutoPausedChunkEndMs
        cancelAutoPause()

        if (action != null) {
            when (action) {
                is PostChunkAction.SeekToPosition -> {
                    updateState(chunkId = findChunkAt(action.positionMs)?.id, autoPauseEndsAt = null)
                    seekInternal(action.positionMs)
                    if (ensurePlaying) {
                        resumePlayerFromAutoPause()
                    }
                    reschedule()
                }

                is PostChunkAction.SeekToChunk -> {
                    val target = action.targetChunk
                    shouldRestartSelectedChunksOnPlay = false
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    seekInternal(target.startMs)
                    if (ensurePlaying) {
                        resumePlayerFromAutoPause()
                    }
                    scheduleBoundaryMessage(
                        payload = createChunkEndPayload(target),
                        persistent = false,
                    )
                }

                is PostChunkAction.AdvanceAfter -> {
                    reschedule(
                        minBoundaryAfterMs = action.finishedChunkEndMs,
                        assumePlaying = ensurePlaying,
                    )
                    if (ensurePlaying) {
                        resumePlayerFromAutoPause()
                    }
                }

                is PostChunkAction.PlayTrailingSilenceUntil -> {
                    if (ensurePlaying) {
                        resumePlayerFromAutoPause()
                    }
                    if (exoPlayer.currentPosition + effectiveMinTrailingSilenceMs() >= action.silenceEndMs) {
                        handlePostChunkSilenceEnd(action.chunk, action.silenceEndMs)
                    } else {
                        scheduleBoundaryMessage(
                            payload = BoundaryPayload.PostChunkSilenceEnd(
                                chunk = action.chunk,
                                boundaryMs = action.silenceEndMs,
                            ),
                            persistent = false,
                        )
                    }
                }

                is PostChunkAction.PauseAtChunkEnd -> {
                    singleChunkPlaybackId = null
                    shouldRestartSelectedChunksOnPlay = true
                    pausedAtChunkEndPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    exoPlayer.pause()
                }
            }
        } else {
            reschedule(
                minBoundaryAfterMs = resumeAfterMs,
                assumePlaying = ensurePlaying,
            )
            if (ensurePlaying) {
                resumePlayerFromAutoPause()
            }
        }
    }

    private fun resumePlayerFromAutoPause() {
        isResumingFromAutoPause = true
        try {
            exoPlayer.play()
        } finally {
            isResumingFromAutoPause = false
        }
    }

    /**
     * 実行中の自動ポーズを即座に取り消し、タイマー・`autoPauseEndsAtMs`・WakeLock をすべてクリアする（§7.2.4）。
     */
    fun cancelAutoPause() {
        cancelAutoPauseTimerOnly()
        autoPausedChunk = null
        pendingPostPauseAction = null
        lastAutoPausedChunkEndMs = null
        releaseWakeLockIfHeld()
        if (_autoPauseEndsAtMs.value != null) {
            updateState(chunkId = _currentChunkId.value, autoPauseEndsAt = null)
        }
    }

    private fun cancelAutoPauseTimerOnly() {
        autoPauseResumeRunnable?.let(mainHandler::removeCallbacks)
        autoPauseResumeRunnable = null
    }

    private fun releaseWakeLockIfHeld() {
        val wl = wakeLock ?: return
        if (wl.isHeld) {
            wl.release()
        }
    }

    private fun seekInternal(positionMs: Long) {
        pendingSelfSeekCount += 1
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun resetLoopModeInternally() {
        isUpdatingLoopInternally = true
        try {
            practiceController.resetLoop()
            practiceSettings = practiceController.settings.value
        } finally {
            isUpdatingLoopInternally = false
        }
    }

    private fun activeRepeatChunks(settings: PracticeSettings = practiceSettings): List<Chunk> {
        if (settings.repeatWholeFile || settings.languageFilter != LanguageFilter.CHECKED_ONLY) {
            return emptyList()
        }
        return chunks.filter { it.id in settings.repeatChunkIds && !it.isSkipped }
    }

    private fun createChunkEndPayload(chunk: Chunk): BoundaryPayload.ChunkEnd = BoundaryPayload.ChunkEnd(
        chunk = chunk,
        boundaryMs = effectiveChunkEndMs(chunk),
    )

    private fun currentPlaybackSpeed(): Float = exoPlayer.playbackParameters.speed.coerceAtLeast(1.0f)

    private fun effectiveSilenceTailCutMs(): Long = (POST_CHUNK_SILENCE_TAIL_CUT_MS * currentPlaybackSpeed()).roundToLong()

    private fun effectiveMinTrailingSilenceMs(): Long = (MIN_TRAILING_SILENCE_DURATION_MS * currentPlaybackSpeed()).roundToLong()

    /**
     * 直後の次チャンクが非選択（フィルタやチェックのみでスキップされる対象）のとき、
     * チャンク間の隙間が狭い（または `0ms` の）場合に `chunk.endMs` で [PlayerMessage] を発火させると
     * メインスレッドへの配送と `AudioTrack` フラッシュの往復遅延のあいだに次チャンク冒頭の音声が漏れてしまう。
     * そのため、次チャンクが非選択の場合は `nextChunk.startMs - guardMs` まで境界を前倒しする。
     */
    internal fun effectiveChunkEndMs(
        chunk: Chunk,
        settings: PracticeSettings = practiceSettings,
    ): Long {
        if (singleChunkPlaybackId != null ||
            settings.loop is LoopMode.AbRange ||
            settings.loop is LoopMode.ChunkRange
        ) {
            return chunk.endMs
        }
        val index = chunks.indexOfFirst { it.id == chunk.id }
        val nextChunk = if (index >= 0) chunks.getOrNull(index + 1) else null
        if (nextChunk == null || !isNextChunkUnselected(chunk, nextChunk, settings)) {
            return chunk.endMs
        }
        val speed = currentPlaybackSpeed()
        val guardMs = (UNSELECTED_NEXT_CHUNK_GUARD_MS * speed).roundToLong()
        val maxTailCutMs = (MAX_CHUNK_TAIL_CUT_MS * speed).roundToLong()
        val minEndMs = max(
            chunk.startMs + min(MIN_PLAYABLE_CHUNK_DURATION_MS, chunk.durationMs / 2L),
            chunk.endMs - maxTailCutMs,
        )
        val guardedEndMs = (nextChunk.startMs - guardMs).coerceAtLeast(minEndMs)
        return min(chunk.endMs, guardedEndMs)
    }

    private fun isNextChunkUnselected(
        chunk: Chunk,
        nextChunk: Chunk,
        settings: PracticeSettings = practiceSettings,
    ): Boolean {
        val repeatChunks = activeRepeatChunks(settings)
        if (repeatChunks.isNotEmpty()) {
            val selectedIndex = repeatChunks.indexOfFirst { it.id == chunk.id }
            val nextRepeatChunk = if (selectedIndex >= 0) {
                repeatChunks.getOrNull(selectedIndex + 1)
            } else {
                repeatChunks.firstOrNull { it.startMs >= chunk.endMs }
            }
            return nextRepeatChunk?.id != nextChunk.id
        }
        val filter = settings.languageFilter
        val useSkipFilter = chunks.any { it.isSkipped }
        val useLanguageFilter = filter != LanguageFilter.ALL && isLanguageFilterApplicable(filter)
        val useBookmarkFilter = settings.bookmarksOnly && chunks.any(::isChunkBookmarked)
        if (!useSkipFilter && !useLanguageFilter && !useBookmarkFilter) {
            return false
        }
        return !matchesPlaybackFilter(
            chunk = nextChunk,
            languageFilter = filter,
            useLanguageFilter = useLanguageFilter,
            useBookmarkFilter = useBookmarkFilter,
        )
    }

    /**
     * チャンク直後の無音区間を再生してから次チャンクをスキップする際、
     * スキップ対象チャンク冒頭の数十ミリ秒が漏れるのを防ぐために末尾から [effectiveSilenceTailCutMs] 手前を終端とする。
     * カット後の無音区間が [effectiveMinTrailingSilenceMs] 未満の短い隙間しかない場合は、
     * 2 段階の [PlayerMessage] を発火させると遅延で次チャンクに食い込むため `chunk.endMs` を返して即座にシーク／停止させる。
     */
    private fun trailingSilenceEndMs(chunk: Chunk, nextChunk: Chunk): Long {
        val candidateSilenceEndMs = nextChunk.startMs - effectiveSilenceTailCutMs()
        return if (candidateSilenceEndMs - chunk.endMs >= effectiveMinTrailingSilenceMs()) {
            candidateSilenceEndMs
        } else {
            chunk.endMs
        }
    }

    private fun repeatChunkEffectiveEndMs(
        chunk: Chunk,
        settings: PracticeSettings = practiceSettings,
    ): Long {
        val baseEndMs = effectiveChunkEndMs(chunk, settings)
        if (settings.skipOriginalSilence) return baseEndMs
        val index = chunks.indexOfFirst { it.id == chunk.id }
        val nextChunk = if (index >= 0) chunks.getOrNull(index + 1) else null
        return if (nextChunk != null) {
            max(baseEndMs, trailingSilenceEndMs(chunk, nextChunk))
        } else {
            baseEndMs
        }
    }

    private fun isPositionWithinRepeatChunkOrSilence(
        chunk: Chunk,
        positionMs: Long,
        settings: PracticeSettings = practiceSettings,
    ): Boolean {
        val effectiveEndMs = repeatChunkEffectiveEndMs(chunk, settings)
        return positionMs in chunk.startMs until effectiveEndMs
    }

    private fun isLanguageFilterApplicable(filter: LanguageFilter): Boolean = when (filter) {
        LanguageFilter.ALL,
        LanguageFilter.CHECKED_ONLY,
        -> false

        LanguageFilter.TARGET_ONLY -> languagePair != null && transcripts.isNotEmpty()

        LanguageFilter.BASE_ONLY -> languagePair?.base != null && transcripts.isNotEmpty()
    }

    private fun isChunkBookmarked(chunk: Chunk): Boolean = bookmarks.any {
        it.positionMs in chunk.startMs until chunk.endMs || it.positionMs == chunk.startMs
    }

    private fun matchesPlaybackFilter(
        chunk: Chunk,
        languageFilter: LanguageFilter,
        useLanguageFilter: Boolean,
        useBookmarkFilter: Boolean,
    ): Boolean {
        if (chunk.isSkipped) return false
        if (useBookmarkFilter && !isChunkBookmarked(chunk)) return false
        if (useLanguageFilter && !matchesLanguageFilter(chunk.id, languageFilter)) return false
        return true
    }

    private fun matchesLanguageFilter(chunkId: ChunkId, filter: LanguageFilter): Boolean {
        val pair = languagePair ?: return true
        val chunkLang = transcripts[chunkId]?.language ?: return false
        return when (filter) {
            LanguageFilter.ALL,
            LanguageFilter.CHECKED_ONLY,
            -> true

            LanguageFilter.TARGET_ONLY -> isSameOrCompatibleLanguage(chunkLang, pair.target)

            LanguageFilter.BASE_ONLY -> pair.base?.let { isSameOrCompatibleLanguage(chunkLang, it) } == true
        }
    }

    private fun findChunkAt(positionMs: Long): Chunk? = chunks.firstOrNull { positionMs in it.startMs until it.endMs }
        ?: chunks.lastOrNull { it.startMs <= positionMs }

    private fun updateState(
        chunkId: ChunkId?,
        autoPauseEndsAt: Long?,
        flushBufferOnSpeedChange: Boolean = false,
    ) {
        val chunkChanged = _currentChunkId.value != chunkId
        val changed = chunkChanged || _autoPauseEndsAtMs.value != autoPauseEndsAt
        _currentChunkId.value = chunkId
        _autoPauseEndsAtMs.value = autoPauseEndsAt
        if (chunkChanged) {
            // チャンクが変わったら、ベース言語のチャンクかどうかで速度を切り替える
            applyEffectiveSpeed(
                userSpeedChanged = false,
                flushBufferOnSpeedChange = flushBufferOnSpeedChange,
            )
        }
        if (changed) {
            onStateChanged(chunkId, autoPauseEndsAt)
        }
    }

    /**
     * いまのチャンクに合った速度を ExoPlayer に当てる。ベース言語の速度が設定されていて、いまのチャンクがベース言語なら
     * [baseLanguageSpeed]、それ以外は [userSpeed]。
     *
     * ExoPlayer の速度を変えるのは、ベース言語の速度を当てる・戻すとき、または利用者が速度を変えたとき（[userSpeedChanged]）
     * だけにする。この機能を使っていないときは、チャンクが変わっても速度に手を出さない。
     *
     * チャンク境界を跨いで速度が切り替わるとき（[flushBufferOnSpeedChange] が `true`）、`DefaultAudioSink`（`AudioTrack`）や
     * `SonicAudioProcessor` に前のチャンクの速度で先読み伸長済みの PCM が最大約 750ms 残っていると、
     * 新しいチャンクの冒頭が前の速度で再生されてしまう。そのため、速度変更直後に当該チャンクの先頭（または現在位置）へ
     * 内部シークしてオーディオパイプラインをフラッシュし、チャンク冒頭から正しい速度で再生させる。
     */
    private fun applyEffectiveSpeed(
        userSpeedChanged: Boolean = false,
        flushBufferOnSpeedChange: Boolean = false,
    ) {
        val chunkId = _currentChunkId.value
        val override = baseLanguageSpeed?.takeIf { chunkId != null && isBaseLanguageChunk(chunkId) }
        val wasApplied = isBaseLanguageSpeedApplied
        isBaseLanguageSpeedApplied = override != null
        if (override == null && !wasApplied && !userSpeedChanged) return
        val desired = override ?: userSpeed
        if (abs(exoPlayer.playbackParameters.speed - desired) < SPEED_EPSILON) return
        exoPlayer.setPlaybackSpeed(desired)
        if (!userSpeedChanged && flushBufferOnSpeedChange && chunkId != null) {
            val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val targetChunk = chunks.firstOrNull { it.id == chunkId }
            val flushPosMs = if (targetChunk != null &&
                nowMs in targetChunk.startMs..(targetChunk.startMs + SPEED_CHANGE_START_SNAP_MS)
            ) {
                targetChunk.startMs
            } else {
                nowMs
            }
            seekInternal(flushPosMs)
        }
    }

    /**
     * 前チャンク終端から次チャンク [nextChunk] 開始までの無音区間に入った時点で、
     * 次チャンクの有効再生速度が現在と異なる場合は無音区間のうちに速度切替と `AudioTrack` のフラッシュを済ませ、
     * [nextChunk] の開始時刻に到達した瞬間から [nextChunk] の速度で再生されるようにする。
     */
    private fun prepareSpeedForUpcomingChunk(
        nextChunk: Chunk,
        isPlayingOrStarting: Boolean,
    ) {
        val override = baseLanguageSpeed?.takeIf { isBaseLanguageChunk(nextChunk.id) }
        val wasApplied = isBaseLanguageSpeedApplied
        isBaseLanguageSpeedApplied = override != null
        if (override == null && !wasApplied) return
        val desired = override ?: userSpeed
        if (abs(exoPlayer.playbackParameters.speed - desired) < SPEED_EPSILON) return
        exoPlayer.setPlaybackSpeed(desired)
        if (isPlayingOrStarting) {
            val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            seekInternal(nowMs)
        }
    }

    /**
     * [chunkId] のチャンクがベース言語（言語ペアの `base`）で話されているか。言語がまだわからないチャンクや、
     * 学習言語とベース言語が同じ言語のときは `false`。
     */
    private fun isBaseLanguageChunk(chunkId: ChunkId): Boolean {
        val pair = languagePair ?: return false
        val base = pair.base ?: return false
        if (isSameOrCompatibleLanguage(base, pair.target)) return false
        val chunkLang = transcripts[chunkId]?.language ?: return false
        return isSameOrCompatibleLanguage(chunkLang, base)
    }

    private sealed interface BoundaryPayload {
        val boundaryMs: Long

        data class AbEnd(
            val startMs: Long,
            override val boundaryMs: Long,
        ) : BoundaryPayload

        data class ChunkStart(
            val chunk: Chunk,
        ) : BoundaryPayload {
            override val boundaryMs: Long get() = chunk.startMs
        }

        data class ChunkEnd(
            val chunk: Chunk,
            override val boundaryMs: Long = chunk.endMs,
        ) : BoundaryPayload

        data class PostChunkSilenceEnd(
            val chunk: Chunk,
            override val boundaryMs: Long,
        ) : BoundaryPayload
    }

    private sealed interface PostChunkAction {
        val allowsAutoPause: Boolean
            get() = true

        data class SeekToPosition(val positionMs: Long) : PostChunkAction
        data class SeekToChunk(val targetChunk: Chunk) : PostChunkAction
        data class AdvanceAfter(val finishedChunkEndMs: Long) : PostChunkAction
        data class PlayTrailingSilenceUntil(
            val chunk: Chunk,
            val silenceEndMs: Long,
        ) : PostChunkAction
        data class PauseAtChunkEnd(
            override val allowsAutoPause: Boolean = false,
        ) : PostChunkAction
    }

    companion object {
        /** 自動ポーズ中の WakeLock タイムアウト余裕（ミリ秒、§7.2.4: `actualPauseMs + 5_000L`）。 */
        const val WAKE_LOCK_MARGIN_MS: Long = 5_000L

        /** チャンク直後の無音区間を再生する際、スキップ対象チャンクの冒頭音声漏れを防ぐために末尾からカットする基準時間（ミリ秒）。 */
        internal const val POST_CHUNK_SILENCE_TAIL_CUT_MS: Long = 250L

        /** カット後の無音区間がこの基準時間（ミリ秒）未満の場合は、無音再生用の 2 段目メッセージを予約せず即座にシーク／停止する。 */
        internal const val MIN_TRAILING_SILENCE_DURATION_MS: Long = 150L

        /** 直後の非選択チャンクとの隙間が狭い場合に、非選択チャンク開始時刻の手前に設ける安全マージン（ミリ秒）。 */
        internal const val UNSELECTED_NEXT_CHUNK_GUARD_MS: Long = 200L

        /** 非選択チャンクへの食い込み防止のために現在チャンクの末尾パディングを削る最大基準時間（ミリ秒）。 */
        internal const val MAX_CHUNK_TAIL_CUT_MS: Long = 250L

        /** 末尾カット適用時でも最低限確保するチャンク再生時間（ミリ秒）。 */
        internal const val MIN_PLAYABLE_CHUNK_DURATION_MS: Long = 200L

        /** チャンク境界で速度切替に伴うバッファフラッシュを行う際、チャンク先頭へスナップする許容幅（ミリ秒）。 */
        internal const val SPEED_CHANGE_START_SNAP_MS: Long = 250L

        private const val END_OF_CHUNK_MARGIN_MS: Long = 100L
        private const val MISSED_BOUNDARY_THRESHOLD_MS: Long = 50L
        private const val WAKE_LOCK_TAG: String = "ChunkFlow:AutoPauseWakeLock"

        /** 速度が同じとみなす差。 */
        private const val SPEED_EPSILON: Float = 0.001f

        /**
         * 2 つの [LanguageTag] が同一または互換の言語を表すかを判定する。
         *
         * - 大文字小文字および区切り文字（`-` / `_`）の違いを無視して完全一致する場合は `true`。
         * - 片方のみがサブタグを持たない主言語コード（例: `zh` と `zh-cn`、`ja` と `ja-JP`）の場合は主言語の一致を許容する。
         * - 両方が異なるサブタグを持つ場合（例: `zh-cn` と `zh-tw`）は `false` とする。
         */
        internal fun isSameOrCompatibleLanguage(
            a: com.miuraken.chunkflow.core.model.LanguageTag,
            b: com.miuraken.chunkflow.core.model.LanguageTag,
        ): Boolean {
            val rawA = a.value.trim().replace('_', '-')
            val rawB = b.value.trim().replace('_', '-')
            if (rawA.equals(rawB, ignoreCase = true)) return true
            val hasSubtagA = rawA.contains('-')
            val hasSubtagB = rawB.contains('-')
            if (hasSubtagA && hasSubtagB) return false
            val primaryA = rawA.substringBefore('-')
            val primaryB = rawB.substringBefore('-')
            return primaryA.isNotEmpty() && primaryA.equals(primaryB, ignoreCase = true)
        }
    }
}
