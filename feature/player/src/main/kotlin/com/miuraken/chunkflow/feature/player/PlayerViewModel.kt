package com.miuraken.chunkflow.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ブラインドモード（スクリプトを隠す学習モード。F7.2、§7.5.5）。
 */
enum class BlindMode {
    /** すべてのテキストを表示する。 */
    OFF,

    /** 学習言語（target）の行だけを隠す（母語を見て学習言語で言ってみる練習）。 */
    HIDE_TARGET_ONLY,

    /** すべての行を隠す（聞き取り練習）。 */
    HIDE_ALL,
}

/**
 * プレイヤー画面での文字起こしファイル取り込み結果の通知状態（F1.3、§7.5.5）。
 */
sealed interface TranscriptImportBannerState {
    /**
     * 文字起こしを取り込んだ。
     *
     * @property chunkCount 作成したチャンク数。
     * @property durationMismatch 音声の長さと 0.5 秒以上の差があるかどうか。
     */
    data class Attached(val chunkCount: Int, val durationMismatch: Boolean) : TranscriptImportBannerState

    /**
     * 対応する音声が一致しなかった。
     *
     * @property audioFileName JSON に記載されていた音声ファイル名。
     */
    data class NoMatchingAudio(val audioFileName: String?) : TranscriptImportBannerState

    /**
     * 文字起こしファイルが不正だった。
     *
     * @property error エラーの種別。
     */
    data class Invalid(val error: TranscriptError) : TranscriptImportBannerState
}

/**
 * チャンク 1 件の UI 表示モデル（§6.2、§7.5.5）。
 *
 * @property id チャンク ID。
 * @property startMs 開始時刻（ミリ秒）。
 * @property endMs 終了時刻（ミリ秒）。
 * @property text 文字起こしテキスト（なければ `null`）。
 * @property language 言語タグ（未判定または信頼度が低い場合は `null`）。
 * @property isBookmarked ブックマーク済みかどうか。
 * @property textVariants 別の字体のテキスト（キーは `"Hans"` / `"Hant"`）。
 * @property origin チャンクの出どころ。
 */
data class ChunkUi(
    val id: ChunkId,
    val startMs: Long,
    val endMs: Long,
    val text: String?,
    val language: LanguageTag?,
    val isBookmarked: Boolean = false,
    val textVariants: Map<String, String> = emptyMap(),
    val origin: ChunkOrigin = ChunkOrigin.AUTO,
) {
    /**
     * 設定された漢字の字体（[chineseScript] / [cantoneseScript]）に従って表示するテキストを返す（§7.5.5）。
     */
    fun resolveDisplayText(
        chineseScript: ChineseScript,
        cantoneseScript: ChineseScript,
    ): String? {
        val raw = text ?: return null
        return when (language?.value?.lowercase(Locale.ROOT)) {
            "zh" -> textVariants[chineseScript.iso15924] ?: raw
            "yue" -> textVariants[cantoneseScript.iso15924] ?: raw
            else -> raw
        }
    }

    /**
     * 行ごとの `LocaleList` に指定する BCP-47 ロケールタグを返す（§7.5.5）。
     *
     * 言語が未判定（`null`）の場合は `null` を返し、端末の既定ロケールを使う。
     */
    fun resolveLocaleTag(
        chineseScript: ChineseScript,
        cantoneseScript: ChineseScript,
    ): String? {
        val rawTag = language?.value?.trim().orEmpty()
        if (rawTag.isEmpty()) return null
        return when (rawTag.lowercase(Locale.ROOT)) {
            "zh" -> "zh-${chineseScript.iso15924}"
            "yue" -> "yue-${cantoneseScript.iso15924}"
            else -> rawTag
        }
    }
}

/**
 * プレイヤー画面の UI 状態（§6.2、F3.1〜F7.2）。
 *
 * 高頻度で変わる再生位置はここには含めず、[PositionProvider] から描画・子 Composable で読む。
 */
