package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.isActive

/** 拡大波形の既定の表示窓（2 分 = 120,000ms、§7.5.4）。 */
internal const val DEFAULT_ZOOM_WINDOW_MS: Long = 120_000L

/** 拡大波形の最小表示窓（2 秒 = 2,000ms、§7.5.4）。 */
internal const val MIN_ZOOM_WINDOW_MS: Long = 2_000L

/**
 * プレイヤー画面の波形セクション（全体波形 + 拡大波形。F4.1、F4.2、F6.2、§7.5.4）。
 *
 * - [positionProvider] は描画フェーズ（`onDrawBehind`）でのみ読み取り、再生位置の変化で再コンポーズしない（§6.2）。
 * - 再生中のみ `withFrameNanos` で描画レイヤーの無効化を行う。
 * - 波形のバーの [Path] は `Modifier.drawWithCache` と [WaveformDownsampler] でキャッシュする。
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
) {
    if (waveform == null || waveform.peak.isEmpty()) {
        WaveformPlaceholderCard(
            featureStatus = analysis[AnalysisStage.FEATURES],
            modifier = modifier,
        )
        return
    }

    val effectiveDurationMs = max(durationMs, waveform.durationMs).coerceAtLeast(1L)
    val downsampler = remember { WaveformDownsampler() }
    val isInspectionMode = LocalInspectionMode.current

    // 描画フェーズでのみ読むフレームカウンタ（コンポーズ中には読まない）
    val frameTick = remember { mutableLongStateOf(0L) }
    if (!isInspectionMode && isPlaying) {
        LaunchedEffect(isPlaying) {
            while (isActive) {
                withFrameNanos { frameNanos ->
                    frameTick.longValue = frameNanos
                }
            }
        }
    }

    val maxWindowMs = max(MIN_ZOOM_WINDOW_MS, effectiveDurationMs)
    var zoomWindowMs by remember(effectiveDurationMs) {
        mutableLongStateOf(DEFAULT_ZOOM_WINDOW_MS.coerceIn(MIN_ZOOM_WINDOW_MS, maxWindowMs))
    }

    // 拡大波形の 3 倍幅キャッシュの中心時刻（はみ出したときだけ更新して Path を作り直す）
    var cachedAnchorCenterMs by remember(waveform) {
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
    val seekBackActionLabel = stringResource(R.string.player_seek_back_cd, 3)

    val colorScheme = MaterialTheme.colorScheme
    val wavePeakColor = colorScheme.primary.copy(alpha = 0.55f)
    val waveRmsColor = colorScheme.primary
    val playheadColor = colorScheme.error
    val boundaryTickColor = colorScheme.outline
    val viewportStrokeColor = colorScheme.secondary
    val viewportFillColor = colorScheme.secondary.copy(alpha = 0.14f)
    val targetChunkColor = colorScheme.primaryContainer.copy(alpha = 0.45f)
    val baseChunkColor = colorScheme.tertiaryContainer.copy(alpha = 0.45f)
    val unknownChunkColor = colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val activeChunkHighlightColor = colorScheme.primary.copy(alpha = 0.24f)
    val abLoopHighlightColor = colorScheme.tertiary.copy(alpha = 0.30f)
    val abLoopBorderColor = colorScheme.tertiary

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
            // 1. 全体波形ヘッダ + キャンバス
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_waveform_overview_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDurationMs(effectiveDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            OverviewWaveformCanvas(
                waveform = waveform,
                durationMs = effectiveDurationMs,
                zoomWindowMs = zoomWindowMs,
                chunks = chunks,
                loopMode = loopMode,
                downsampler = downsampler,
                positionProvider = positionProvider,
                scrubPositionMs = scrubPositionMs,
                frameTick = { frameTick.longValue },
                wavePeakColor = wavePeakColor,
                waveRmsColor = waveRmsColor,
                playheadColor = playheadColor,
                boundaryTickColor = boundaryTickColor,
                viewportStrokeColor = viewportStrokeColor,
                viewportFillColor = viewportFillColor,
                abLoopHighlightColor = abLoopHighlightColor,
                contentDescriptionText = overviewCd,
                prevChunkActionLabel = prevChunkActionLabel,
                nextChunkActionLabel = nextChunkActionLabel,
                seekBackActionLabel = seekBackActionLabel,
                onSeekTo = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = null
                    cachedAnchorCenterMs = clamped
                    onSeekTo(clamped)
                },
                onScrubChange = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = clamped
                },
                onPreviousChunk = onPreviousChunk,
                onNextChunk = onNextChunk,
                onSeekBack = onSeekBack,
            )

            // 2. 拡大波形ヘッダ + キャンバス
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            ZoomedWaveformCanvas(
                waveform = waveform,
                durationMs = effectiveDurationMs,
                zoomWindowMs = zoomWindowMs,
                cachedAnchorCenterMs = cachedAnchorCenterMs,
                onUpdateAnchorCenter = { newAnchor -> cachedAnchorCenterMs = newAnchor },
                chunks = chunks,
                activeChunkIndex = activeChunkIndex,
                languagePair = languagePair,
                loopMode = loopMode,
                downsampler = downsampler,
                positionProvider = positionProvider,
                scrubPositionMs = scrubPositionMs,
                abDragStartMs = abDragStartMs,
                abDragCurrentMs = abDragCurrentMs,
                frameTick = { frameTick.longValue },
                wavePeakColor = wavePeakColor,
                waveRmsColor = waveRmsColor,
                playheadColor = playheadColor,
                targetChunkColor = targetChunkColor,
                baseChunkColor = baseChunkColor,
                unknownChunkColor = unknownChunkColor,
                activeChunkHighlightColor = activeChunkHighlightColor,
                abLoopHighlightColor = abLoopHighlightColor,
                abLoopBorderColor = abLoopBorderColor,
                contentDescriptionText = zoomedCd,
                prevChunkActionLabel = prevChunkActionLabel,
                nextChunkActionLabel = nextChunkActionLabel,
                seekBackActionLabel = seekBackActionLabel,
                onSeekTo = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = null
                    cachedAnchorCenterMs = clamped
                    onSeekTo(clamped)
                },
                onScrubChange = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = clamped
                    if (abs(clamped - cachedAnchorCenterMs) > zoomWindowMs) {
                        cachedAnchorCenterMs = clamped
                    }
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

@Composable
private fun OverviewWaveformCanvas(
    waveform: Waveform,
    durationMs: Long,
    zoomWindowMs: Long,
    chunks: List<ChunkUi>,
    loopMode: LoopMode,
    downsampler: WaveformDownsampler,
    positionProvider: PositionProvider,
    scrubPositionMs: Long?,
    frameTick: () -> Long,
    wavePeakColor: Color,
    waveRmsColor: Color,
    playheadColor: Color,
    boundaryTickColor: Color,
    viewportStrokeColor: Color,
    viewportFillColor: Color,
    abLoopHighlightColor: Color,
    contentDescriptionText: String,
    prevChunkActionLabel: String,
    nextChunkActionLabel: String,
    seekBackActionLabel: String,
    onSeekTo: (Long) -> Unit,
    onScrubChange: (Long) -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
) {
    var dragFraction by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clipToBounds()
            .systemGestureExclusion()
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
                val columnCount = max(1, (size.width / 3f).roundToInt())
                val downsampled = downsampler.downsample(
                    waveform = waveform,
                    startMs = 0L,
                    endMs = durationMs,
                    columnCount = columnCount,
                )
                val peakPath = buildWaveformBarsPath(
                    amplitudes = downsampled.peak,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                )
                val rmsPath = buildWaveformBarsPath(
                    amplitudes = downsampled.rms,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                )

                onDrawBehind {
                    // フレーム更新の購読（描画フェーズのみ）
                    frameTick()
                    val currentPosMs = (scrubPositionMs ?: positionProvider.currentPositionMs())
                        .coerceIn(0L, durationMs)

                    // ループ範囲ハイライト
                    drawLoopRangeOnOverview(
                        loopMode = loopMode,
                        chunks = chunks,
                        durationMs = durationMs,
                        abLoopHighlightColor = abLoopHighlightColor,
                    )

                    // チャンク境界の目盛り
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

                    // 波形バー（キャッシュ済み Path）
                    drawPath(path = peakPath, color = wavePeakColor)
                    drawPath(path = rmsPath, color = waveRmsColor)

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

                    // 再生ヘッド
                    val playheadX = (currentPosMs.toFloat() / durationMs.toFloat()) * size.width
                    drawLine(
                        color = playheadColor,
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    )
}

@Composable
private fun ZoomedWaveformCanvas(
    waveform: Waveform,
    durationMs: Long,
    zoomWindowMs: Long,
    cachedAnchorCenterMs: Long,
    onUpdateAnchorCenter: (Long) -> Unit,
    chunks: List<ChunkUi>,
    activeChunkIndex: Int?,
    languagePair: LanguagePair?,
    loopMode: LoopMode,
    downsampler: WaveformDownsampler,
    positionProvider: PositionProvider,
    scrubPositionMs: Long?,
    abDragStartMs: Long?,
    abDragCurrentMs: Long?,
    frameTick: () -> Long,
    wavePeakColor: Color,
    waveRmsColor: Color,
    playheadColor: Color,
    targetChunkColor: Color,
    baseChunkColor: Color,
    unknownChunkColor: Color,
    activeChunkHighlightColor: Color,
    abLoopHighlightColor: Color,
    abLoopBorderColor: Color,
    contentDescriptionText: String,
    prevChunkActionLabel: String,
    nextChunkActionLabel: String,
    seekBackActionLabel: String,
    onSeekTo: (Long) -> Unit,
    onScrubChange: (Long) -> Unit,
    onZoomWindowChange: (Long) -> Unit,
    onAbDragUpdate: (Long, Long) -> Unit,
    onAbDragFinish: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
) {
    // 再生中にアンカー中心から大きく離れたときだけ、緩やかにアンカー中心を更新して Path キャッシュを再構築する
    if (!LocalInspectionMode.current) {
        LaunchedEffect(zoomWindowMs, durationMs) {
            while (isActive) {
                withFrameNanos {
                    val pos = positionProvider.currentPositionMs().coerceIn(0L, durationMs)
                    if (abs(pos - cachedAnchorCenterMs) > (zoomWindowMs * 3L) / 4L) {
                        onUpdateAnchorCenter(pos)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clipToBounds()
            .systemGestureExclusion()
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
            .pointerInput(durationMs, zoomWindowMs) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        val centerMs = scrubPositionMs ?: positionProvider.currentPositionMs()
                        val deltaFraction = (offset.x / size.width.toFloat()) - 0.5f
                        val targetMs = (centerMs + (deltaFraction * zoomWindowMs).roundToLong())
                            .coerceIn(0L, durationMs)
                        onSeekTo(targetMs)
                    }
                }
            }
            .pointerInput(durationMs, zoomWindowMs) {
                var startTouchMs = 0L
                var currentTouchMs = 0L
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            val centerMs = scrubPositionMs ?: positionProvider.currentPositionMs()
                            val deltaFraction = (offset.x / size.width.toFloat()) - 0.5f
                            startTouchMs = (centerMs + (deltaFraction * zoomWindowMs).roundToLong())
                                .coerceIn(0L, durationMs)
                            currentTouchMs = startTouchMs
                            onAbDragUpdate(startTouchMs, currentTouchMs)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            val deltaMs = ((dragAmount.x / size.width.toFloat()) * zoomWindowMs).roundToLong()
                            currentTouchMs = (currentTouchMs + deltaMs).coerceIn(0L, durationMs)
                            onAbDragUpdate(startTouchMs, currentTouchMs)
                        }
                    },
                    onDragEnd = { onAbDragFinish() },
                    onDragCancel = { onAbDragFinish() },
                )
            }
            .pointerInput(durationMs, zoomWindowMs) {
                var activeScrubMs: Long? = null
                detectTransformGestures { _, pan, zoom, _ ->
                    if (size.width <= 0) return@detectTransformGestures
                    if (abs(zoom - 1f) > 0.01f) {
                        val scaledWindow = (zoomWindowMs.toFloat() / zoom).roundToLong()
                        onZoomWindowChange(scaledWindow)
                    } else if (abs(pan.x) > 0.5f) {
                        val baseMs = activeScrubMs ?: scrubPositionMs ?: positionProvider.currentPositionMs()
                        val deltaMs = ((-pan.x / size.width.toFloat()) * zoomWindowMs).roundToLong()
                        val nextMs = (baseMs + deltaMs).coerceIn(0L, durationMs)
                        activeScrubMs = nextMs
                        onScrubChange(nextMs)
                        onSeekTo(nextMs)
                    }
                }
            }
            .drawWithCache {
                // 表示範囲の 3 倍の幅をキャッシュ（§7.5.4）
                val cachedSpanMs = zoomWindowMs * 3L
                val cachedStartMs = cachedAnchorCenterMs - cachedSpanMs / 2L
                val cachedEndMs = cachedAnchorCenterMs + cachedSpanMs / 2L
                val singleWindowColumns = max(1, (size.width / 3f).roundToInt())
                val cachedColumns = singleWindowColumns * 3

                val downsampled = downsampler.downsample(
                    waveform = waveform,
                    startMs = cachedStartMs,
                    endMs = cachedEndMs,
                    columnCount = cachedColumns,
                )
                val cachedCanvasWidth = size.width * 3f
                val peakPath = buildWaveformBarsPath(
                    amplitudes = downsampled.peak,
                    canvasWidth = cachedCanvasWidth,
                    canvasHeight = size.height,
                )
                val rmsPath = buildWaveformBarsPath(
                    amplitudes = downsampled.rms,
                    canvasWidth = cachedCanvasWidth,
                    canvasHeight = size.height,
                )

                onDrawBehind {
                    frameTick()
                    val currentPosMs = (scrubPositionMs ?: positionProvider.currentPositionMs())
                        .coerceIn(0L, durationMs)
                    val windowStartMs = currentPosMs - zoomWindowMs / 2L
                    val windowEndMs = currentPosMs + zoomWindowMs / 2L

                    // チャンク領域を役割別の色で描画（学習言語 / 母語 / 不明）
                    val activeId: ChunkId? = activeChunkIndex?.let { chunks.getOrNull(it)?.id }
                    for (chunk in chunks) {
                        if (chunk.endMs <= windowStartMs || chunk.startMs >= windowEndMs) continue
                        val leftX = ((chunk.startMs - windowStartMs).toFloat() / zoomWindowMs.toFloat()) * size.width
                        val rightX = ((chunk.endMs - windowStartMs).toFloat() / zoomWindowMs.toFloat()) * size.width
                        val clampedLeft = leftX.coerceIn(0f, size.width)
                        val clampedRight = rightX.coerceIn(clampedLeft, size.width)
                        if (clampedRight <= clampedLeft) continue

                        val roleColor = when {
                            languagePair != null && chunk.language == languagePair.target -> targetChunkColor
                            languagePair?.base != null && chunk.language == languagePair.base -> baseChunkColor
                            else -> unknownChunkColor
                        }
                        drawRoundRect(
                            color = roleColor,
                            topLeft = Offset(clampedLeft, 2.dp.toPx()),
                            size = Size(clampedRight - clampedLeft, size.height - 4.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                        if (chunk.id == activeId) {
                            drawRoundRect(
                                color = activeChunkHighlightColor,
                                topLeft = Offset(clampedLeft, 0f),
                                size = Size(clampedRight - clampedLeft, size.height),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            )
                        }
                    }

                    // 3 倍幅のキャッシュ Path を平行移動して描画（§7.5.4）
                    val translationX =
                        ((cachedStartMs - windowStartMs).toFloat() / zoomWindowMs.toFloat()) * size.width
                    withTransform({ translate(left = translationX) }) {
                        drawPath(path = peakPath, color = wavePeakColor)
                        drawPath(path = rmsPath, color = waveRmsColor)
                    }

                    // A-B リピートまたはドラッグ中の A-B 範囲を強調
                    val abRange: Pair<Long, Long>? = when {
                        abDragStartMs != null && abDragCurrentMs != null ->
                            min(abDragStartMs, abDragCurrentMs) to max(abDragStartMs, abDragCurrentMs)

                        loopMode is LoopMode.AbRange ->
                            loopMode.startMs to loopMode.endMs

                        else -> null
                    }
                    abRange?.let { (abStart, abEnd) ->
                        if (abEnd > windowStartMs && abStart < windowEndMs) {
                            val leftX = ((abStart - windowStartMs).toFloat() / zoomWindowMs.toFloat())
                                .coerceIn(0f, size.width)
                            val rightX = ((abEnd - windowStartMs).toFloat() / zoomWindowMs.toFloat())
                                .coerceIn(leftX, size.width)
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

                    // 中央の再生ヘッド
                    val centerX = size.width * 0.5f
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoopRangeOnOverview(
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

private fun buildWaveformBarsPath(
    amplitudes: FloatArray,
    canvasWidth: Float,
    canvasHeight: Float,
): Path {
    val path = Path()
    val count = amplitudes.size
    if (count <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) return path

    val stepX = canvasWidth / count.toFloat()
    val barWidth = max(1f, stepX * 0.68f)
    val centerY = canvasHeight * 0.5f
    val maxHalfHeight = canvasHeight * 0.46f
    val minHalfHeight = 1.5f
    val cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)

    for (i in 0 until count) {
        val amp = amplitudes[i].coerceIn(0f, 1f)
        val halfH = max(minHalfHeight, amp * maxHalfHeight)
        val left = i * stepX + (stepX - barWidth) * 0.5f
        path.addRoundRect(
            RoundRect(
                rect = Rect(
                    left = left,
                    top = centerY - halfH,
                    right = left + barWidth,
                    bottom = centerY + halfH,
                ),
                cornerRadius = cornerRadius,
            ),
        )
    }
    return path
}

@Composable
private fun WaveformPlaceholderCard(
    featureStatus: StageStatus?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (featureStatus) {
                is StageStatus.Running -> {
                    val progress = featureStatus.progress
                    val label = if (progress != null) {
                        stringResource(
                            R.string.player_waveform_analyzing_percent,
                            (progress * 100f).roundToInt().coerceIn(0, 100),
                        )
                    } else {
                        stringResource(R.string.player_waveform_analyzing)
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is StageStatus.Failed -> {
                    Text(
                        text = stringResource(R.string.player_waveform_analysis_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.player_waveform_preparing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun WaveformSectionPreview() {
    val material = SampleMaterials.jaKo
    val chunks = material.chunks.map { chunk ->
        val transcript = material.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
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
