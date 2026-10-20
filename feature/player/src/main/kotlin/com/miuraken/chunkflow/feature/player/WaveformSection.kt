package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * チャンクの言語タグ [chunkLanguage] が役割の言語タグ [roleLanguage] と一致するかどうかを判定する。
 *
 * 大文字小文字を無視し、BCP-47 の主言語サブタグ（例: `ja-JP` と `ja`、`ko-KR` と `ko`）の一致も許容する。
 */
internal fun matchesLanguageRole(
    chunkLanguage: LanguageTag?,
    roleLanguage: LanguageTag?,
): Boolean {
    val chunkTag = chunkLanguage?.value?.trim()?.takeUnless {
        it.isEmpty() || it.equals("und", ignoreCase = true)
    } ?: return false
    val roleTag = roleLanguage?.value?.trim()?.takeUnless {
        it.isEmpty() || it.equals("und", ignoreCase = true)
    } ?: return false
    if (chunkTag.equals(roleTag, ignoreCase = true)) return true
    val chunkPrimary = chunkTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
    val rolePrimary = roleTag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
    if (chunkPrimary.isEmpty() || chunkPrimary != rolePrimary) return false
    val chunkChineseVariant = explicitChineseScriptForTag(chunkTag)
    val roleChineseVariant = explicitChineseScriptForTag(roleTag)
    if (chunkChineseVariant != null && roleChineseVariant != null && chunkChineseVariant != roleChineseVariant) {
        return false
    }
    return true
}

/** 拡大波形の既定の表示窓（30 秒 = 30,000ms）。 */
internal const val DEFAULT_ZOOM_WINDOW_MS: Long = 30_000L

/** 拡大波形で、15 秒の解像度の近傍画像に切り替える表示窓の閾値（15 秒 = 15,000ms）。 */
internal const val HIGH_RES_ZOOM_THRESHOLD_MS: Long = 15_000L

/** 拡大波形で、さらに細かい 7.5 秒の解像度の近傍画像に切り替える表示窓の閾値（7.5 秒 = 7,500ms）。 */
internal const val FINE_ZOOM_THRESHOLD_MS: Long = 7_500L

/** 拡大波形の最小表示窓（2 秒 = 2,000ms、§7.5.4）。 */
internal const val MIN_ZOOM_WINDOW_MS: Long = 2_000L

/** ピンチイン・ピンチアウト完了後に高精度波形を再描画するまでの遅延（0.2 秒 = 200ms）。 */
internal const val ZOOM_SETTLE_DELAY_MS: Long = 200L

/** 未完成の波形を表示時に左から右へ漸次的に更新表示するアニメーションの所要時間（ミリ秒）。 */
private const val PROGRESSIVE_WAVEFORM_REVEAL_DURATION_MS: Long = 320L

/**
 * 波形上に描くチャンク面の角丸半径。
 * 波形は Canvas に密に並ぶ内容（部品ではない）なので、M3 の Shape scale ではなく波形固有の寸法として持つ。
 * チャンクの高さ（数十 dp）に対して十分小さく、隣接チャンクの 1dp の隙間と干渉しない値。
 */
private val WAVEFORM_CHUNK_CORNER_RADIUS: Dp = 4.dp

/** ミニマップ（全体波形）の再生位置バーの幅。角丸半径は幅の半分（ピル形）。 */
private val MINIMAP_PLAYHEAD_BAR_WIDTH: Dp = 4.dp

/** ミニマップの再生位置バーの上下に付けるつまみの幅。 */
private val MINIMAP_PLAYHEAD_KNOB_WIDTH: Dp = 10.dp

/** ミニマップの再生位置バーのつまみの高さ。角丸半径は高さの半分（ピル形）。 */
private val MINIMAP_PLAYHEAD_KNOB_HEIGHT: Dp = 6.dp

private const val MAX_BITMAP_TILE_WIDTH_PX: Int = 2048
private const val MAX_TOTAL_STRIP_WIDTH_PX: Int = 16_384

/** 近傍画像の幅の上限（px）。 */
private const val MAX_LOCAL_STRIP_WIDTH_PX: Int = 8_192

/** 近傍画像で描く時間幅（解像度の何倍か）。 */
internal const val LOCAL_STRIP_SPAN_FACTOR: Long = 4L

/** 近傍画像の中心時刻を見直す間隔（ミリ秒）。 */
private const val ANCHOR_POLL_INTERVAL_MS: Long = 100L

/** 拡大波形で、1 つおきのチャンクの背景を薄くするときの濃さ（不透明度の倍率）。 */
private const val ALTERNATE_CHUNK_BACKGROUND_EMPHASIS: Float = 0.78f

/**
 * 既存の波形を表示した上で左から右へ漸次的に更新表示する際の境界時刻（ミリ秒）を計算する。
 *
 * [revealFraction] が 0f のときは [startMs]、1f のときは [endMs] を返す。
 */
internal fun computeProgressiveRevealCutoffMs(
    startMs: Long,
    endMs: Long,
    revealFraction: Float,
): Long {
    if (endMs <= startMs) return startMs
    val clamped = revealFraction.coerceIn(0f, 1f)
    if (clamped <= 0f) return startMs
    if (clamped >= 1f) return endMs
    return (startMs + ((endMs - startMs).toDouble() * clamped.toDouble()).roundToLong())
        .coerceIn(startMs, endMs)
}

/**
 * 表示窓 [windowMs] に対して、近傍画像をレンダリングする解像度（その表示窓で画面幅いっぱいになる時間幅）を返す。
 *
 * [HIGH_RES_ZOOM_THRESHOLD_MS]（15 秒）以上では全体画像（30 秒の解像度）を使うため `null` を返す。
 */
internal fun zoomRenderLevelWindowMs(windowMs: Long): Long? = when {
    windowMs < FINE_ZOOM_THRESHOLD_MS -> FINE_ZOOM_THRESHOLD_MS
    windowMs < HIGH_RES_ZOOM_THRESHOLD_MS -> HIGH_RES_ZOOM_THRESHOLD_MS
    else -> null
}

/**
 * プレイヤー画面の波形セクション（全体波形 + 拡大波形。F4.1、F4.2、F6.2、§7.5.4）。
 *
 * - [positionProvider] は描画フェーズ（`onDrawBehind`）でのみ読み取り、再生位置の変化で再コンポーズしない（§6.2）。
 * - 再生中のみ `withFrameNanos` で描画レイヤーの無効化を行う。
 * - 拡大波形は [DEFAULT_ZOOM_WINDOW_MS]（30 秒）の解像度で全体波形をアルファチャンネル付き画像（[ImageBitmap]）として
 *   レンダリング・キャッシュし、水平方向の拡大縮小でスライス表示する。表示幅が [HIGH_RES_ZOOM_THRESHOLD_MS]（15 秒）より
 *   短くなったら 15 秒の解像度、[FINE_ZOOM_THRESHOLD_MS]（7.5 秒）より短くなったら 7.5 秒の解像度で現在位置付近の波形を
 *   画像として作り直し、それを拡大縮小して表示する。
 * - 拡大波形のチャンクは背景色で区別し、タップでそのチャンクの冒頭から再生、長押しで選択（チャンクリストのチェックボックスと連動）する。
 *   長押ししたまま指を動かすと、通ったチャンクもまとめて選択（または選択解除）する。
 * - 画面端のドラッグがシステムの「戻る」ジェスチャーと競合しないよう `Modifier.systemGestureExclusion()` を適用する。
 */
