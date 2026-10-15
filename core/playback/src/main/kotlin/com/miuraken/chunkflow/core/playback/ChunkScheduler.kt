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
import kotlin.math.max
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

    private var isUpdatingLoopInternally: Boolean = false
    private var isHandlingAutoPause: Boolean = false
    private var isResumingFromAutoPause: Boolean = false
    private var autoPauseResumeRunnable: Runnable? = null
    private var pendingPostPauseAction: PostChunkAction? = null
    private var lastAutoPausedChunkEndMs: Long? = null

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
        languagePair = initialLanguagePair

        if (mediaId == null) {
            chunks = emptyList()
            transcripts = emptyMap()
            bookmarks = emptyList()
            updateState(chunkId = null, autoPauseEndsAt = null)
            cancelPendingMessage()
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
        if (chunks == sorted) return
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
        reschedule()
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
    }

    /**
     * ユーザの手動による再生・一時停止操作を通知する。自動ポーズ中であれば即座にタイマーを取り消す（§7.2.4）。
     */
    fun onUserPlayPauseCommand() {
        if (_autoPauseEndsAtMs.value != null && !isHandlingAutoPause && !isResumingFromAutoPause) {
            val resumeAfterMs = lastAutoPausedChunkEndMs
            cancelAutoPause()
            reschedule(minBoundaryAfterMs = resumeAfterMs)
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
            updateState(
                chunkId = findChunkAt(newPosMs)?.id,
                autoPauseEndsAt = _autoPauseEndsAtMs.value,
            )
            return
        }

        // ユーザによる手動シーク: リピート回数をリセットし、自動ポーズ中なら解除して次の境界を予約し直す（§7.2.4、ADR 0007）
        completedLoopCount = 0
        if (_autoPauseEndsAtMs.value != null) {
            cancelAutoPause()
        }
        updateState(
            chunkId = findChunkAt(newPosMs)?.id,
            autoPauseEndsAt = null,
        )
        reschedule()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (_autoPauseEndsAtMs.value != null && !isHandlingAutoPause && !isResumingFromAutoPause) {
            val resumeAfterMs = lastAutoPausedChunkEndMs
            cancelAutoPause()
            reschedule(minBoundaryAfterMs = resumeAfterMs)
            return
        }
        if (playWhenReady && _autoPauseEndsAtMs.value == null && practiceSettings.loop !is LoopMode.AbRange) {
            val repeatChunks = chunks.filter { it.id in practiceSettings.repeatChunkIds }
            if (repeatChunks.isNotEmpty()) {
                val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (repeatChunks.none { nowMs in it.startMs until it.endMs }) {
                    val target = repeatChunks.first()
                    seekInternal(target.startMs)
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    scheduleBoundaryMessage(
                        payload = BoundaryPayload.ChunkEnd(target),
                        persistent = false,
                    )
                    return
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            if (_autoPauseEndsAtMs.value != null) {
                cancelAutoPause()
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
        practiceSettings = newSettings

        if (!isUpdatingLoopInternally && oldLoop != newSettings.loop) {
            completedLoopCount = 0
        }

        if (oldAutoPause != AutoPause.Off && newSettings.autoPause == AutoPause.Off && _autoPauseEndsAtMs.value != null) {
            val resumeAfterMs = lastAutoPausedChunkEndMs
            cancelAutoPause()
            exoPlayer.play()
            reschedule(minBoundaryAfterMs = resumeAfterMs)
            return
        }

        if (newSettings.loop !is LoopMode.AbRange && oldRepeatChunkIds != newSettings.repeatChunkIds) {
            val repeatChunks = chunks.filter { it.id in newSettings.repeatChunkIds }
            if (repeatChunks.isNotEmpty()) {
                val nowMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val isCurrentlyInSelected = repeatChunks.any { nowMs in it.startMs until it.endMs }
                if (!isCurrentlyInSelected && _autoPauseEndsAtMs.value == null) {
                    val target = repeatChunks.first()
                    seekInternal(target.startMs)
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    scheduleBoundaryMessage(
                        payload = BoundaryPayload.ChunkEnd(target),
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
     */
    internal fun reschedule(minBoundaryAfterMs: Long? = null) {
        // 自動ポーズのタイマー待機中は再開時に reschedule するため新規予約しない
        if (_autoPauseEndsAtMs.value != null) {
            cancelPendingMessage()
            return
        }

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
        if (minBoundaryAfterMs != null) {
            val nextChunk = chunks.firstOrNull {
                it.startMs >= minBoundaryAfterMs && it.endMs > max(nowMs, minBoundaryAfterMs)
            }
            if (nextChunk == null) {
                cancelPendingMessage()
                return
            }
            if (nextChunk.startMs <= nowMs || nextChunk.startMs == minBoundaryAfterMs) {
                updateState(chunkId = nextChunk.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkEnd(nextChunk),
                    persistent = false,
                )
            } else {
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkStart(nextChunk),
                    persistent = false,
                )
            }
            return
        }

        val activeChunk = chunks.firstOrNull { nowMs in it.startMs until it.endMs }
        if (activeChunk != null) {
            updateState(chunkId = activeChunk.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
            scheduleBoundaryMessage(
                payload = BoundaryPayload.ChunkEnd(activeChunk),
                persistent = false,
            )
        } else {
            updateState(
                chunkId = chunks.lastOrNull { it.startMs <= nowMs }?.id,
                autoPauseEndsAt = _autoPauseEndsAtMs.value,
            )
            val nextChunk = chunks.firstOrNull { it.startMs > nowMs }
            if (nextChunk != null) {
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkStart(nextChunk),
                    persistent = false,
                )
            } else {
                cancelPendingMessage()
            }
        }
    }

    private fun scheduleBoundaryMessage(payload: BoundaryPayload, persistent: Boolean) {
        val existing = pendingMessage
        if (persistent &&
            existing != null &&
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
                seekInternal(loop.startMs)
                if (chunks.isNotEmpty()) {
                    updateState(
                        chunkId = findChunkAt(loop.startMs)?.id,
                        autoPauseEndsAt = null,
                    )
                }
            }

            is BoundaryPayload.ChunkStart -> {
                val chunk = payload.chunk
                updateState(chunkId = chunk.id, autoPauseEndsAt = _autoPauseEndsAtMs.value)
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkEnd(chunk),
                    persistent = false,
                )
            }

            is BoundaryPayload.ChunkEnd -> {
                pendingMessage = null
                pendingMessagePayload = null
                handleChunkEndBoundary(payload.chunk)
            }
        }
    }

    private fun handleChunkEndBoundary(chunk: Chunk) {
        val postAction = determinePostChunkAction(chunk)
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

    /**
     * チャンク終端でのリピート・言語フィルタ・通常進行の優先順位に従い、次の動作を決定する（§7.2.4）。
     *
     * 優先順位: `AbRange > SingleChunk / ChunkRange > LanguageFilter`。
     */
    private fun determinePostChunkAction(chunk: Chunk): PostChunkAction {
        when (val loop = practiceSettings.loop) {
            is LoopMode.AbRange -> {
                return PostChunkAction.SeekToPosition(loop.startMs)
            }

            is LoopMode.SingleChunk -> {
                if (loop.chunkId == chunk.id) {
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
            }

            is LoopMode.ChunkRange -> {
                val firstIndex = chunks.indexOfFirst { it.id == loop.firstChunkId }
                val lastIndex = chunks.indexOfFirst { it.id == loop.lastChunkId }
                val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
                if (firstIndex >= 0 && lastIndex >= firstIndex && currentIndex in firstIndex..lastIndex) {
                    if (currentIndex < lastIndex) {
                        return PostChunkAction.AdvanceAfter(chunk.endMs)
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

            LoopMode.Off -> Unit
        }

        // チェックボックスで選択されたチャンク（repeatChunkIds）の順次・無限リピート再生
        val repeatChunks = chunks.filter { it.id in practiceSettings.repeatChunkIds }
        if (repeatChunks.isNotEmpty()) {
            val nextTarget = if (chunk.id in practiceSettings.repeatChunkIds) {
                val idx = repeatChunks.indexOfFirst { it.id == chunk.id }
                if (idx >= 0 && idx + 1 < repeatChunks.size) {
                    repeatChunks[idx + 1]
                } else {
                    repeatChunks.first()
                }
            } else {
                repeatChunks.firstOrNull { it.startMs >= chunk.endMs } ?: repeatChunks.first()
            }
            return if (nextTarget.id != chunk.id && nextTarget.startMs == chunk.endMs) {
                PostChunkAction.AdvanceAfter(chunk.endMs)
            } else {
                PostChunkAction.SeekToChunk(nextTarget)
            }
        }

        // 言語フィルタ（ALL / TARGET_ONLY / BASE_ONLY）およびブックマーク連続再生（F8.1）の適用
        val filter = practiceSettings.languageFilter
        val useLanguageFilter = filter != LanguageFilter.ALL && isLanguageFilterApplicable(filter)
        val useBookmarkFilter = practiceSettings.bookmarksOnly && chunks.any(::isChunkBookmarked)
        if (useLanguageFilter || useBookmarkFilter) {
            val currentIndex = chunks.indexOfFirst { it.id == chunk.id }
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
            if (nextMatching != null) {
                return if (nextMatching.startMs == chunk.endMs) {
                    PostChunkAction.AdvanceAfter(chunk.endMs)
                } else {
                    PostChunkAction.SeekToChunk(nextMatching)
                }
            }
            if (useBookmarkFilter) {
                val firstMatching = chunks.firstOrNull { candidate ->
                    matchesPlaybackFilter(
                        chunk = candidate,
                        languageFilter = filter,
                        useLanguageFilter = useLanguageFilter,
                        useBookmarkFilter = useBookmarkFilter,
                    )
                }
                if (firstMatching != null) {
                    return PostChunkAction.SeekToChunk(firstMatching)
                }
            }
            return PostChunkAction.AdvanceAfter(chunk.endMs)
        }

        return PostChunkAction.AdvanceAfter(chunk.endMs)
    }

    private fun executePostChunkAction(action: PostChunkAction) {
        when (action) {
            is PostChunkAction.SeekToPosition -> {
                seekInternal(action.positionMs)
                updateState(
                    chunkId = findChunkAt(action.positionMs)?.id,
                    autoPauseEndsAt = null,
                )
                reschedule()
            }

            is PostChunkAction.SeekToChunk -> {
                val target = action.targetChunk
                seekInternal(target.startMs)
                updateState(chunkId = target.id, autoPauseEndsAt = null)
                scheduleBoundaryMessage(
                    payload = BoundaryPayload.ChunkEnd(target),
                    persistent = false,
                )
            }

            is PostChunkAction.AdvanceAfter -> {
                reschedule(minBoundaryAfterMs = action.finishedChunkEndMs)
            }
        }
    }

    private fun computeAutoPauseDurationMs(chunk: Chunk): Long {
        val autoPause = practiceSettings.autoPause
        if (autoPause is AutoPause.Off) return 0L

        val scopeMatches = when (practiceSettings.autoPauseScope) {
            AutoPauseScope.ALL_CHUNKS -> true

            AutoPauseScope.AFTER_BASE_LANGUAGE -> {
                val baseLang = languagePair?.base
                val chunkLang = transcripts[chunk.id]?.language
                baseLang != null && chunkLang == baseLang
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
        }
    }

    private fun startAutoPause(
        chunk: Chunk,
        pauseDurationMs: Long,
        postAction: PostChunkAction,
    ) {
        cancelAutoPauseTimerOnly()
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
        val action = pendingPostPauseAction
        cancelAutoPauseTimerOnly()
        releaseWakeLockIfHeld()
        pendingPostPauseAction = null
        lastAutoPausedChunkEndMs = null
        updateState(chunkId = _currentChunkId.value, autoPauseEndsAt = null)

        if (action != null) {
            when (action) {
                is PostChunkAction.SeekToPosition -> {
                    seekInternal(action.positionMs)
                    updateState(chunkId = findChunkAt(action.positionMs)?.id, autoPauseEndsAt = null)
                    resumePlayerFromAutoPause()
                    reschedule()
                }

                is PostChunkAction.SeekToChunk -> {
                    val target = action.targetChunk
                    seekInternal(target.startMs)
                    updateState(chunkId = target.id, autoPauseEndsAt = null)
                    resumePlayerFromAutoPause()
                    scheduleBoundaryMessage(
                        payload = BoundaryPayload.ChunkEnd(target),
                        persistent = false,
                    )
                }

                is PostChunkAction.AdvanceAfter -> {
                    resumePlayerFromAutoPause()
                    reschedule(minBoundaryAfterMs = action.finishedChunkEndMs)
                }
            }
        } else {
            resumePlayerFromAutoPause()
            reschedule()
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

    private fun isLanguageFilterApplicable(filter: LanguageFilter): Boolean = when (filter) {
        LanguageFilter.ALL -> false
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
        if (useBookmarkFilter && !isChunkBookmarked(chunk)) return false
        if (useLanguageFilter && !matchesLanguageFilter(chunk.id, languageFilter)) return false
        return true
    }

    private fun matchesLanguageFilter(chunkId: ChunkId, filter: LanguageFilter): Boolean {
        val pair = languagePair ?: return true
        val chunkLang = transcripts[chunkId]?.language ?: return false
        return when (filter) {
            LanguageFilter.ALL -> true
            LanguageFilter.TARGET_ONLY -> chunkLang == pair.target
            LanguageFilter.BASE_ONLY -> pair.base != null && chunkLang == pair.base
        }
    }

    private fun findChunkAt(positionMs: Long): Chunk? = chunks.firstOrNull { positionMs in it.startMs until it.endMs }
        ?: chunks.lastOrNull { it.startMs <= positionMs }

    private fun updateState(chunkId: ChunkId?, autoPauseEndsAt: Long?) {
        val changed = _currentChunkId.value != chunkId || _autoPauseEndsAtMs.value != autoPauseEndsAt
        _currentChunkId.value = chunkId
        _autoPauseEndsAtMs.value = autoPauseEndsAt
        if (changed) {
            onStateChanged(chunkId, autoPauseEndsAt)
        }
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
        ) : BoundaryPayload {
            override val boundaryMs: Long get() = chunk.endMs
        }
    }

    private sealed interface PostChunkAction {
        data class SeekToPosition(val positionMs: Long) : PostChunkAction
        data class SeekToChunk(val targetChunk: Chunk) : PostChunkAction
        data class AdvanceAfter(val finishedChunkEndMs: Long) : PostChunkAction
    }

    companion object {
        /** 自動ポーズ中の WakeLock タイムアウト余裕（ミリ秒、§7.2.4: `actualPauseMs + 5_000L`）。 */
        const val WAKE_LOCK_MARGIN_MS: Long = 5_000L

        private const val WAKE_LOCK_TAG: String = "ChunkFlow:AutoPauseWakeLock"
    }
}