data class PlayerUiState(
    val mediaId: MediaId? = null,
    val title: String = "",
    val course: String? = null,
    val languagePair: LanguagePair? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val seekBackMs: Long = UserSettings.DEFAULT_SEEK_BACK_MS,
    val seekForwardMs: Long = UserSettings.DEFAULT_SEEK_FORWARD_MS,
    val practice: PracticeSettings = PracticeSettings(),
    val autoPauseEndsAtMs: Long? = null,
    val waveform: Waveform? = null,
    val chunks: List<ChunkUi> = emptyList(),
    val activeChunkIndex: Int? = null,
    val analysis: Map<AnalysisStage, StageStatus> = emptyMap(),
    val error: PlaybackError? = null,
    val blindMode: BlindMode = BlindMode.OFF,
    val revealedChunkIds: Set<ChunkId> = emptySet(),
    val chineseScript: ChineseScript = ChineseScript.HANS,
    val cantoneseScript: ChineseScript = ChineseScript.HANT,
    val chunkSource: ChunkOrigin = ChunkOrigin.AUTO,
    val availableChunkOrigins: Set<ChunkOrigin> = emptySet(),
    val segmentationParams: SegmentationParams = SegmentationParams(),
    val transcriptImportBanner: TranscriptImportBannerState? = null,
) {
    /** 文字起こしテキストを持つチャンクが 1 件以上あるかどうか。 */
    val hasTranscript: Boolean
        get() = chunks.any { it.text != null }

    companion object {
        /** 再生速度の微調整の刻み幅（0.05 倍、F3.2）。 */
        const val SPEED_STEP: Float = 0.05f

        /** 再生速度のプリセット一覧（F3.2）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}

/** プレイヤー画面のイベント。 */
sealed interface PlayerEvent {
    /** 前の画面に戻る。 */
    data object Back : PlayerEvent

    /** 再生と一時停止を切り替える。 */
    data object TogglePlayPause : PlayerEvent

    /** 指定した位置（ミリ秒）にシークする。 */
    data class SeekTo(val positionMs: Long) : PlayerEvent

    /** 設定された秒数だけ巻き戻す。 */
    data object SeekBack : PlayerEvent

    /** 設定された秒数だけ早送りする。 */
    data object SeekForward : PlayerEvent

    /** 前のチャンク（または先頭）へ移動する（F3.4、F6.1）。 */
    data object PreviousChunk : PlayerEvent

    /** 次のチャンクへ移動する（F3.4、F6.1）。 */
    data object NextChunk : PlayerEvent

    /** 指定したチャンクの頭へシークする（F6.1）。 */
    data class SeekToChunk(val chunkId: ChunkId) : PlayerEvent

    /** 再生速度を指定した倍率（0.5〜2.0 倍）に変更する。 */
    data class SetSpeed(val speed: Float) : PlayerEvent

    /** 現在の再生速度を [delta]（例: `-0.05f` / `+0.05f`）だけ増減する。 */
    data class AdjustSpeed(val delta: Float) : PlayerEvent

    /** リピートモードを変更する（F6.1、F6.2）。 */
    data class SetLoopMode(val loopMode: LoopMode) : PlayerEvent

    /** 自動ポーズの設定を変更する（F6.3）。 */
    data class SetAutoPause(val autoPause: AutoPause) : PlayerEvent

    /** ブラインドモードを変更する（F7.2）。 */
    data class SetBlindMode(val blindMode: BlindMode) : PlayerEvent

    /** ブラインドモード中に特定のチャンクの一時表示を切り替える（F7.2）。 */
    data class ToggleRevealChunk(val chunkId: ChunkId) : PlayerEvent

    /** チャンクの出どころ（文字起こし / 自動分割）を切り替える（F5.4）。 */
    data class SetChunkSource(val origin: ChunkOrigin) : PlayerEvent

    /** 分割パラメータを変更して再分割を実行する（F5.2）。 */
    data class Resegment(val params: SegmentationParams) : PlayerEvent

    /** 文字起こし JSON ファイルを取り込む（F1.3、§7.5.5）。 */
    data class ImportTranscript(val uri: Uri) : PlayerEvent

    /** 文字起こし取り込み結果のバナーを閉じる。 */
    data object DismissTranscriptBanner : PlayerEvent
}

private data class AnalysisSnapshot(
    val status: Map<AnalysisStage, StageStatus> = emptyMap(),
    val waveform: Waveform? = null,
    val chunks: List<Chunk> = emptyList(),
    val transcripts: Map<ChunkId, Transcript> = emptyMap(),
    val availableOrigins: Set<ChunkOrigin> = emptySet(),
)

private data class LocalUiSnapshot(
    val blindMode: BlindMode = BlindMode.OFF,
    val revealedChunkIds: Set<ChunkId> = emptySet(),
    val customSegmentationParams: SegmentationParams? = null,
    val transcriptImportBanner: TranscriptImportBannerState? = null,
)

/**
 * プレイヤー画面の ViewModel（§6.2、F3.1〜F7.2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val analysisRepository: AnalysisRepository,
    private val playbackController: PlaybackController,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        libraryRepository = libraryRepository,
        analysisRepository = FakeAnalysisRepository(),
        playbackController = playbackController,
        userSettingsRepository = userSettingsRepository,
    )

    private val currentMediaId = MutableStateFlow<MediaId?>(null)
    private val localUiState = MutableStateFlow(LocalUiSnapshot())
    private var lastOpenedMediaId: MediaId? = null

    /** 高頻度で変わる再生位置のプロバイダ（§6.2）。 */
    val positionProvider: PositionProvider
        get() = playbackController.position

    private val currentAudioItem = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItem(id)
    }

    private val currentItemState = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItemState(id)
    }

    private val currentAnalysisSnapshot = currentMediaId.flatMapLatest { id ->
        if (id == null) {
            flowOf(AnalysisSnapshot())
        } else {
            combine(
                analysisRepository.observeStatus(id),
                analysisRepository.observeWaveform(id),
                analysisRepository.observeChunks(id),
                analysisRepository.observeTranscripts(id),
                analysisRepository.observeAvailableChunkOrigins(id),
            ) { status, waveform, chunks, transcripts, origins ->
                AnalysisSnapshot(
                    status = status,
                    waveform = waveform,
                    chunks = chunks,
                    transcripts = transcripts,
                    availableOrigins = origins,
                )
            }
        }
    }

    private val mediaAndAnalysisFlow = combine(
        currentMediaId,
        currentAudioItem,
        currentItemState,
        currentAnalysisSnapshot,
    ) { mediaId, item, itemState, analysis ->
        Quad(mediaId, item, itemState, analysis)
    }

    /** プレイヤー画面の UI 状態。 */
    val uiState: StateFlow<PlayerUiState> = combine(
        mediaAndAnalysisFlow,
        playbackController.status,
        playbackController.practice,
        userSettingsRepository.settings,
        localUiState,
    ) { (mediaId, item, itemState, analysis), status, practice, settings, local ->
        buildUiState(
            mediaId = mediaId,
            item = item,
            itemState = itemState,
            analysis = analysis,
            status = status,
            practice = practice,
            settings = settings,
            local = local,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    /** 指定した教材を開いて再生準備を行う。 */
    fun open(mediaId: MediaId) {
        currentMediaId.value = mediaId
        if (lastOpenedMediaId == mediaId) return
        lastOpenedMediaId = mediaId
        localUiState.value = LocalUiSnapshot()
        viewModelScope.launch {
            val savedState = libraryRepository.observeItemState(mediaId).firstOrNull()
            playbackController.open(mediaId)
            if (playbackController is FakePlaybackController && savedState != null) {
                playbackController.position.positionMs = savedState.positionMs
                playbackController.setSpeed(savedState.speed)
            }
        }
    }

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.TogglePlayPause -> togglePlayPause()

            is PlayerEvent.SeekTo -> seekTo(event.positionMs)

            PlayerEvent.SeekBack -> {
                val seekBackMs = uiState.value.seekBackMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos - seekBackMs)
            }

            PlayerEvent.SeekForward -> {
                val seekForwardMs = uiState.value.seekForwardMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos + seekForwardMs)
            }

            PlayerEvent.PreviousChunk -> seekToAdjacentChunk(direction = -1)

            PlayerEvent.NextChunk -> seekToAdjacentChunk(direction = 1)

            is PlayerEvent.SeekToChunk -> seekToChunk(event.chunkId)

            is PlayerEvent.SetSpeed -> setSpeed(event.speed)

            is PlayerEvent.AdjustSpeed -> {
                val currentSpeed = playbackController.status.value.speed
                setSpeed(currentSpeed + event.delta)
            }

            is PlayerEvent.SetLoopMode -> {
                playbackController.updatePractice { it.copy(loop = event.loopMode) }
            }

            is PlayerEvent.SetAutoPause -> {
                playbackController.updatePractice { it.copy(autoPause = event.autoPause) }
            }

            is PlayerEvent.SetBlindMode -> {
                localUiState.update {
                    it.copy(
                        blindMode = event.blindMode,
                        revealedChunkIds = emptySet(),
                    )
                }
            }

            is PlayerEvent.ToggleRevealChunk -> {
                localUiState.update { current ->
                    val nextRevealed = if (event.chunkId in current.revealedChunkIds) {
                        current.revealedChunkIds - event.chunkId
                    } else {
                        current.revealedChunkIds + event.chunkId
                    }
                    current.copy(revealedChunkIds = nextRevealed)
                }
            }

            is PlayerEvent.SetChunkSource -> {
                val id = uiState.value.mediaId ?: currentMediaId.value ?: return
                viewModelScope.launch {
                    analysisRepository.setChunkSource(id, event.origin)
                }
            }

            is PlayerEvent.Resegment -> {
                val id = uiState.value.mediaId ?: currentMediaId.value ?: return
                localUiState.update { it.copy(customSegmentationParams = event.params) }
                viewModelScope.launch {
                    analysisRepository.resegment(id, event.params)
                }
            }

            is PlayerEvent.ImportTranscript -> {
                viewModelScope.launch {
                    val result = libraryRepository.importTranscript(event.uri)
                    val banner = when (result) {
                        is TranscriptImportResult.Attached ->
                            TranscriptImportBannerState.Attached(
                                chunkCount = result.chunkCount,
                                durationMismatch = result.durationMismatch,
                            )

                        is TranscriptImportResult.NoMatchingAudio ->
                            TranscriptImportBannerState.NoMatchingAudio(result.audioFileName)

                        is TranscriptImportResult.Invalid ->
                            TranscriptImportBannerState.Invalid(result.error)
                    }
                    localUiState.update { it.copy(transcriptImportBanner = banner) }
                }
            }

            PlayerEvent.DismissTranscriptBanner -> {
                localUiState.update { it.copy(transcriptImportBanner = null) }
            }

            PlayerEvent.Back -> Unit
        }
    }

    private fun togglePlayPause() {
        val player = playbackController.player.value
        if (player != null) {
            if (playbackController.status.value.isPlaying || player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { it.copy(isPlaying = !it.isPlaying, autoPauseEndsAtMs = null) }
        }
    }

    private fun seekTo(targetPositionMs: Long) {
        val durationMs = uiState.value.durationMs
        val clampedMs = if (durationMs > 0L) {
            targetPositionMs.coerceIn(0L, durationMs)
        } else {
            targetPositionMs.coerceAtLeast(0L)
        }
        val player = playbackController.player.value
        if (player != null) {
            player.seekTo(clampedMs)
        }
        if (playbackController is FakePlaybackController) {
            playbackController.position.positionMs = clampedMs
            val matchingChunk = uiState.value.chunks.firstOrNull { clampedMs in it.startMs..it.endMs }
            if (matchingChunk != null) {
                playbackController.statusState.update { it.copy(currentChunkId = matchingChunk.id) }
            }
        }
    }

    private fun seekToChunk(chunkId: ChunkId) {
        playbackController.seekToChunk(chunkId)
        if (playbackController is FakePlaybackController) {
            val targetChunk = uiState.value.chunks.firstOrNull { it.id == chunkId }
            if (targetChunk != null) {
                playbackController.position.positionMs = targetChunk.startMs
            }
        }
    }

    private fun seekToAdjacentChunk(direction: Int) {
        val player = playbackController.player.value
        if (player != null) {
            if (direction < 0) {
                player.seekToPrevious()
            } else {
                player.seekToNext()
            }
            return
        }
        val chunks = uiState.value.chunks
        if (chunks.isEmpty()) {
            if (direction < 0) seekTo(0L)
            return
        }
        val currentIdx = uiState.value.activeChunkIndex
            ?: chunks.indexOfFirst { playbackController.position.currentPositionMs() in it.startMs..it.endMs }
                .takeIf { it >= 0 }
            ?: 0
        val targetIdx = (currentIdx + direction).coerceIn(0, chunks.lastIndex)
        seekToChunk(chunks[targetIdx].id)
    }

    private fun setSpeed(rawSpeed: Float) {
        val clamped = rawSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val rounded = (clamped * 20f).roundToInt() / 20f
        playbackController.setSpeed(rounded.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED))
    }

    private fun buildUiState(
        mediaId: MediaId?,
        item: AudioItem?,
        itemState: ItemState?,
        analysis: AnalysisSnapshot,
        status: PlaybackStatus,
        practice: PracticeSettings,
        settings: UserSettings,
        local: LocalUiSnapshot,
    ): PlayerUiState {
        val resolvedDurationMs = when {
            status.durationMs > 0L -> status.durationMs
            item != null && item.durationMs > 0L -> item.durationMs
            analysis.waveform != null -> analysis.waveform.durationMs
            else -> 0L
        }
        val chunkUis = analysis.chunks.map { chunk ->
            val transcript = analysis.transcripts[chunk.id]
            ChunkUi(
                id = chunk.id,
                startMs = chunk.startMs,
                endMs = chunk.endMs,
                text = transcript?.text,
                language = transcript?.language,
                isBookmarked = false,
                textVariants = transcript?.textVariants.orEmpty(),
                origin = chunk.origin,
            )
        }
        val activeIndex = status.currentChunkId?.let { activeId ->
            chunkUis.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
        }
        val resolvedOrigin = analysis.chunks.firstOrNull()?.origin
            ?: itemState?.chunkSource
            ?: ChunkOrigin.AUTO

        return PlayerUiState(
            mediaId = mediaId ?: status.mediaId ?: item?.id,
            title = item?.title.orEmpty(),
            course = item?.course,
            languagePair = item?.languagePair,
            isPlaying = status.isPlaying,
            durationMs = resolvedDurationMs,
            speed = status.speed,
            seekBackMs = settings.seekBackMs,
            seekForwardMs = settings.seekForwardMs,
            practice = practice,
            autoPauseEndsAtMs = status.autoPauseEndsAtMs,
            waveform = analysis.waveform,
            chunks = chunkUis,
            activeChunkIndex = activeIndex,
            analysis = analysis.status,
            error = status.error,
            blindMode = local.blindMode,
            revealedChunkIds = local.revealedChunkIds,
            chineseScript = settings.chineseScript,
            cantoneseScript = settings.cantoneseScript,
            chunkSource = resolvedOrigin,
            availableChunkOrigins = analysis.availableOrigins,
            segmentationParams = local.customSegmentationParams ?: settings.defaultSegmentationParams,
            transcriptImportBanner = local.transcriptImportBanner,
        )
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