@Composable
fun WaveformSection(
    waveform: Waveform?,
    durationMs: Long,
    isPlaying: Boolean,
    chunks: List<ChunkUi>,
    activeChunkIndex: Int?,
    languagePair: LanguagePair?,
    loopMode: LoopMode,
    analysis: Map<AnalysisStage, StageStatus>,
    positionProvider: PositionProvider,
    onSeekTo: (Long) -> Unit,
    onSetAbRange: (Long, Long) -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
    modifier: Modifier = Modifier,
    seekBackSeconds: Int = 3,
    onSeekToChunk: (ChunkId) -> Unit = {},
    onSetChunkSelected: (ChunkId, Boolean) -> Unit = { _, _ -> },
    onDismissAbRangeAndSeek: (Long) -> Unit = onSeekTo,
    lookaheadStatus: com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus =
        com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(),
    isTranscriptionRunning: Boolean = false,
    baseChunks: List<ChunkUi> = emptyList(),
    isOverviewWaveformRenderComplete: Boolean = true,
    isZoomedWaveformRenderComplete: Boolean = true,
    renderGeneration: Long = 0L,
    onOverviewWaveformRenderComplete: () -> Unit = {},
    onZoomedWaveformRenderComplete: () -> Unit = {},
) {
    val effectiveDurationMs = max(durationMs, waveform?.durationMs ?: 0L).coerceAtLeast(1L)
    val featureStatus = analysis[AnalysisStage.FEATURES]
    val segmentationStatus = analysis[AnalysisStage.SEGMENTATION]
    val downsampler = remember { WaveformDownsampler() }
    val isInspectionMode = LocalInspectionMode.current

    // 描画フェーズでのみ読むフレームカウンタ（コンポーズ中には読まない）
    val frameTick = remember { mutableLongStateOf(0L) }
    if (!isInspectionMode) {
        LaunchedEffect(isPlaying, positionProvider) {
            var lastPosMs = -1L
            while (isActive) {
                if (isPlaying) {
                    withFrameNanos { frameNanos ->
                        frameTick.longValue = frameNanos
                        lastPosMs = positionProvider.currentPositionMs()
                    }
                } else {
                    val currentPosMs = positionProvider.currentPositionMs()
                    if (currentPosMs != lastPosMs) {
                        lastPosMs = currentPosMs
                        frameTick.longValue += 1L
                    }
                    delay(50L)
                }
            }
        }
    }

    val maxWindowMs = max(MIN_ZOOM_WINDOW_MS, effectiveDurationMs)
    var zoomWindowMs by remember(effectiveDurationMs) {
        mutableLongStateOf(DEFAULT_ZOOM_WINDOW_MS.coerceIn(MIN_ZOOM_WINDOW_MS, maxWindowMs))
    }

    // 拡大波形の近傍画像（15 秒 / 7.5 秒の解像度）を描く範囲の中心時刻。
    // プログレッシブ波形更新のたびにリセットされてちらつくのを防ぐため effectiveDurationMs をキーにする
    var cachedAnchorCenterMs by remember(effectiveDurationMs) {
        mutableLongStateOf(positionProvider.currentPositionMs().coerceIn(0L, effectiveDurationMs))
    }

    // ドラッグスクラブ中または A-B 範囲選択中の一時状態
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    var abDragStartMs by remember { mutableStateOf<Long?>(null) }
    var abDragCurrentMs by remember { mutableStateOf<Long?>(null) }

    val overviewCd = stringResource(R.string.player_waveform_overview_cd)
    val zoomedCd = stringResource(R.string.player_waveform_zoomed_cd)
    val prevChunkActionLabel = stringResource(R.string.player_prev_chunk_cd)
    val nextChunkActionLabel = stringResource(R.string.player_next_chunk_cd)
    val seekBackActionLabel = stringResource(R.string.player_seek_back_cd, seekBackSeconds)

    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val playheadColor = colorScheme.error
    val boundaryTickColor = colorScheme.outline
    val viewportStrokeColor = colorScheme.secondary
    val viewportFillColor = colorScheme.secondary.copy(alpha = 0.14f)
    val targetWavePeakColor = langPalette.targetWaveformPeak
    val targetWaveRmsColor = langPalette.targetWaveformRms
    val targetActiveBorderColor = langPalette.onTargetBadgeContainer.copy(alpha = 0.65f)
    val baseWavePeakColor = langPalette.baseWaveformPeak
    val baseWaveRmsColor = langPalette.baseWaveformRms
    val baseActiveBorderColor = langPalette.onBaseBadgeContainer.copy(alpha = 0.65f)
    val unknownWavePeakColor = colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
    val unknownWaveRmsColor = colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val unknownActiveBorderColor = colorScheme.primary.copy(alpha = 0.60f)
    val abLoopHighlightColor = colorScheme.tertiary.copy(alpha = 0.30f)
    val abLoopBorderColor = colorScheme.tertiary
    // 拡大波形のチャンク背景（非チャンク区間 surfaceContainerLow と明確に見分けられる濃さで、隣り合うチャンクは濃さを交互に変える）
    val isDarkTheme = colorScheme.surface.luminance() < 0.5f
    val targetChunkBackgroundColor = langPalette.targetChunkBackground
    val baseChunkBackgroundColor = langPalette.baseChunkBackground
    val unknownChunkBackgroundColor = colorScheme.onSurfaceVariant.copy(alpha = if (isDarkTheme) 0.34f else 0.24f)
    val selectedChunkBackgroundColor = colorScheme.primary.copy(alpha = 0.16f)
    val selectedChunkBorderColor = colorScheme.primary
    val selectedChunkCheckColor = colorScheme.onPrimary
    val dummyWaveColor = colorScheme.outlineVariant.copy(alpha = 0.35f)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. 全体波形ヘッダ（左上は現在の再生箇所「分:秒」、右上は全体長「分:秒」）+ キャンバス
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WaveformOverviewPositionText(
                    durationMs = effectiveDurationMs,
                    isPlaying = isPlaying,
                    positionProvider = positionProvider,
                    scrubPositionMs = scrubPositionMs,
                )
                Text(
                    text = formatDurationMs(effectiveDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            val showOverviewBoundaryTicks = remember(
                featureStatus,
                segmentationStatus,
                lookaheadStatus,
                chunks,
            ) {
                shouldShowOverviewChunkBoundaryTicks(
                    featureStatus = featureStatus,
                    segmentationStatus = segmentationStatus,
                    lookaheadStatus = lookaheadStatus,
                    chunks = chunks,
                )
            }

            OverviewWaveformCanvas(
                waveform = waveform,
                durationMs = effectiveDurationMs,
                zoomWindowMs = zoomWindowMs,
                chunks = chunks,
                baseChunks = baseChunks,
                isRenderComplete = isOverviewWaveformRenderComplete,
                renderGeneration = renderGeneration,
                onRenderComplete = onOverviewWaveformRenderComplete,
                showBoundaryTicks = showOverviewBoundaryTicks,
                languagePair = languagePair,
                loopMode = loopMode,
                downsampler = downsampler,
                positionProvider = positionProvider,
                scrubPositionMs = scrubPositionMs,
                frameTick = { frameTick.longValue },
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                playheadColor = playheadColor,
                boundaryTickColor = boundaryTickColor,
                viewportStrokeColor = viewportStrokeColor,
                viewportFillColor = viewportFillColor,
                abLoopHighlightColor = abLoopHighlightColor,
                contentDescriptionText = overviewCd,
                prevChunkActionLabel = prevChunkActionLabel,
                nextChunkActionLabel = nextChunkActionLabel,
                seekBackActionLabel = seekBackActionLabel,
                dummyWaveColor = dummyWaveColor,
                onSeekTo = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = null
                    cachedAnchorCenterMs = clamped
                    onSeekTo(clamped)
                },
                onScrubChange = { targetMs ->
                    // ドラッグ中の中心時刻の更新は拡大波形の側で間引いて行う（イベントごとに近傍画像を作り直さない）
                    scrubPositionMs = targetMs.coerceIn(0L, effectiveDurationMs)
                },
                onPreviousChunk = onPreviousChunk,
                onNextChunk = onNextChunk,
                onSeekBack = onSeekBack,
            )

            // 2. 拡大波形ヘッダ（波形解析中・文字起こし中はステータス表示領域＋背景プログレスバーとして使用）+ キャンバス
            ZoomedWaveformHeaderStatusArea(
                waveform = waveform,
                featureStatus = featureStatus,
                segmentationStatus = segmentationStatus,
                chunks = chunks,
                lookaheadStatus = lookaheadStatus,
                isTranscriptionRunning = isTranscriptionRunning,
                zoomWindowMs = zoomWindowMs,
            )

            ZoomedWaveformCanvas(
                waveform = waveform,
                durationMs = effectiveDurationMs,
                zoomWindowMs = zoomWindowMs,
                cachedAnchorCenterMs = cachedAnchorCenterMs,
                onUpdateAnchorCenter = { newAnchor -> cachedAnchorCenterMs = newAnchor },
                chunks = chunks,
                baseChunks = baseChunks,
                isRenderComplete = isZoomedWaveformRenderComplete,
                renderGeneration = renderGeneration,
                onRenderComplete = onZoomedWaveformRenderComplete,
                activeChunkIndex = activeChunkIndex,
                languagePair = languagePair,
                loopMode = loopMode,
                downsampler = downsampler,
                positionProvider = positionProvider,
                scrubPositionMs = scrubPositionMs,
                abDragStartMs = abDragStartMs,
                abDragCurrentMs = abDragCurrentMs,
                frameTick = { frameTick.longValue },
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                playheadColor = playheadColor,
                targetActiveBorderColor = targetActiveBorderColor,
                baseActiveBorderColor = baseActiveBorderColor,
                unknownActiveBorderColor = unknownActiveBorderColor,
                abLoopHighlightColor = abLoopHighlightColor,
                abLoopBorderColor = abLoopBorderColor,
                targetChunkBackgroundColor = targetChunkBackgroundColor,
                baseChunkBackgroundColor = baseChunkBackgroundColor,
                unknownChunkBackgroundColor = unknownChunkBackgroundColor,
                selectedChunkBackgroundColor = selectedChunkBackgroundColor,
                selectedChunkBorderColor = selectedChunkBorderColor,
                selectedChunkCheckColor = selectedChunkCheckColor,
                dummyWaveColor = dummyWaveColor,
                onSetChunkSelected = onSetChunkSelected,
                contentDescriptionText = zoomedCd,
                prevChunkActionLabel = prevChunkActionLabel,
                nextChunkActionLabel = nextChunkActionLabel,
                seekBackActionLabel = seekBackActionLabel,
                // 拡大波形の中での移動は表示窓の近くに限られるため、近傍画像の中心時刻は拡大波形の側で
                // 範囲から外れそうになったときだけ更新する（パンのイベントごとに近傍画像を作り直さない）
                onSeekTo = { targetMs ->
                    scrubPositionMs = null
                    onSeekTo(targetMs.coerceIn(0L, effectiveDurationMs))
                },
                onDismissAbRangeAndSeek = { targetMs ->
                    scrubPositionMs = null
                    onDismissAbRangeAndSeek(targetMs.coerceIn(0L, effectiveDurationMs))
                },
                onSeekToChunk = { chunkId ->
                    scrubPositionMs = null
                    onSeekToChunk(chunkId)
                },
                onScrubChange = { targetMs ->
                    scrubPositionMs = targetMs.coerceIn(0L, effectiveDurationMs)
                },
                onZoomWindowChange = { newWindowMs ->
                    zoomWindowMs = newWindowMs.coerceIn(MIN_ZOOM_WINDOW_MS, maxWindowMs)
                },
                onAbDragUpdate = { startMs, currentMs ->
                    abDragStartMs = startMs
                    abDragCurrentMs = currentMs
                },
                onAbDragFinish = {
                    val start = abDragStartMs
                    val end = abDragCurrentMs
                    abDragStartMs = null
                    abDragCurrentMs = null
                    if (start != null && end != null) {
                        val minMs = min(start, end).coerceIn(0L, effectiveDurationMs)
                        val maxMs = max(start, end).coerceIn(0L, effectiveDurationMs)
                        if (maxMs - minMs >= 100L) {
                            onSetAbRange(minMs, maxMs)
                        }
                    }
                },
                onPreviousChunk = onPreviousChunk,
                onNextChunk = onNextChunk,
                onSeekBack = onSeekBack,
            )
        }
    }
}

/**
 * 拡大波形の上部ヘッダ領域。
 *
 * - 波形解析中や文字起こし中はステータス表示領域として使用し、背景をプログレスバーに見立てて
 *   薄いバックグラウンドカラーの変化で進捗度（波形解析の進捗率、または文字起こし済チャンク数/総チャンク数）を表示する。
 * - 文字起こし中はステータスログ（処理中のチャンク番号・ステップ・詳細・経過秒数・進捗数）を表示する。
 * - 波形解析・文字起こしがすべて完了（または待機中）したら、通常の「拡大波形」〜「表示幅: 00:30」表示に戻す。
 */
@Composable
private fun ZoomedWaveformHeaderStatusArea(
    waveform: Waveform?,
    featureStatus: StageStatus?,
    segmentationStatus: StageStatus?,
    chunks: List<ChunkUi>,
    lookaheadStatus: com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus,
    isTranscriptionRunning: Boolean,
    zoomWindowMs: Long,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isInspectionMode = LocalInspectionMode.current

    val isWaveformAnalyzing = featureStatus is StageStatus.Running ||
        segmentationStatus is StageStatus.Running ||
        (waveform == null && featureStatus !is StageStatus.Done && featureStatus !is StageStatus.Failed)
    val isWaveformFailed = featureStatus is StageStatus.Failed

    val inFlightStartedAt = lookaheadStatus.inFlightStartedAtEpochMs
    var nowEpochMs by remember(inFlightStartedAt) {
        mutableLongStateOf(inFlightStartedAt ?: 0L)
    }
    if (!isInspectionMode && isTranscriptionRunning && inFlightStartedAt != null) {
        LaunchedEffect(inFlightStartedAt) {
            while (isActive) {
                nowEpochMs = System.currentTimeMillis()
                delay(500L)
            }
        }
    }

    when {
        isWaveformAnalyzing -> {
            val featureProgress = (featureStatus as? StageStatus.Running)?.progress
            val rawProgress = featureProgress
                ?: (segmentationStatus as? StageStatus.Running)?.progress
            val progressFraction = (rawProgress ?: 0f).coerceIn(0f, 1f)
            val statusText = when {
                featureProgress != null ->
                    stringResource(
                        R.string.player_waveform_analyzing_percent,
                        (featureProgress * 100f).roundToInt().coerceIn(0, 100),
                    )

                featureStatus is StageStatus.Running || segmentationStatus is StageStatus.Running ->
                    stringResource(R.string.player_waveform_analyzing)

                else -> stringResource(R.string.player_waveform_preparing)
            }
            val trackColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
            val fillColor = colorScheme.primary.copy(alpha = 0.22f)
            val progressCornerSize = MaterialTheme.shapes.extraSmall.topStart
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .drawWithCache {
                        onDrawBehind {
                            val cornerPx = progressCornerSize.toPx(size, this)
                            val corner = CornerRadius(cornerPx, cornerPx)
                            drawRoundRect(color = trackColor, cornerRadius = corner)
                            if (progressFraction > 0f) {
                                drawRoundRect(
                                    color = fillColor,
                                    size = Size(size.width * progressFraction, size.height),
                                    cornerRadius = corner,
                                )
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (rawProgress != null) {
                    Text(
                        text = "${(progressFraction * 100f).roundToInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        isWaveformFailed -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_waveform_analysis_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.error,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(
                        R.string.player_waveform_zoom_window,
                        formatDurationMs(zoomWindowMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        isTranscriptionRunning -> {
            val transcribedChunkCount = chunks.count { !it.text.isNullOrBlank() }
            val progressSummary = computeTranscriptionProgressSummary(
                lookaheadStatus = lookaheadStatus,
                transcribedChunkCount = transcribedChunkCount,
                totalChunkCount = chunks.size,
            )
            val totalCount = progressSummary.totalCount
            val completedCount = progressSummary.completedCount
            val progressFraction = progressSummary.overallProgress.coerceIn(0f, 1f)

            val activeChunkNumber = lookaheadStatus.inFlightChunkId?.let { inFlightId ->
                chunks.indexOfFirst { it.id == inFlightId }.takeIf { it >= 0 }?.plus(1)
            }
            val elapsedMs = if (inFlightStartedAt != null && nowEpochMs >= inFlightStartedAt) {
                (nowEpochMs - inFlightStartedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            val isStalled = elapsedMs >= 8_000L
            val stepLabel = transcriptionStepLabel(lookaheadStatus.inFlightStep)
            val chunkPrefix = if (activeChunkNumber != null) {
                "#$activeChunkNumber: "
            } else {
                ""
            }
            val detailSuffix = lookaheadStatus.inFlightDetail
                ?.takeIf { it.isNotBlank() }
                ?.let { " [$it]" }
                .orEmpty()
            val elapsedSuffix = if (elapsedMs > 0L) {
                String.format(Locale.ROOT, " (%.1fs)", elapsedMs / 1_000f)
            } else {
                ""
            }
            val stallSuffix = if (isStalled) {
                " " + stringResource(R.string.player_transcription_stall_short)
            } else {
                ""
            }
            val logText = "$chunkPrefix$stepLabel$detailSuffix$elapsedSuffix$stallSuffix"

            val trackColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
            val fillColor = colorScheme.primary.copy(alpha = 0.22f)
            val progressCornerSize = MaterialTheme.shapes.extraSmall.topStart
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .drawWithCache {
                        onDrawBehind {
                            val cornerPx = progressCornerSize.toPx(size, this)
                            val corner = CornerRadius(cornerPx, cornerPx)
                            drawRoundRect(color = trackColor, cornerRadius = corner)
                            if (progressFraction > 0f) {
                                drawRoundRect(
                                    color = fillColor,
                                    size = Size(size.width * progressFraction, size.height),
                                    cornerRadius = corner,
                                )
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStalled) {
                    // 遅延の警告は絵文字ではなくアイコンで示す（文言は stall_short のまま）
                    Icon(
                        imageVector = ChunkFlowIcons.Schedule,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .size(12.dp),
                    )
                }
                Text(
                    text = logText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (totalCount > 0) {
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = "$completedCount/$totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        else -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_waveform_zoomed_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.player_waveform_zoom_window,
                        formatDurationMs(zoomWindowMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 全体波形の下部にチャンク境界の目盛り（オーバーレイ）を表示するかどうかを判定する。
 *
 * チャンク分割時（特徴量抽出・チャンク分割・言語判別が進行中、またはまだ言語判別結果がない段階）にのみ表示し、
 * 言語判別が完了した段階では非表示にして言語別の色付き波形のみを表示する。
 */
internal fun shouldShowOverviewChunkBoundaryTicks(
    featureStatus: StageStatus?,
    segmentationStatus: StageStatus?,
    lookaheadStatus: com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus,
    chunks: List<ChunkUi>,
): Boolean {
    if (chunks.isEmpty()) return false
    if (
        featureStatus is StageStatus.Running ||
        segmentationStatus is StageStatus.Running ||
        lookaheadStatus.isLanguageDetectionRunning ||
        lookaheadStatus.inFlightStep == com.miuraken.chunkflow.core.model.TranscriptionStep.DETECTING_LANGUAGE
    ) {
        return true
    }
    val hasAnyResolvedLanguage = chunks.any { chunk ->
        val tag = chunk.language?.value?.trim()
        !tag.isNullOrEmpty() && !tag.equals("und", ignoreCase = true)
    }
    return !hasAnyResolvedLanguage
}

@Composable
private fun OverviewWaveformCanvas(
    waveform: Waveform?,
    durationMs: Long,
    zoomWindowMs: Long,
    chunks: List<ChunkUi>,
    baseChunks: List<ChunkUi>,
    isRenderComplete: Boolean,
    renderGeneration: Long,
    onRenderComplete: () -> Unit,
    showBoundaryTicks: Boolean,
    languagePair: LanguagePair?,
    loopMode: LoopMode,
    downsampler: WaveformDownsampler,
    positionProvider: PositionProvider,
    scrubPositionMs: Long?,
    frameTick: () -> Long,
    unknownWavePeakColor: Color,
    unknownWaveRmsColor: Color,
    targetWavePeakColor: Color,
    targetWaveRmsColor: Color,
    baseWavePeakColor: Color,
    baseWaveRmsColor: Color,
    playheadColor: Color,
    boundaryTickColor: Color,
    viewportStrokeColor: Color,
    viewportFillColor: Color,
    abLoopHighlightColor: Color,
    contentDescriptionText: String,
    prevChunkActionLabel: String,
    nextChunkActionLabel: String,
    seekBackActionLabel: String,
    dummyWaveColor: Color,
    onSeekTo: (Long) -> Unit,
    onScrubChange: (Long) -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
) {
    val isInspectionMode = LocalInspectionMode.current
    val currentOnRenderComplete by rememberUpdatedState(onRenderComplete)
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    var canvasHeightPx by remember { mutableIntStateOf(0) }

    val chunkColorKeys = remember(chunks) {
        chunks.map {
            ChunkColorKey(
                startMs = it.startMs,
                endMs = it.endMs,
                language = it.language,
                isTranscribed = !it.text.isNullOrBlank(),
                isSkipped = it.isSkipped,
            )
        }
    }
    val effectiveBaseChunks = remember(baseChunks, chunks) {
        baseChunks.ifEmpty { chunks.map { it.copy(language = null, text = null) } }
    }
    val baseChunkColorKeys = remember(effectiveBaseChunks) {
        effectiveBaseChunks.map {
            ChunkColorKey(
                startMs = it.startMs,
                endMs = it.endMs,
                language = it.language,
                isTranscribed = !it.text.isNullOrBlank(),
                isSkipped = it.isSkipped,
            )
        }
    }

    var revealFraction by remember(renderGeneration) {
        mutableFloatStateOf(if (isInspectionMode || isRenderComplete) 1f else 0f)
    }
    if (!isInspectionMode) {
        LaunchedEffect(renderGeneration, chunkColorKeys, waveform, isRenderComplete) {
            if (waveform == null || chunks.isEmpty()) {
                revealFraction = 0f
                return@LaunchedEffect
            }
            if (isRenderComplete) {
                revealFraction = 1f
                return@LaunchedEffect
            }
            val shouldAnimate = baseChunkColorKeys != chunkColorKeys
            if (shouldAnimate) {
                revealFraction = 0f
                val startNanos = withFrameNanos { it }
                val durationNanos = PROGRESSIVE_WAVEFORM_REVEAL_DURATION_MS * 1_000_000L
                while (isActive) {
                    val nowNanos = withFrameNanos { it }
                    val progress = ((nowNanos - startNanos).toDouble() / durationNanos.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
                    revealFraction = progress
                    if (progress >= 1f) break
                }
            } else {
                revealFraction = 1f
            }
            currentOnRenderComplete()
        }
    }

    val cachedOverviewStrip = remember(
        renderGeneration,
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        chunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = 0L,
                endMs = durationMs,
                totalWidthPx = canvasWidthPx,
                heightPx = canvasHeightPx,
                chunks = chunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    val cachedBaseOverviewStrip = remember(
        renderGeneration,
        isRenderComplete,
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        baseChunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        if (isInspectionMode || isRenderComplete || canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = 0L,
                endMs = durationMs,
                totalWidthPx = canvasWidthPx,
                heightPx = canvasHeightPx,
                chunks = effectiveBaseChunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clipToBounds()
            .systemGestureExclusion()
            .onSizeChanged { newSize ->
                canvasWidthPx = newSize.width
                canvasHeightPx = newSize.height
            }
            .semantics {
                contentDescription = contentDescriptionText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = 0f,
                    range = 0f..durationMs.toFloat(),
                )
                customActions = listOf(
                    CustomAccessibilityAction(prevChunkActionLabel) {
                        onPreviousChunk()
                        true
                    },
                    CustomAccessibilityAction(nextChunkActionLabel) {
                        onNextChunk()
                        true
                    },
                    CustomAccessibilityAction(seekBackActionLabel) {
                        onSeekBack()
                        true
                    },
                )
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((fraction * durationMs).roundToLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { startOffset ->
                        if (size.width > 0) {
                            dragFraction = (startOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onScrubChange((dragFraction * durationMs).roundToLong())
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            dragFraction = (dragFraction + dragAmount / size.width.toFloat()).coerceIn(0f, 1f)
                            onScrubChange((dragFraction * durationMs).roundToLong())
                        }
                    },
                    onDragEnd = {
                        onSeekTo((dragFraction * durationMs).roundToLong())
                    },
                    onDragCancel = {
                        onSeekTo((dragFraction * durationMs).roundToLong())
                    },
                )
            }
            .drawWithCache {
                val overviewStrip = cachedOverviewStrip ?: if (isInspectionMode) {
                    val widthPx = size.width.roundToInt().coerceAtLeast(1)
                    val heightPx = size.height.roundToInt().coerceAtLeast(1)
                    renderWaveformBitmapStrip(
                        waveform = waveform,
                        downsampler = downsampler,
                        startMs = 0L,
                        endMs = durationMs,
                        totalWidthPx = widthPx,
                        heightPx = heightPx,
                        chunks = chunks,
                        languagePair = languagePair,
                        unknownWavePeakColor = unknownWavePeakColor,
                        unknownWaveRmsColor = unknownWaveRmsColor,
                        targetWavePeakColor = targetWavePeakColor,
                        targetWaveRmsColor = targetWaveRmsColor,
                        baseWavePeakColor = baseWavePeakColor,
                        baseWaveRmsColor = baseWaveRmsColor,
                        dummyWaveColor = dummyWaveColor,
                        desaturateUntranscribedChunks = true,
                    )
                } else {
                    null
                }
                val baseOverviewStrip = cachedBaseOverviewStrip

                onDrawBehind {
                    // フレーム更新の購読（描画フェーズのみ）
                    frameTick()
                    val currentPosMs = (scrubPositionMs ?: positionProvider.currentPositionMs())
                        .coerceIn(0L, durationMs)
                    val activeRevealFraction = if (isInspectionMode || isRenderComplete) {
                        1f
                    } else {
                        revealFraction.coerceIn(0f, 1f)
                    }

                    // ループ範囲ハイライト
                    drawLoopRangeOnOverview(
                        loopMode = loopMode,
                        chunks = chunks,
                        durationMs = durationMs,
                        abLoopHighlightColor = abLoopHighlightColor,
                    )

                    // チャンク境界の目盛り（チャンク分割時のみ表示し、言語判別完了後は非表示）
                    if (showBoundaryTicks) {
                        val tickHeight = size.height * 0.28f
                        for (chunk in chunks) {
                            val x = (chunk.startMs.toFloat() / durationMs.toFloat()) * size.width
                            drawLine(
                                color = boundaryTickColor,
                                start = Offset(x, size.height - tickHeight),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }

                    // アルファチャンネル画像としてキャッシュ済みの全体波形を描画（未完成時は既存の波形の上で左から右へ漸次更新）
                    if (activeRevealFraction < 1f && baseOverviewStrip != null && overviewStrip != null) {
                        val cutoffMs = computeProgressiveRevealCutoffMs(
                            startMs = 0L,
                            endMs = durationMs,
                            revealFraction = activeRevealFraction,
                        )
                        if (cutoffMs > 0L) {
                            drawWaveformBitmapStrip(
                                strip = overviewStrip,
                                windowStartMs = 0L,
                                windowEndMs = durationMs,
                                clipStartMs = 0L,
                                clipEndMs = cutoffMs,
                            )
                        }
                        if (cutoffMs < durationMs) {
                            drawWaveformBitmapStrip(
                                strip = baseOverviewStrip,
                                windowStartMs = 0L,
                                windowEndMs = durationMs,
                                clipStartMs = cutoffMs,
                                clipEndMs = durationMs,
                            )
                        }
                    } else {
                        overviewStrip?.let { strip ->
                            drawWaveformBitmapStrip(
                                strip = strip,
                                windowStartMs = 0L,
                                windowEndMs = durationMs,
                            )
                        }
                    }

                    // 拡大波形の表示範囲枠（ビューポート矩形）
                    val halfWindow = zoomWindowMs / 2L
                    val viewStartMs = (currentPosMs - halfWindow).coerceIn(0L, durationMs)
                    val viewEndMs = (currentPosMs + halfWindow).coerceIn(0L, durationMs)
                    val viewLeft = (viewStartMs.toFloat() / durationMs.toFloat()) * size.width
                    val viewRight = max(viewLeft + 4f, (viewEndMs.toFloat() / durationMs.toFloat()) * size.width)
                    drawRect(
                        color = viewportFillColor,
                        topLeft = Offset(viewLeft, 0f),
                        size = Size(viewRight - viewLeft, size.height),
                    )
                    drawRect(
                        color = viewportStrokeColor,
                        topLeft = Offset(viewLeft, 0f),
                        size = Size(viewRight - viewLeft, size.height),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )

                    // 現在位置を示すバー（太めのハンドル形状）
                    val playheadX = ((currentPosMs.toFloat() / durationMs.toFloat()) * size.width)
                        .coerceIn(0f, size.width)
                    val barWidthPx = MINIMAP_PLAYHEAD_BAR_WIDTH.toPx()
                    val barRadiusPx = barWidthPx * 0.5f
                    drawRoundRect(
                        color = playheadColor,
                        topLeft = Offset(
                            x = (playheadX - barWidthPx * 0.5f).coerceIn(0f, (size.width - barWidthPx).coerceAtLeast(0f)),
                            y = 0f,
                        ),
                        size = Size(barWidthPx, size.height),
                        cornerRadius = CornerRadius(barRadiusPx, barRadiusPx),
                    )
                    val knobWidthPx = MINIMAP_PLAYHEAD_KNOB_WIDTH.toPx()
                    val knobHeightPx = MINIMAP_PLAYHEAD_KNOB_HEIGHT.toPx()
                    val knobRadiusPx = knobHeightPx * 0.5f
                    val knobLeft = (playheadX - knobWidthPx * 0.5f)
                        .coerceIn(0f, (size.width - knobWidthPx).coerceAtLeast(0f))
                    drawRoundRect(
                        color = playheadColor,
                        topLeft = Offset(knobLeft, 0f),
                        size = Size(knobWidthPx, knobHeightPx),
                        cornerRadius = CornerRadius(knobRadiusPx, knobRadiusPx),
                    )
                    drawRoundRect(
                        color = playheadColor,
                        topLeft = Offset(knobLeft, (size.height - knobHeightPx).coerceAtLeast(0f)),
                        size = Size(knobWidthPx, knobHeightPx),
                        cornerRadius = CornerRadius(knobRadiusPx, knobRadiusPx),
                    )
                }
            },
    )
}

/**
 * 全体波形の左上に現在の再生箇所（「分:秒」）を表示する子 Composable。
 *
 * [PositionProvider] のサンプリングをこの Composable の内部に閉じ込めることで、
 * 再生位置の更新によって [WaveformSection] 全体が再コンポーズされるのを防ぐ（§6.2）。
 */
@Composable
private fun WaveformOverviewPositionText(
    durationMs: Long,
    isPlaying: Boolean,
    positionProvider: PositionProvider,
    scrubPositionMs: Long?,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    var sampledPositionMs by remember(positionProvider) {
        mutableLongStateOf(positionProvider.currentPositionMs())
    }
    if (!isInspectionMode) {
        LaunchedEffect(positionProvider, isPlaying) {
            sampledPositionMs = positionProvider.currentPositionMs()
            while (isActive) {
                sampledPositionMs = positionProvider.currentPositionMs()
                delay(if (isPlaying) 100L else 250L)
            }
        }
    }
    val displayPosMs = (scrubPositionMs ?: sampledPositionMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
    Text(
        text = formatDurationMs(displayPosMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ZoomedWaveformCanvas(
    waveform: Waveform?,
    durationMs: Long,
    zoomWindowMs: Long,
    cachedAnchorCenterMs: Long,
    onUpdateAnchorCenter: (Long) -> Unit,
    chunks: List<ChunkUi>,
    baseChunks: List<ChunkUi>,
    isRenderComplete: Boolean,
    renderGeneration: Long,
    onRenderComplete: () -> Unit,
    activeChunkIndex: Int?,
    languagePair: LanguagePair?,
    loopMode: LoopMode,
    downsampler: WaveformDownsampler,
    positionProvider: PositionProvider,
    scrubPositionMs: Long?,
    abDragStartMs: Long?,
    abDragCurrentMs: Long?,
    frameTick: () -> Long,
    unknownWavePeakColor: Color,
    unknownWaveRmsColor: Color,
    targetWavePeakColor: Color,
    targetWaveRmsColor: Color,
    baseWavePeakColor: Color,
    baseWaveRmsColor: Color,
    playheadColor: Color,
    targetActiveBorderColor: Color,
    baseActiveBorderColor: Color,
    unknownActiveBorderColor: Color,
    abLoopHighlightColor: Color,
    abLoopBorderColor: Color,
    targetChunkBackgroundColor: Color,
    baseChunkBackgroundColor: Color,
    unknownChunkBackgroundColor: Color,
    selectedChunkBackgroundColor: Color,
    selectedChunkBorderColor: Color,
    selectedChunkCheckColor: Color,
    dummyWaveColor: Color,
    onSetChunkSelected: (ChunkId, Boolean) -> Unit,
    contentDescriptionText: String,
    prevChunkActionLabel: String,
    nextChunkActionLabel: String,
    seekBackActionLabel: String,
    onSeekTo: (Long) -> Unit,
    onDismissAbRangeAndSeek: (Long) -> Unit,
    onSeekToChunk: (ChunkId) -> Unit,
    onScrubChange: (Long) -> Unit,
    onZoomWindowChange: (Long) -> Unit,
    onAbDragUpdate: (Long, Long) -> Unit,
    onAbDragFinish: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
) {
    val isInspectionMode = LocalInspectionMode.current
    val hapticFeedback = LocalHapticFeedback.current

    // pointerInput や LaunchedEffect の中では、起動したときにキャプチャした古い値ではなく最新の値を読む
    val currentZoomWindowMs by rememberUpdatedState(zoomWindowMs)
    val currentChunks by rememberUpdatedState(chunks)
    val currentLoopMode by rememberUpdatedState(loopMode)
    val currentScrubPositionMs by rememberUpdatedState(scrubPositionMs)
    val currentAnchorCenterMs by rememberUpdatedState(cachedAnchorCenterMs)
    val currentOnUpdateAnchorCenter by rememberUpdatedState(onUpdateAnchorCenter)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentOnDismissAbRangeAndSeek by rememberUpdatedState(onDismissAbRangeAndSeek)
    val currentOnSeekToChunk by rememberUpdatedState(onSeekToChunk)
    val currentOnScrubChange by rememberUpdatedState(onScrubChange)
    val currentOnZoomWindowChange by rememberUpdatedState(onZoomWindowChange)
    val currentOnAbDragUpdate by rememberUpdatedState(onAbDragUpdate)
    val currentOnAbDragFinish by rememberUpdatedState(onAbDragFinish)
    val currentOnSetChunkSelected by rememberUpdatedState(onSetChunkSelected)
    val currentOnRenderComplete by rememberUpdatedState(onRenderComplete)

    // ピンチ操作中は既存の画像を水平方向に拡大縮小して即座に追従させ、操作が止まってから 0.2 秒（200ms）後に、
    // 表示幅に応じた解像度（15 秒 / 7.5 秒）の近傍画像を作り直す
    var settledZoomWindowMs by remember(durationMs) { mutableLongStateOf(zoomWindowMs) }
    LaunchedEffect(zoomWindowMs) {
        if (zoomWindowMs != settledZoomWindowMs) {
            delay(ZOOM_SETTLE_DELAY_MS)
            val pos = (currentScrubPositionMs ?: positionProvider.currentPositionMs()).coerceIn(0L, durationMs)
            currentOnUpdateAnchorCenter(pos)
            settledZoomWindowMs = zoomWindowMs
        }
    }

    // Canvas のサイズ（レイアウトのあとで確定する）
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    var canvasHeightPx by remember { mutableIntStateOf(0) }

    // 30 秒の解像度で全体を描いた画像の本来の幅。長い音声では上限で縮むため、30 秒の表示でも近傍画像を使う
    val defaultWindowMs = DEFAULT_ZOOM_WINDOW_MS.coerceAtMost(durationMs).coerceAtLeast(1L)
    val idealFullStripWidthPx = (durationMs.toDouble() / defaultWindowMs.toDouble()) * canvasWidthPx.toDouble()
    val renderLevelMs = localStripLevelMs(
        settledWindowMs = settledZoomWindowMs,
        isFullStripDownscaled = idealFullStripWidthPx > MAX_TOTAL_STRIP_WIDTH_PX,
    )

    // 再生位置やプログレスバーの移動で近傍画像の範囲から離れそうになったら、中心時刻を更新する
    if (!isInspectionMode && renderLevelMs != null) {
        LaunchedEffect(renderLevelMs, durationMs) {
            val thresholdMs = renderLevelMs * 3L / 4L
            while (isActive) {
                val pos = (currentScrubPositionMs ?: positionProvider.currentPositionMs()).coerceIn(0L, durationMs)
                if (abs(pos - currentAnchorCenterMs) > thresholdMs) {
                    currentOnUpdateAnchorCenter(pos)
                }
                delay(ANCHOR_POLL_INTERVAL_MS)
            }
        }
    }

    // 波形画像の色分けに使うのはチャンクの時刻・言語・文字起こし完了有無・スキップ状態だけなので、選択状態や本文の内容が変わっても作り直さない
    val chunkColorKeys = remember(chunks) {
        chunks.map {
            ChunkColorKey(
                startMs = it.startMs,
                endMs = it.endMs,
                language = it.language,
                isTranscribed = !it.text.isNullOrBlank(),
                isSkipped = it.isSkipped,
            )
        }
    }
    val effectiveBaseChunks = remember(baseChunks, chunks) {
        baseChunks.ifEmpty { chunks.map { it.copy(language = null, text = null) } }
    }
    val baseChunkColorKeys = remember(effectiveBaseChunks) {
        effectiveBaseChunks.map {
            ChunkColorKey(
                startMs = it.startMs,
                endMs = it.endMs,
                language = it.language,
                isTranscribed = !it.text.isNullOrBlank(),
                isSkipped = it.isSkipped,
            )
        }
    }

    var revealFraction by remember(renderGeneration) {
        mutableFloatStateOf(if (isInspectionMode || isRenderComplete) 1f else 0f)
    }
    if (!isInspectionMode) {
        LaunchedEffect(renderGeneration, chunkColorKeys, waveform, isRenderComplete) {
            if (waveform == null || chunks.isEmpty()) {
                revealFraction = 0f
                return@LaunchedEffect
            }
            if (isRenderComplete) {
                revealFraction = 1f
                return@LaunchedEffect
            }
            val shouldAnimate = baseChunkColorKeys != chunkColorKeys
            if (shouldAnimate) {
                revealFraction = 0f
                val startNanos = withFrameNanos { it }
                val durationNanos = PROGRESSIVE_WAVEFORM_REVEAL_DURATION_MS * 1_000_000L
                while (isActive) {
                    val nowNanos = withFrameNanos { it }
                    val progress = ((nowNanos - startNanos).toDouble() / durationNanos.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
                    revealFraction = progress
                    if (progress >= 1f) break
                }
            } else {
                revealFraction = 1f
            }
            currentOnRenderComplete()
        }
    }

    // 近傍画像の解像度と範囲（中心時刻の前後に解像度の 4 倍の幅）。範囲が変わらなければ作り直さない
    val localStripSpec: LocalStripSpec? = renderLevelMs?.let { levelMs ->
        LocalStripSpec(
            levelMs = levelMs,
            range = localStripRangeFor(anchorMs = cachedAnchorCenterMs, levelMs = levelMs, durationMs = durationMs),
        )
    }

    val fullWaveformStrip = remember(
        renderGeneration,
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        chunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = 0L,
                endMs = durationMs,
                totalWidthPx = idealFullStripWidthPx.roundToInt().coerceIn(canvasWidthPx, MAX_TOTAL_STRIP_WIDTH_PX),
                heightPx = canvasHeightPx,
                chunks = chunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    val baseFullWaveformStrip = remember(
        renderGeneration,
        isRenderComplete,
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        baseChunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        if (isInspectionMode || isRenderComplete || canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = 0L,
                endMs = durationMs,
                totalWidthPx = idealFullStripWidthPx.roundToInt().coerceIn(canvasWidthPx, MAX_TOTAL_STRIP_WIDTH_PX),
                heightPx = canvasHeightPx,
                chunks = effectiveBaseChunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    // 近傍画像の範囲内にあるチャンクと言語、および範囲内の波形データが変わったときだけ近傍画像を作り直す
    // （ファイル後半のデコード進行や言語判定更新によって現在表示中の拡大波形が再生成・フラッシュするのを防ぐ）
    val localChunkColorKeys = remember(chunkColorKeys, localStripSpec) {
        val spec = localStripSpec ?: return@remember emptyList()
        chunkColorKeys.filter { it.endMs > spec.range.first && it.startMs < spec.range.last }
    }
    val localBaseChunkColorKeys = remember(baseChunkColorKeys, localStripSpec) {
        val spec = localStripSpec ?: return@remember emptyList()
        baseChunkColorKeys.filter { it.endMs > spec.range.first && it.startMs < spec.range.last }
    }
    val localWaveformSliceHash = remember(waveform, localStripSpec) {
        val spec = localStripSpec ?: return@remember 0
        waveformSliceContentHash(waveform, spec.range)
    }

    val localStrip = remember(
        renderGeneration,
        localStripSpec,
        localWaveformSliceHash,
        canvasWidthPx,
        canvasHeightPx,
        localChunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        val spec = localStripSpec
        if (spec == null || canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            val spanMs = (spec.range.last - spec.range.first).coerceAtLeast(1L)
            val stripWidthPx = ((spanMs.toDouble() / spec.levelMs.toDouble()) * canvasWidthPx.toDouble())
                .roundToInt()
                .coerceIn(canvasWidthPx, MAX_LOCAL_STRIP_WIDTH_PX)
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = spec.range.first,
                endMs = spec.range.last,
                totalWidthPx = stripWidthPx,
                heightPx = canvasHeightPx,
                chunks = chunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    val baseLocalStrip = remember(
        renderGeneration,
        isRenderComplete,
        localStripSpec,
        localWaveformSliceHash,
        canvasWidthPx,
        canvasHeightPx,
        localBaseChunkColorKeys,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
        dummyWaveColor,
    ) {
        val spec = localStripSpec
        if (isInspectionMode || isRenderComplete || spec == null || canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            val spanMs = (spec.range.last - spec.range.first).coerceAtLeast(1L)
            val stripWidthPx = ((spanMs.toDouble() / spec.levelMs.toDouble()) * canvasWidthPx.toDouble())
                .roundToInt()
                .coerceIn(canvasWidthPx, MAX_LOCAL_STRIP_WIDTH_PX)
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = spec.range.first,
                endMs = spec.range.last,
                totalWidthPx = stripWidthPx,
                heightPx = canvasHeightPx,
                chunks = effectiveBaseChunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                dummyWaveColor = dummyWaveColor,
                desaturateUntranscribedChunks = true,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clipToBounds()
            .systemGestureExclusion()
            .onSizeChanged { newSize ->
                canvasWidthPx = newSize.width
                canvasHeightPx = newSize.height
            }
            .semantics {
                contentDescription = contentDescriptionText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = 0f,
                    range = 0f..durationMs.toFloat(),
                )
                customActions = listOf(
                    CustomAccessibilityAction(prevChunkActionLabel) {
                        onPreviousChunk()
                        true
                    },
                    CustomAccessibilityAction(nextChunkActionLabel) {
                        onNextChunk()
                        true
                    },
                    CustomAccessibilityAction(seekBackActionLabel) {
                        onSeekBack()
                        true
                    },
                )
            }
            // タップ: 範囲選択中なら範囲選択を解除してタッチ位置へ移動する。
            // 通常時はチャンクの上ならそのチャンクの冒頭から再生し、チャンクの外ならその位置へ移動する
            .pointerInput(durationMs) {
                detectTapGestures(
                    onTap = { offset ->
                        if (size.width > 0) {
                            val targetMs = zoomedTouchXToMs(
                                x = offset.x,
                                widthPx = size.width,
                                centerMs = currentScrubPositionMs ?: positionProvider.currentPositionMs(),
                                windowMs = currentZoomWindowMs,
                                durationMs = durationMs,
                            )
                            val activeAbRange = (currentLoopMode as? LoopMode.AbRange)?.takeIf { it.startMs < it.endMs }
                            if (activeAbRange != null) {
                                currentOnDismissAbRangeAndSeek(targetMs)
                            } else {
                                val targetChunk = findChunkAt(currentChunks, targetMs)
                                if (targetChunk != null) {
                                    currentOnSeekToChunk(targetChunk.id)
                                } else {
                                    currentOnSeekTo(targetMs)
                                }
                            }
                        }
                    },
                )
            }
            // ピンチで表示幅を変え、1 本指の左右ドラッグで再生位置を動かす
            .pointerInput(durationMs) {
                var activeScrubMs: Long? = null
                detectZoomAndPanGestures(
                    // パンの基準位置はジェスチャーごとに取り直す（前回のパンの終了位置に戻らないように）
                    onGestureStart = { activeScrubMs = null },
                    onGesture = { pan, zoom ->
                        if (size.width > 0) {
                            val liveWindow = currentZoomWindowMs
                            if (abs(zoom - 1f) > 0.004f) {
                                currentOnZoomWindowChange((liveWindow.toFloat() / zoom).roundToLong())
                            } else if (abs(pan.x) > 0.5f) {
                                val baseMs = activeScrubMs
                                    ?: currentScrubPositionMs
                                    ?: positionProvider.currentPositionMs()
                                val deltaMs = ((-pan.x / size.width.toFloat()) * liveWindow).roundToLong()
                                val nextMs = (baseMs + deltaMs).coerceIn(0L, durationMs)
                                activeScrubMs = nextMs
                                currentOnScrubChange(nextMs)
                                currentOnSeekTo(nextMs)
                            }
                        }
                    },
                )
            }
            // 長押しドラッグ: ドラッグ移動時は範囲選択（A-B 範囲）を行い、
            // ドラッグせずチャンク上で長押ししただけの場合はそのチャンクの選択状態を切り替える。
            // 長押しが成立したあとの指の動きをピンチ・パンより先に受け取るため、いちばん内側（最後）に置く
            .pointerInput(durationMs) {
                var initialTouchedChunk: ChunkUi? = null
                var abStartMs = 0L
                var abCurrentMs = 0L
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            val touchMs = zoomedTouchXToMs(
                                x = offset.x,
                                widthPx = size.width,
                                centerMs = currentScrubPositionMs ?: positionProvider.currentPositionMs(),
                                windowMs = currentZoomWindowMs,
                                durationMs = durationMs,
                            )
                            initialTouchedChunk = findChunkAt(currentChunks, touchMs)
                            abStartMs = touchMs
                            abCurrentMs = touchMs
                            currentOnAbDragUpdate(abStartMs, abCurrentMs)
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            val deltaMs = ((dragAmount.x / size.width.toFloat()) * currentZoomWindowMs).roundToLong()
                            abCurrentMs = (abCurrentMs + deltaMs).coerceIn(0L, durationMs)
                            currentOnAbDragUpdate(abStartMs, abCurrentMs)
                        }
                    },
                    onDragEnd = {
                        val touched = initialTouchedChunk
                        initialTouchedChunk = null
                        if (abs(abCurrentMs - abStartMs) < 100L && touched != null) {
                            currentOnSetChunkSelected(touched.id, !touched.isRepeatChecked)
                        }
                        currentOnAbDragFinish()
                    },
                    onDragCancel = {
                        initialTouchedChunk = null
                        currentOnAbDragFinish()
                    },
                )
            }
            .drawWithCache {
                // プレビュー（スクリーンショット）では再コンポーズを待たずに描けるよう、その場で全体画像を作る
                val fullStrip = fullWaveformStrip ?: if (isInspectionMode) {
                    val wPx = size.width.roundToInt().coerceAtLeast(1)
                    renderWaveformBitmapStrip(
                        waveform = waveform,
                        downsampler = downsampler,
                        startMs = 0L,
                        endMs = durationMs,
                        totalWidthPx = ((durationMs.toDouble() / defaultWindowMs.toDouble()) * wPx.toDouble())
                            .roundToInt()
                            .coerceIn(wPx, MAX_TOTAL_STRIP_WIDTH_PX),
                        heightPx = size.height.roundToInt().coerceAtLeast(1),
                        chunks = chunks,
                        languagePair = languagePair,
                        unknownWavePeakColor = unknownWavePeakColor,
                        unknownWaveRmsColor = unknownWaveRmsColor,
                        targetWavePeakColor = targetWavePeakColor,
                        targetWaveRmsColor = targetWaveRmsColor,
                        baseWavePeakColor = baseWavePeakColor,
                        baseWaveRmsColor = baseWaveRmsColor,
                        dummyWaveColor = dummyWaveColor,
                        desaturateUntranscribedChunks = true,
                    )
                } else {
                    null
                }
                val baseFullStrip = baseFullWaveformStrip
                val chunkGapPx = 1.dp.toPx()
                val chunkCornerPx = WAVEFORM_CHUNK_CORNER_RADIUS.toPx()
                val chunkCorner = CornerRadius(chunkCornerPx, chunkCornerPx)
                val selectedStrokePx = 2.dp.toPx()
                val activeStrokePx = 1.5.dp.toPx()
                val badgeRadiusPx = 7.dp.toPx()
                val badgeMarginPx = 3.dp.toPx()
                val activeChunk = activeChunkIndex?.let { chunks.getOrNull(it) }

                onDrawBehind {
                    frameTick()
                    val liveWindowMs = zoomWindowMs.coerceAtLeast(1L)
                    val currentPosMs = (scrubPositionMs ?: positionProvider.currentPositionMs())
                        .coerceIn(0L, durationMs)
                    val windowStartMs = currentPosMs - liveWindowMs / 2L
                    val windowEndMs = windowStartMs + liveWindowMs
                    val pxPerMs = size.width / liveWindowMs.toFloat()
                    val centerX = size.width * 0.5f
                    val activeRevealFraction = if (isInspectionMode || isRenderComplete) {
                        1f
                    } else {
                        revealFraction.coerceIn(0f, 1f)
                    }
                    val cutoffMs = computeProgressiveRevealCutoffMs(
                        startMs = windowStartMs,
                        endMs = windowEndMs,
                        revealFraction = activeRevealFraction,
                    )
                    val cutoffX = (size.width * activeRevealFraction).coerceIn(0f, size.width)

                    fun chunkRect(chunk: ChunkUi): Rect? = chunkRectOnCanvas(
                        leftX = (chunk.startMs - windowStartMs) * pxPerMs,
                        rightX = (chunk.endMs - windowStartMs) * pxPerMs,
                        heightPx = size.height,
                        gapPx = chunkGapPx,
                    )

                    fun drawChunkBackgrounds(targetChunks: List<ChunkUi>) {
                        targetChunks.forEachIndexed { index, chunk ->
                            if (chunk.endMs <= windowStartMs || chunk.startMs >= windowEndMs) return@forEachIndexed
                            val rect = chunkRect(chunk) ?: return@forEachIndexed
                            val roleColor = when {
                                matchesLanguageRole(chunk.language, languagePair?.target) -> targetChunkBackgroundColor
                                matchesLanguageRole(chunk.language, languagePair?.base) -> baseChunkBackgroundColor
                                else -> unknownChunkBackgroundColor
                            }
                            val emphasis = if (index % 2 == 0) 1f else ALTERNATE_CHUNK_BACKGROUND_EMPHASIS
                            drawRoundRect(
                                color = styleZoomedChunkBackgroundColor(
                                    roleColor = roleColor,
                                    emphasis = emphasis,
                                    isSkipped = chunk.isSkipped,
                                ),
                                topLeft = rect.topLeft,
                                size = rect.size,
                                cornerRadius = chunkCorner,
                            )
                            if (chunk.isRepeatChecked) {
                                drawRoundRect(
                                    color = selectedChunkBackgroundColor,
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                    cornerRadius = chunkCorner,
                                )
                            }
                        }
                    }

                    // 1. チャンクの背景（言語の役割で色を分け、隣り合うチャンクは濃さを交互に変えて区別しやすくする。
                    // 未完成時は既存のチャンク背景の上で左から右へ漸次更新する）
                    if (activeRevealFraction < 1f) {
                        if (cutoffX > 0f) {
                            clipRect(left = 0f, top = 0f, right = cutoffX, bottom = size.height) {
                                drawChunkBackgrounds(chunks)
                            }
                        }
                        if (cutoffX < size.width) {
                            clipRect(left = cutoffX, top = 0f, right = size.width, bottom = size.height) {
                                drawChunkBackgrounds(effectiveBaseChunks)
                            }
                        }
                    } else {
                        drawChunkBackgrounds(chunks)
                    }

                    // 2. 波形画像を水平方向に拡大縮小して描く。表示幅が近傍画像の解像度の 2 倍以内で、
                    // 表示範囲が近傍画像に収まっていれば近傍画像を、それ以外（ピンチ中・大きな移動の直後など）は全体画像を使う
                    val visibleStartMs = windowStartMs.coerceAtLeast(0L)
                    val visibleEndMs = windowEndMs.coerceAtMost(durationMs)
                    val nearStrip = localStrip
                    val nearLevelMs = localStripSpec?.levelMs
                    val useNearStrip = nearStrip != null &&
                        nearLevelMs != null &&
                        liveWindowMs <= nearLevelMs * 2L &&
                        visibleStartMs >= nearStrip.startMs &&
                        visibleEndMs <= nearStrip.endMs
                    val stripToDraw = if (useNearStrip) nearStrip else fullStrip
                    val baseStripToDraw = if (useNearStrip) baseLocalStrip ?: baseFullStrip else baseFullStrip

                    if (activeRevealFraction < 1f && baseStripToDraw != null && stripToDraw != null) {
                        if (cutoffMs > windowStartMs) {
                            drawWaveformBitmapStrip(
                                strip = stripToDraw,
                                windowStartMs = windowStartMs,
                                windowEndMs = windowEndMs,
                                clipStartMs = windowStartMs,
                                clipEndMs = cutoffMs,
                            )
                        }
                        if (cutoffMs < windowEndMs) {
                            drawWaveformBitmapStrip(
                                strip = baseStripToDraw,
                                windowStartMs = windowStartMs,
                                windowEndMs = windowEndMs,
                                clipStartMs = cutoffMs,
                                clipEndMs = windowEndMs,
                            )
                        }
                    } else {
                        stripToDraw?.let { strip ->
                            drawWaveformBitmapStrip(
                                strip = strip,
                                windowStartMs = windowStartMs,
                                windowEndMs = windowEndMs,
                            )
                        }
                    }

                    // 3. 選択中のチャンクの枠線とチェックの印（チャンクリストのチェックボックスと連動）
                    for (chunk in chunks) {
                        if (!chunk.isRepeatChecked) continue
                        if (chunk.endMs <= windowStartMs || chunk.startMs >= windowEndMs) continue
                        val rect = chunkRect(chunk) ?: continue
                        val half = selectedStrokePx / 2f
                        drawRoundRect(
                            color = selectedChunkBorderColor,
                            topLeft = Offset(rect.left + half, rect.top + half),
                            size = Size((rect.width - selectedStrokePx).coerceAtLeast(0f), rect.height - selectedStrokePx),
                            cornerRadius = chunkCorner,
                            style = Stroke(width = selectedStrokePx),
                        )
                        // チャンクの左端が画面の外にあっても、見えている部分の左上に印を出す
                        val visibleLeft = max(rect.left, 0f)
                        val visibleRight = min(rect.right, size.width)
                        if (visibleRight - visibleLeft >= (badgeRadiusPx + badgeMarginPx) * 2f) {
                            drawSelectionCheckBadge(
                                center = Offset(visibleLeft + badgeMarginPx + badgeRadiusPx, badgeMarginPx + badgeRadiusPx),
                                radius = badgeRadiusPx,
                                badgeColor = selectedChunkBorderColor,
                                checkColor = selectedChunkCheckColor,
                            )
                        }
                    }

                    // 4. 再生中のチャンクの枠線（選択中のチャンクでは、選択の枠線の内側に描く）
                    if (activeChunk != null && activeChunk.endMs > windowStartMs && activeChunk.startMs < windowEndMs) {
                        chunkRect(activeChunk)?.let { rect ->
                            val inset = if (activeChunk.isRepeatChecked) selectedStrokePx + activeStrokePx / 2f else activeStrokePx / 2f
                            val borderColor = when {
                                matchesLanguageRole(activeChunk.language, languagePair?.target) -> targetActiveBorderColor
                                matchesLanguageRole(activeChunk.language, languagePair?.base) -> baseActiveBorderColor
                                else -> unknownActiveBorderColor
                            }
                            val width = rect.width - inset * 2f
                            val height = rect.height - inset * 2f
                            if (width > 0f && height > 0f) {
                                drawRoundRect(
                                    color = borderColor,
                                    topLeft = Offset(rect.left + inset, rect.top + inset),
                                    size = Size(width, height),
                                    cornerRadius = chunkCorner,
                                    style = Stroke(width = activeStrokePx),
                                )
                            }
                        }
                    }

                    // 5. A-B リピートまたはドラッグ中の A-B 範囲を強調
                    val abRange: Pair<Long, Long>? = when {
                        abDragStartMs != null && abDragCurrentMs != null ->
                            min(abDragStartMs, abDragCurrentMs) to max(abDragStartMs, abDragCurrentMs)

                        loopMode is LoopMode.AbRange ->
                            loopMode.startMs to loopMode.endMs

                        else -> null
                    }
                    abRange?.let { (abStart, abEnd) ->
                        if (abEnd > windowStartMs && abStart < windowEndMs) {
                            val leftX = ((abStart - windowStartMs) * pxPerMs).coerceIn(0f, size.width)
                            val rightX = ((abEnd - windowStartMs) * pxPerMs).coerceIn(leftX, size.width)
                            drawRect(
                                color = abLoopHighlightColor,
                                topLeft = Offset(leftX, 0f),
                                size = Size(rightX - leftX, size.height),
                            )
                            drawRect(
                                color = abLoopBorderColor,
                                topLeft = Offset(leftX, 0f),
                                size = Size(rightX - leftX, size.height),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }

                    // 6. 中央の再生ヘッド
                    drawLine(
                        color = playheadColor,
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}

/** 波形画像の色分けに使うチャンクの情報（時刻・言語・文字起こし完了有無・スキップ状態）。これが変わったときだけ画像を作り直す。 */
private data class ChunkColorKey(
    val startMs: Long,
    val endMs: Long,
    val language: LanguageTag?,
    val isTranscribed: Boolean = false,
    val isSkipped: Boolean = false,
)

/** 未文字起こしチャンクの波形を描画するときに残す彩度の割合（0.0 = 完全なグレースケール、1.0 = 元の色）。 */
internal const val UNTRANSCRIBED_WAVEFORM_SATURATION: Float = 0.25f

/** スキップ対象チャンクの背景および波形を描画するときに残す彩度の割合。 */
internal const val SKIPPED_CHUNK_SATURATION: Float = 0.15f

/** スキップ対象チャンクの背景の不透明度に乗じる係数（背景とのコントラストを下げる）。 */
internal const val SKIPPED_CHUNK_BACKGROUND_ALPHA_FACTOR: Float = 0.38f

/** スキップ対象チャンクの波形バーの不透明度に乗じる係数（背景とのコントラストを下げる）。 */
internal const val SKIPPED_CHUNK_WAVEFORM_ALPHA_FACTOR: Float = 0.42f

/**
 * 波形の色 [color] の彩度を [saturationFactor]（`0f..1f`）に落とした色を返す。
 *
 * 知覚輝度（Rec.601: `0.299R + 0.587G + 0.114B`）に基づくグレースケール成分と元の RGB 成分を線形補間し、
 * アルファ値はそのまま維持する。拡大波形で文字起こしがまだ完了していないチャンクの波形（「折れ線」部分）の
 * 彩度を落として文字起こし済みチャンクと区別するために用いる。
 */
internal fun desaturateWaveformColor(
    color: Color,
    saturationFactor: Float = UNTRANSCRIBED_WAVEFORM_SATURATION,
): Color {
    val factor = saturationFactor.coerceIn(0f, 1f)
    val gray = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return Color(
        red = (gray + (color.red - gray) * factor).coerceIn(0f, 1f),
        green = (gray + (color.green - gray) * factor).coerceIn(0f, 1f),
        blue = (gray + (color.blue - gray) * factor).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

/**
 * 拡大波形のチャンク背景色 [roleColor] に、偶数/奇数チャンクの強調係数 [emphasis] とスキップ状態 [isSkipped] を反映した色を返す。
 *
 * スキップに設定されたチャンクは彩度を [SKIPPED_CHUNK_SATURATION] に落とし、不透明度にも [SKIPPED_CHUNK_BACKGROUND_ALPHA_FACTOR] を掛けて
 * 周囲の背景とのコントラストを下げる。
 */
internal fun styleZoomedChunkBackgroundColor(
    roleColor: Color,
    emphasis: Float = 1f,
    isSkipped: Boolean = false,
): Color {
    val base = if (isSkipped) {
        desaturateWaveformColor(roleColor, saturationFactor = SKIPPED_CHUNK_SATURATION)
    } else {
        roleColor
    }
    val alphaFactor = if (isSkipped) {
        emphasis * SKIPPED_CHUNK_BACKGROUND_ALPHA_FACTOR
    } else {
        emphasis
    }
    return base.copy(alpha = (base.alpha * alphaFactor).coerceIn(0f, 1f))
}

/**
 * 拡大波形でスキップに設定されたチャンクの波形バー色 [color] の彩度と不透明度を落とし、背景とのコントラストを下げた色を返す。
 */
internal fun styleSkippedWaveformBarColor(color: Color): Color {
    val desaturated = desaturateWaveformColor(color, saturationFactor = SKIPPED_CHUNK_SATURATION)
    return desaturated.copy(alpha = (desaturated.alpha * SKIPPED_CHUNK_WAVEFORM_ALPHA_FACTOR).coerceIn(0f, 1f))
}

/** 近傍画像の解像度 [levelMs]（その表示窓で画面幅いっぱいになる時間幅）と、描く時間範囲 [range]。 */
private data class LocalStripSpec(
    val levelMs: Long,
    val range: LongRange,
)

/**
 * [waveform] のうち指定された時間範囲 [range] に含まれるビンの内容ハッシュを計算する。
 *
 * ファイル後半のプログレッシブ波形更新で、表示中の近傍範囲 `[range.first, range.last]` の波形データが変わっていない場合に
 * `localStrip` の再レンダリングをスキップするために用いる。
 */
private fun waveformSliceContentHash(
    waveform: Waveform?,
    range: LongRange,
): Int {
    if (waveform == null || waveform.peak.isEmpty()) return 0
    val binCount = waveform.peak.size
    val binMs = waveform.binMs.coerceAtLeast(1)
    val startBin = (range.first / binMs).toInt().coerceIn(0, binCount)
    val endBin = ((range.last + binMs - 1) / binMs).toInt().coerceIn(startBin, binCount)
    var hash = waveform.binMs
    hash = 31 * hash + startBin
    hash = 31 * hash + endBin
    for (i in startBin until endBin) {
        hash = 31 * hash + waveform.peak[i].toInt()
        hash = 31 * hash + waveform.rms[i].toInt()
    }
    return hash
}

/**
 * 近傍画像の解像度（その表示窓で画面幅いっぱいになる時間幅）を、操作が落ち着いたあとの表示窓 [settledWindowMs] から決める。
 *
 * 表示窓が 15 秒未満なら [zoomRenderLevelWindowMs] に従う（15 秒 / 7.5 秒）。全体画像が幅の上限で縮んでいる（長い音声）
 * [isFullStripDownscaled] ときは、既定の 30 秒以下の表示でも 30 秒の解像度の近傍画像を使う。それ以外は `null`（全体画像を使う）。
 */
internal fun localStripLevelMs(
    settledWindowMs: Long,
    isFullStripDownscaled: Boolean,
): Long? = zoomRenderLevelWindowMs(settledWindowMs)
    ?: DEFAULT_ZOOM_WINDOW_MS.takeIf { isFullStripDownscaled && settledWindowMs <= DEFAULT_ZOOM_WINDOW_MS }

/**
 * 解像度 [levelMs] の近傍画像で描く時間範囲を返す。中心時刻 [anchorMs] の前後に、解像度の [LOCAL_STRIP_SPAN_FACTOR] 倍の幅を取り、
 * 音声の範囲 `[0, durationMs]` に収める。
 */
internal fun localStripRangeFor(
    anchorMs: Long,
    levelMs: Long,
    durationMs: Long,
): LongRange {
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    val spanMs = (levelMs * LOCAL_STRIP_SPAN_FACTOR).coerceIn(1L, safeDurationMs)
    val startMs = (anchorMs - spanMs / 2L).coerceIn(0L, safeDurationMs - spanMs)
    return startMs..(startMs + spanMs)
}

/**
 * 拡大波形の上の x 座標 [x] を、中心 [centerMs]・表示幅 [windowMs] の表示窓での時刻（ミリ秒）に変換する。
 */
internal fun zoomedTouchXToMs(
    x: Float,
    widthPx: Int,
    centerMs: Long,
    windowMs: Long,
    durationMs: Long,
): Long {
    if (widthPx <= 0) return centerMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val deltaFraction = (x / widthPx.toFloat()) - 0.5f
    return (centerMs + (deltaFraction * windowMs).roundToLong()).coerceIn(0L, durationMs.coerceAtLeast(0L))
}

/**
 * チャンクの左右の x 座標から、隣のチャンクとの間に [gapPx] のすき間を空けた描画用の矩形を返す。
 *
 * 幅が狭いチャンクはすき間を空けずに描き、最低でも 1px の幅を確保する。幅が 0 以下なら `null`。
 */
private fun chunkRectOnCanvas(
    leftX: Float,
    rightX: Float,
    heightPx: Float,
    gapPx: Float,
): Rect? {
    val width = rightX - leftX
    if (width <= 0f) return null
    val inset = if (width > gapPx * 4f) gapPx else 0f
    val left = leftX + inset
    val right = max(rightX - inset, left + 1f)
    return Rect(left, 0f, right, heightPx)
}

/** 選択中のチャンクの左上に出す、チェックの印（丸の中にチェック）を描く。 */
private fun DrawScope.drawSelectionCheckBadge(
    center: Offset,
    radius: Float,
    badgeColor: Color,
    checkColor: Color,
) {
    drawCircle(color = badgeColor, radius = radius, center = center)
    val checkPath = Path().apply {
        moveTo(center.x - radius * 0.45f, center.y + radius * 0.02f)
        lineTo(center.x - radius * 0.12f, center.y + radius * 0.36f)
        lineTo(center.x + radius * 0.48f, center.y - radius * 0.32f)
    }
    drawPath(
        path = checkPath,
        color = checkColor,
        style = Stroke(width = radius * 0.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * ピンチ（拡大縮小）と 1 本指のパンを検出する。
 *
 * 判定は `detectTransformGestures` と同じ（タッチスロップを超えてから反応し、反応したら位置の変化を消費する）。
 * それに加えて、ジェスチャーの開始時に [onGestureStart] を呼ぶ。
 */
private suspend fun PointerInputScope.detectZoomAndPanGestures(
    onGestureStart: () -> Unit,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        onGestureStart()
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1f - zoom) * centroidSize
                    if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(panChange, zoomChange)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

private class WaveformBitmapTile(
    val bitmap: ImageBitmap,
    val startPx: Int,
    val widthPx: Int,
)

private class WaveformBitmapStrip(
    val startMs: Long,
    val endMs: Long,
    val totalWidthPx: Int,
    val heightPx: Int,
    val tiles: List<WaveformBitmapTile>,
)

/**
 * 指定された時間範囲 `[startMs, endMs]` の波形を、言語役割別の色（ベース言語: 青、対象言語: 赤、不明領域: グレイ）で
 * アルファチャンネル付き [ImageBitmap] ストリップとしてレンダリングする。
 *
 * [desaturateUntranscribedChunks] が `true` のとき、文字起こしがまだ完了していないチャンク（`chunk.text.isNullOrBlank()`）の
 * 波形バーの彩度を [desaturateWaveformColor] で落として文字起こし済みチャンクと区別する。
 */
private fun renderWaveformBitmapStrip(
    waveform: Waveform?,
    downsampler: WaveformDownsampler,
    startMs: Long,
    endMs: Long,
    totalWidthPx: Int,
    heightPx: Int,
    chunks: List<ChunkUi>,
    languagePair: LanguagePair?,
    unknownWavePeakColor: Color,
    unknownWaveRmsColor: Color,
    targetWavePeakColor: Color,
    targetWaveRmsColor: Color,
    baseWavePeakColor: Color,
    baseWaveRmsColor: Color,
    dummyWaveColor: Color,
    desaturateUntranscribedChunks: Boolean = false,
): WaveformBitmapStrip {
    val safeWidthPx = totalWidthPx.coerceIn(1, MAX_TOTAL_STRIP_WIDTH_PX)
    val safeHeightPx = heightPx.coerceAtLeast(1)
    val spanMs = (endMs - startMs).coerceAtLeast(1L)
    val columnCount = max(1, (safeWidthPx / 3f).roundToInt())
    val downsampled = if (waveform != null) {
        downsampler.downsample(
            waveform = waveform,
            startMs = startMs,
            endMs = endMs,
            columnCount = columnCount,
        )
    } else {
        null
    }

    val waveformDurationMs = waveform?.durationMs ?: 0L

    val stepX = safeWidthPx.toFloat() / columnCount.toFloat()
    val barWidth = max(1f, stepX * 0.68f)
    val centerY = safeHeightPx * 0.5f
    val maxHalfHeight = safeHeightPx * 0.46f
    val minHalfHeight = 1.5f
    val cornerRadius = barWidth * 0.5f

    val tileCount = ceil(safeWidthPx.toDouble() / MAX_BITMAP_TILE_WIDTH_PX.toDouble()).toInt().coerceAtLeast(1)
    val tiles = ArrayList<WaveformBitmapTile>(tileCount)

    val unknownPeakPaint = Paint().apply {
        color = unknownWavePeakColor
        isAntiAlias = true
    }
    val unknownRmsPaint = Paint().apply {
        color = unknownWaveRmsColor
        isAntiAlias = true
    }
    val targetPeakPaint = Paint().apply {
        color = targetWavePeakColor
        isAntiAlias = true
    }
    val targetRmsPaint = Paint().apply {
        color = targetWaveRmsColor
        isAntiAlias = true
    }
    val basePeakPaint = Paint().apply {
        color = baseWavePeakColor
        isAntiAlias = true
    }
    val baseRmsPaint = Paint().apply {
        color = baseWaveRmsColor
        isAntiAlias = true
    }
    val untranscribedUnknownPeakPaint = Paint().apply {
        color = desaturateWaveformColor(unknownWavePeakColor)
        isAntiAlias = true
    }
    val untranscribedUnknownRmsPaint = Paint().apply {
        color = desaturateWaveformColor(unknownWaveRmsColor)
        isAntiAlias = true
    }
    val untranscribedTargetPeakPaint = Paint().apply {
        color = desaturateWaveformColor(targetWavePeakColor)
        isAntiAlias = true
    }
    val untranscribedTargetRmsPaint = Paint().apply {
        color = desaturateWaveformColor(targetWaveRmsColor)
        isAntiAlias = true
    }
    val untranscribedBasePeakPaint = Paint().apply {
        color = desaturateWaveformColor(baseWavePeakColor)
        isAntiAlias = true
    }
    val untranscribedBaseRmsPaint = Paint().apply {
        color = desaturateWaveformColor(baseWaveRmsColor)
        isAntiAlias = true
    }
    val skippedUnknownPeakPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(unknownWavePeakColor)
        isAntiAlias = true
    }
    val skippedUnknownRmsPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(unknownWaveRmsColor)
        isAntiAlias = true
    }
    val skippedTargetPeakPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(targetWavePeakColor)
        isAntiAlias = true
    }
    val skippedTargetRmsPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(targetWaveRmsColor)
        isAntiAlias = true
    }
    val skippedBasePeakPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(baseWavePeakColor)
        isAntiAlias = true
    }
    val skippedBaseRmsPaint = Paint().apply {
        color = styleSkippedWaveformBarColor(baseWaveRmsColor)
        isAntiAlias = true
    }
    val dummyPaint = Paint().apply {
        color = dummyWaveColor
        isAntiAlias = true
    }

    var chunkSearchIndex = 0
    for (tileIdx in 0 until tileCount) {
        val tileStartPx = tileIdx * MAX_BITMAP_TILE_WIDTH_PX
        val tileWidthPx = min(MAX_BITMAP_TILE_WIDTH_PX, safeWidthPx - tileStartPx)
        if (tileWidthPx <= 0) break
        val tileEndPx = tileStartPx + tileWidthPx

        val bitmap = ImageBitmap(tileWidthPx, safeHeightPx, ImageBitmapConfig.Argb8888)
        val canvas = ComposeCanvas(bitmap)

        val firstCol = max(0, ((tileStartPx - stepX) / stepX).toInt())
        val lastCol = min(columnCount - 1, ceil(tileEndPx.toDouble() / stepX.toDouble()).toInt())

        for (i in firstCol..lastCol) {
            val globalLeft = i * stepX + (stepX - barWidth) * 0.5f
            val localLeft = globalLeft - tileStartPx
            val localRight = localLeft + barWidth
            if (localRight <= 0f || localLeft >= tileWidthPx.toFloat()) continue

            val colCenterMs = startMs + (((i + 0.5) / columnCount.toDouble()) * spanMs.toDouble()).roundToLong()

            if (colCenterMs >= waveformDurationMs) {
                val isEven = (i % 2 == 0)
                val dummyHalfH = if (isEven) maxHalfHeight * 0.45f else maxHalfHeight * 0.25f
                canvas.drawRoundRect(
                    left = localLeft,
                    top = centerY - dummyHalfH,
                    right = localRight,
                    bottom = centerY + dummyHalfH,
                    radiusX = cornerRadius,
                    radiusY = cornerRadius,
                    paint = dummyPaint,
                )
                continue
            }

            while (chunkSearchIndex < chunks.size && chunks[chunkSearchIndex].endMs < colCenterMs) {
                chunkSearchIndex++
            }
            val matchedChunk = chunks.getOrNull(chunkSearchIndex)?.takeIf {
                colCenterMs in it.startMs..it.endMs
            } ?: chunks.firstOrNull { colCenterMs in it.startMs..it.endMs }

            val isTarget = matchedChunk != null && matchesLanguageRole(matchedChunk.language, languagePair?.target)
            val isBase = matchedChunk != null && matchesLanguageRole(matchedChunk.language, languagePair?.base)
            val isSkipped = desaturateUntranscribedChunks &&
                matchedChunk != null &&
                matchedChunk.isSkipped
            val isUntranscribed = desaturateUntranscribedChunks &&
                matchedChunk != null &&
                matchedChunk.text.isNullOrBlank()

            val peakPaint = when {
                isTarget && isSkipped -> skippedTargetPeakPaint
                isTarget && isUntranscribed -> untranscribedTargetPeakPaint
                isTarget -> targetPeakPaint
                isBase && isSkipped -> skippedBasePeakPaint
                isBase && isUntranscribed -> untranscribedBasePeakPaint
                isBase -> basePeakPaint
                isSkipped -> skippedUnknownPeakPaint
                isUntranscribed -> untranscribedUnknownPeakPaint
                else -> unknownPeakPaint
            }
            val rmsPaint = when {
                isTarget && isSkipped -> skippedTargetRmsPaint
                isTarget && isUntranscribed -> untranscribedTargetRmsPaint
                isTarget -> targetRmsPaint
                isBase && isSkipped -> skippedBaseRmsPaint
                isBase && isUntranscribed -> untranscribedBaseRmsPaint
                isBase -> baseRmsPaint
                isSkipped -> skippedUnknownRmsPaint
                isUntranscribed -> untranscribedUnknownRmsPaint
                else -> unknownRmsPaint
            }

            val peakAmp = downsampled?.peak?.get(i)?.coerceIn(0f, 1f) ?: 0f
            val peakHalfH = max(minHalfHeight, peakAmp * maxHalfHeight)
            canvas.drawRoundRect(
                left = localLeft,
                top = centerY - peakHalfH,
                right = localRight,
                bottom = centerY + peakHalfH,
                radiusX = cornerRadius,
                radiusY = cornerRadius,
                paint = peakPaint,
            )

            val rmsAmp = downsampled?.rms?.get(i)?.coerceIn(0f, 1f) ?: 0f
            val rmsHalfH = max(minHalfHeight, rmsAmp * maxHalfHeight)
            canvas.drawRoundRect(
                left = localLeft,
                top = centerY - rmsHalfH,
                right = localRight,
                bottom = centerY + rmsHalfH,
                radiusX = cornerRadius,
                radiusY = cornerRadius,
                paint = rmsPaint,
            )
        }

        tiles.add(
            WaveformBitmapTile(
                bitmap = bitmap,
                startPx = tileStartPx,
                widthPx = tileWidthPx,
            ),
        )
    }

    return WaveformBitmapStrip(
        startMs = startMs,
        endMs = endMs,
        totalWidthPx = safeWidthPx,
        heightPx = safeHeightPx,
        tiles = tiles,
    )
}

/**
 * キャッシュ済みの [WaveformBitmapStrip] から `[windowStartMs, windowEndMs]` の範囲（さらに `[clipStartMs, clipEndMs]` でクリップ）を切り出し、
 * 水平方向に拡大縮小して現在の [DrawScope] に描画する。
 */
private fun DrawScope.drawWaveformBitmapStrip(
    strip: WaveformBitmapStrip,
    windowStartMs: Long,
    windowEndMs: Long,
    clipStartMs: Long = windowStartMs,
    clipEndMs: Long = windowEndMs,
) {
    val stripSpanMs = (strip.endMs - strip.startMs).coerceAtLeast(1L)
    val windowSpanMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)
    val clampedStartMs = max(windowStartMs, clipStartMs)
    val clampedEndMs = min(windowEndMs, clipEndMs)
    val visibleStartMs = max(clampedStartMs, strip.startMs)
    val visibleEndMs = min(clampedEndMs, strip.endMs)
    if (visibleEndMs <= visibleStartMs) return

    val srcStartPx = (((visibleStartMs - strip.startMs).toDouble() / stripSpanMs.toDouble()) * strip.totalWidthPx.toDouble())
        .roundToInt()
        .coerceIn(0, strip.totalWidthPx - 1)
    val srcEndPx = (((visibleEndMs - strip.startMs).toDouble() / stripSpanMs.toDouble()) * strip.totalWidthPx.toDouble())
        .roundToInt()
        .coerceIn(srcStartPx + 1, strip.totalWidthPx)
    val totalSrcSpanPx = (srcEndPx - srcStartPx).coerceAtLeast(1)

    val dstStartX = (((visibleStartMs - windowStartMs).toDouble() / windowSpanMs.toDouble()) * size.width.toDouble())
        .roundToInt()
        .coerceIn(0, size.width.roundToInt())
    val dstEndX = (((visibleEndMs - windowStartMs).toDouble() / windowSpanMs.toDouble()) * size.width.toDouble())
        .roundToInt()
        .coerceIn(dstStartX + 1, size.width.roundToInt().coerceAtLeast(1))
    val totalDstSpanPx = (dstEndX - dstStartX).coerceAtLeast(1)
    val dstHeightPx = size.height.roundToInt().coerceAtLeast(1)

    for (tile in strip.tiles) {
        val tileStartPx = tile.startPx
        val tileEndPx = tile.startPx + tile.widthPx
        val overlapStartPx = max(srcStartPx, tileStartPx)
        val overlapEndPx = min(srcEndPx, tileEndPx)
        if (overlapEndPx <= overlapStartPx) continue

        val localSrcX = overlapStartPx - tileStartPx
        val localSrcW = overlapEndPx - overlapStartPx

        val tileDstLeft = dstStartX + (((overlapStartPx - srcStartPx).toDouble() / totalSrcSpanPx.toDouble()) * totalDstSpanPx.toDouble()).roundToInt()
        val tileDstRight = dstStartX + (((overlapEndPx - srcStartPx).toDouble() / totalSrcSpanPx.toDouble()) * totalDstSpanPx.toDouble()).roundToInt()
        val tileDstW = (tileDstRight - tileDstLeft).coerceAtLeast(1)

        drawImage(
            image = tile.bitmap,
            srcOffset = IntOffset(localSrcX, 0),
            srcSize = IntSize(localSrcW, strip.heightPx),
            dstOffset = IntOffset(tileDstLeft, 0),
            dstSize = IntSize(tileDstW, dstHeightPx),
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun DrawScope.drawLoopRangeOnOverview(
    loopMode: LoopMode,
    chunks: List<ChunkUi>,
    durationMs: Long,
    abLoopHighlightColor: Color,
) {
    if (durationMs <= 0L) return
    val rangeMs: Pair<Long, Long>? = when (loopMode) {
        LoopMode.Off -> null

        is LoopMode.AbRange -> loopMode.startMs to loopMode.endMs

        is LoopMode.SingleChunk -> chunks.firstOrNull { it.id == loopMode.chunkId }?.let { it.startMs to it.endMs }

        is LoopMode.ChunkRange -> {
            val first = chunks.firstOrNull { it.id == loopMode.firstChunkId }
            val last = chunks.firstOrNull { it.id == loopMode.lastChunkId }
            if (first != null && last != null) {
                min(first.startMs, last.startMs) to max(first.endMs, last.endMs)
            } else {
                null
            }
        }
    }
    rangeMs?.let { (startMs, endMs) ->
        val left = (startMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
        val right = (endMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
        if (right > left) {
            drawRect(
                color = abLoopHighlightColor,
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height),
            )
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun WaveformSectionPreview() {
    val material = SampleMaterials.jaKo
    val chunks = material.chunks.mapIndexed { index, chunk ->
        val transcript = material.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = if (index < 2) transcript?.text else null,
            language = transcript?.language,
        )
    }
    ChunkFlowTheme {
        Surface {
            WaveformSection(
                waveform = material.waveform,
                durationMs = material.item.durationMs,
                isPlaying = false,
                chunks = chunks,
                activeChunkIndex = 1,
                languagePair = material.item.languagePair,
                loopMode = LoopMode.AbRange(4_100L, 9_600L),
                analysis = material.analysis,
                positionProvider = FakePositionProvider(5_000L),
                onSeekTo = {},
                onSetAbRange = { _, _ -> },
                onPreviousChunk = {},
                onNextChunk = {},
                onSeekBack = {},
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
