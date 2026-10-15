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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

/** 拡大波形で高解像度の近傍レンダリングに切り替える表示窓の閾値（10 秒 = 10,000ms）。 */
internal const val HIGH_RES_ZOOM_THRESHOLD_MS: Long = 10_000L

/** 拡大波形の最小表示窓（2 秒 = 2,000ms、§7.5.4）。 */
internal const val MIN_ZOOM_WINDOW_MS: Long = 2_000L

/** ピンチイン・ピンチアウト完了後に高精度波形を再描画するまでの遅延（0.2 秒 = 200ms）。 */
internal const val ZOOM_SETTLE_DELAY_MS: Long = 200L

private const val MAX_BITMAP_TILE_WIDTH_PX: Int = 2048
private const val MAX_TOTAL_STRIP_WIDTH_PX: Int = 16_384

/**
 * プレイヤー画面の波形セクション（全体波形 + 拡大波形。F4.1、F4.2、F6.2、§7.5.4）。
 *
 * - [positionProvider] は描画フェーズ（`onDrawBehind`）でのみ読み取り、再生位置の変化で再コンポーズしない（§6.2）。
 * - 再生中のみ `withFrameNanos` で描画レイヤーの無効化を行う。
 * - 拡大波形は [DEFAULT_ZOOM_WINDOW_MS]（30 秒）の解像度で全体波形をアルファチャンネル付き画像（[ImageBitmap]）として
 *   レンダリング・キャッシュし、水平方向の拡大縮小でスライス表示する。表示幅が [HIGH_RES_ZOOM_THRESHOLD_MS]（10 秒）より
 *   短くなった場合のみ、現在位置付近の波形を高解像度画像として再レンダリング・キャッシュして表示する。
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

    // 10秒未満の拡大表示時に近傍高解像度画像をキャッシュする中心時刻
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
    val chunkBoundaryLineColor = colorScheme.outlineVariant.copy(alpha = 0.55f)
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

            OverviewWaveformCanvas(
                waveform = waveform,
                durationMs = effectiveDurationMs,
                zoomWindowMs = zoomWindowMs,
                chunks = chunks,
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
                onSeekTo = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = null
                    cachedAnchorCenterMs = clamped
                    onSeekTo(clamped)
                },
                onScrubChange = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, effectiveDurationMs)
                    scrubPositionMs = clamped
                    cachedAnchorCenterMs = clamped
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
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
                playheadColor = playheadColor,
                chunkBoundaryLineColor = chunkBoundaryLineColor,
                targetActiveBorderColor = targetActiveBorderColor,
                baseActiveBorderColor = baseActiveBorderColor,
                unknownActiveBorderColor = unknownActiveBorderColor,
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
                onSeekToChunk = { chunkId ->
                    scrubPositionMs = null
                    val targetChunk = chunks.firstOrNull { it.id == chunkId }
                    if (targetChunk != null) {
                        cachedAnchorCenterMs = targetChunk.startMs.coerceIn(0L, effectiveDurationMs)
                    }
                    onSeekToChunk(chunkId)
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
                val widthPx = size.width.roundToInt().coerceAtLeast(1)
                val heightPx = size.height.roundToInt().coerceAtLeast(1)
                val overviewStrip = renderWaveformBitmapStrip(
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

                    // アルファチャンネル画像としてキャッシュ済みの全体波形を描画
                    drawWaveformBitmapStrip(
                        strip = overviewStrip,
                        windowStartMs = 0L,
                        windowEndMs = durationMs,
                    )

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
                    val barWidthPx = 4.dp.toPx()
                    val barRadiusPx = 2.dp.toPx()
                    drawRoundRect(
                        color = playheadColor,
                        topLeft = Offset(
                            x = (playheadX - barWidthPx * 0.5f).coerceIn(0f, (size.width - barWidthPx).coerceAtLeast(0f)),
                            y = 0f,
                        ),
                        size = Size(barWidthPx, size.height),
                        cornerRadius = CornerRadius(barRadiusPx, barRadiusPx),
                    )
                    val knobWidthPx = 10.dp.toPx()
                    val knobHeightPx = 6.dp.toPx()
                    val knobRadiusPx = 3.dp.toPx()
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
    unknownWavePeakColor: Color,
    unknownWaveRmsColor: Color,
    targetWavePeakColor: Color,
    targetWaveRmsColor: Color,
    baseWavePeakColor: Color,
    baseWaveRmsColor: Color,
    playheadColor: Color,
    chunkBoundaryLineColor: Color,
    targetActiveBorderColor: Color,
    baseActiveBorderColor: Color,
    unknownActiveBorderColor: Color,
    abLoopHighlightColor: Color,
    abLoopBorderColor: Color,
    contentDescriptionText: String,
    prevChunkActionLabel: String,
    nextChunkActionLabel: String,
    seekBackActionLabel: String,
    onSeekTo: (Long) -> Unit,
    onSeekToChunk: (ChunkId) -> Unit,
    onScrubChange: (Long) -> Unit,
    onZoomWindowChange: (Long) -> Unit,
    onAbDragUpdate: (Long, Long) -> Unit,
    onAbDragFinish: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSeekBack: () -> Unit,
) {
    val currentZoomWindowMs by rememberUpdatedState(zoomWindowMs)
    val currentChunks by rememberUpdatedState(chunks)

    // ピンチ操作中は既存のキャッシュ画像を水平スケーリングで即座に追従させ、
    // 表示幅が 10 秒未満になった場合は 0.2 秒（200ms）後に近傍の高解像度キャッシュ画像を再構築する
    var settledZoomWindowMs by remember(durationMs) { mutableLongStateOf(zoomWindowMs) }
    LaunchedEffect(zoomWindowMs) {
        if (zoomWindowMs != settledZoomWindowMs) {
            delay(ZOOM_SETTLE_DELAY_MS)
            val pos = (scrubPositionMs ?: positionProvider.currentPositionMs()).coerceIn(0L, durationMs)
            onUpdateAnchorCenter(pos)
            settledZoomWindowMs = zoomWindowMs
        }
    }

    // 10秒未満の拡大表示中に再生位置やプログレスバー移動で近傍キャッシュから離れた場合は中心時刻を更新する
    if (!LocalInspectionMode.current) {
        LaunchedEffect(settledZoomWindowMs, durationMs) {
            while (isActive) {
                withFrameNanos {
                    val pos = (scrubPositionMs ?: positionProvider.currentPositionMs()).coerceIn(0L, durationMs)
                    val thresholdMs = max(settledZoomWindowMs, 3_000L)
                    if (abs(pos - cachedAnchorCenterMs) > thresholdMs) {
                        onUpdateAnchorCenter(pos)
                    }
                }
            }
        }
    }

    // Canvas サイズを記憶して、デフォルト表示幅（30秒）の全体波形画像を 1 度だけ生成・キャッシュする
    var canvasWidthPx by remember { mutableStateOf(0) }
    var canvasHeightPx by remember { mutableStateOf(0) }

    val fullWaveformStrip = remember(
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        chunks,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
    ) {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            val defaultWindowMs = DEFAULT_ZOOM_WINDOW_MS.coerceAtMost(durationMs).coerceAtLeast(1L)
            val virtualTotalWidthPx = ((durationMs.toDouble() / defaultWindowMs.toDouble()) * canvasWidthPx.toDouble())
                .roundToInt()
                .coerceIn(canvasWidthPx, MAX_TOTAL_STRIP_WIDTH_PX)
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = 0L,
                endMs = durationMs,
                totalWidthPx = virtualTotalWidthPx,
                heightPx = canvasHeightPx,
                chunks = chunks,
                languagePair = languagePair,
                unknownWavePeakColor = unknownWavePeakColor,
                unknownWaveRmsColor = unknownWaveRmsColor,
                targetWavePeakColor = targetWavePeakColor,
                targetWaveRmsColor = targetWaveRmsColor,
                baseWavePeakColor = baseWavePeakColor,
                baseWaveRmsColor = baseWaveRmsColor,
            )
        }
    }

    val isHighResMode = zoomWindowMs < HIGH_RES_ZOOM_THRESHOLD_MS || settledZoomWindowMs < HIGH_RES_ZOOM_THRESHOLD_MS
    val highResWindowMs = min(zoomWindowMs, settledZoomWindowMs).coerceIn(MIN_ZOOM_WINDOW_MS, HIGH_RES_ZOOM_THRESHOLD_MS)
    val highResStrip = remember(
        isHighResMode,
        waveform,
        durationMs,
        canvasWidthPx,
        canvasHeightPx,
        cachedAnchorCenterMs,
        settledZoomWindowMs.coerceAtMost(HIGH_RES_ZOOM_THRESHOLD_MS),
        chunks,
        languagePair,
        unknownWavePeakColor,
        unknownWaveRmsColor,
        targetWavePeakColor,
        targetWaveRmsColor,
        baseWavePeakColor,
        baseWaveRmsColor,
    ) {
        if (!isHighResMode || canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            null
        } else {
            val baseWindowMs = settledZoomWindowMs.coerceIn(MIN_ZOOM_WINDOW_MS, HIGH_RES_ZOOM_THRESHOLD_MS)
            val spanMs = max(baseWindowMs * 4L, 20_000L).coerceAtMost(durationMs)
            val halfSpan = spanMs / 2L
            val startMs = (cachedAnchorCenterMs - halfSpan).coerceIn(0L, (durationMs - spanMs).coerceAtLeast(0L))
            val endMs = (startMs + spanMs).coerceAtMost(durationMs)
            val stripWidthPx = ((spanMs.toDouble() / baseWindowMs.toDouble()) * canvasWidthPx.toDouble())
                .roundToInt()
                .coerceIn(canvasWidthPx, MAX_BITMAP_TILE_WIDTH_PX * 2)
            renderWaveformBitmapStrip(
                waveform = waveform,
                downsampler = downsampler,
                startMs = startMs,
                endMs = endMs,
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
            )
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
            .pointerInput(durationMs) {
                detectTapGestures(
                    onTap = { offset ->
                        if (size.width > 0) {
                            val liveWindow = currentZoomWindowMs
                            val centerMs = scrubPositionMs ?: positionProvider.currentPositionMs()
                            val deltaFraction = (offset.x / size.width.toFloat()) - 0.5f
                            val targetMs = (centerMs + (deltaFraction * liveWindow).roundToLong())
                                .coerceIn(0L, durationMs)
                            onSeekTo(targetMs)
                        }
                    },
                    onDoubleTap = { offset ->
                        if (size.width > 0) {
                            val liveWindow = currentZoomWindowMs
                            val centerMs = scrubPositionMs ?: positionProvider.currentPositionMs()
                            val deltaFraction = (offset.x / size.width.toFloat()) - 0.5f
                            val targetMs = (centerMs + (deltaFraction * liveWindow).roundToLong())
                                .coerceIn(0L, durationMs)
                            val targetChunk = currentChunks.firstOrNull { targetMs in it.startMs..it.endMs }
                            if (targetChunk != null) {
                                onSeekToChunk(targetChunk.id)
                            } else {
                                onSeekTo(targetMs)
                            }
                        }
                    },
                )
            }
            .pointerInput(durationMs) {
                var startTouchMs = 0L
                var currentTouchMs = 0L
                var initialChunkStartMs: Long? = null
                var initialChunkEndMs: Long? = null
                var totalDragPx = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            val liveWindow = currentZoomWindowMs
                            val centerMs = scrubPositionMs ?: positionProvider.currentPositionMs()
                            val deltaFraction = (offset.x / size.width.toFloat()) - 0.5f
                            val touchMs = (centerMs + (deltaFraction * liveWindow).roundToLong())
                                .coerceIn(0L, durationMs)
                            startTouchMs = touchMs
                            currentTouchMs = touchMs
                            totalDragPx = 0f
                            val touchedChunk = currentChunks.firstOrNull { touchMs in it.startMs..it.endMs }
                            if (touchedChunk != null) {
                                initialChunkStartMs = touchedChunk.startMs
                                initialChunkEndMs = touchedChunk.endMs
                                onAbDragUpdate(touchedChunk.startMs, touchedChunk.endMs)
                            } else {
                                initialChunkStartMs = null
                                initialChunkEndMs = null
                                onAbDragUpdate(startTouchMs, currentTouchMs)
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            val liveWindow = currentZoomWindowMs
                            totalDragPx += abs(dragAmount.x)
                            val deltaMs = ((dragAmount.x / size.width.toFloat()) * liveWindow).roundToLong()
                            currentTouchMs = (currentTouchMs + deltaMs).coerceIn(0L, durationMs)
                            val chunkStart = initialChunkStartMs
                            val chunkEnd = initialChunkEndMs
                            if (chunkStart != null && chunkEnd != null) {
                                if (totalDragPx < 8.dp.toPx()) {
                                    onAbDragUpdate(chunkStart, chunkEnd)
                                } else {
                                    onAbDragUpdate(
                                        min(chunkStart, currentTouchMs),
                                        max(chunkEnd, currentTouchMs),
                                    )
                                }
                            } else {
                                onAbDragUpdate(startTouchMs, currentTouchMs)
                            }
                        }
                    },
                    onDragEnd = { onAbDragFinish() },
                    onDragCancel = { onAbDragFinish() },
                )
            }
            .pointerInput(durationMs) {
                var activeScrubMs: Long? = null
                detectTransformGestures { _, pan, zoom, _ ->
                    if (size.width <= 0) return@detectTransformGestures
                    val liveWindow = currentZoomWindowMs
                    if (abs(zoom - 1f) > 0.004f) {
                        val scaledWindow = (liveWindow.toFloat() / zoom).roundToLong()
                        onZoomWindowChange(scaledWindow)
                    } else if (abs(pan.x) > 0.5f) {
                        val baseMs = activeScrubMs ?: scrubPositionMs ?: positionProvider.currentPositionMs()
                        val deltaMs = ((-pan.x / size.width.toFloat()) * liveWindow).roundToLong()
                        val nextMs = (baseMs + deltaMs).coerceIn(0L, durationMs)
                        activeScrubMs = nextMs
                        onScrubChange(nextMs)
                        onSeekTo(nextMs)
                    }
                }
            }
            .drawWithCache {
                val wPx = size.width.roundToInt().coerceAtLeast(1)
                val hPx = size.height.roundToInt().coerceAtLeast(1)
                if (canvasWidthPx != wPx || canvasHeightPx != hPx) {
                    canvasWidthPx = wPx
                    canvasHeightPx = hPx
                }

                val fallbackFullStrip = fullWaveformStrip ?: renderWaveformBitmapStrip(
                    waveform = waveform,
                    downsampler = downsampler,
                    startMs = 0L,
                    endMs = durationMs,
                    totalWidthPx = ((durationMs.toDouble() / DEFAULT_ZOOM_WINDOW_MS.coerceAtMost(durationMs).coerceAtLeast(1L).toDouble()) * wPx.toDouble())
                        .roundToInt()
                        .coerceIn(wPx, MAX_TOTAL_STRIP_WIDTH_PX),
                    heightPx = hPx,
                    chunks = chunks,
                    languagePair = languagePair,
                    unknownWavePeakColor = unknownWavePeakColor,
                    unknownWaveRmsColor = unknownWaveRmsColor,
                    targetWavePeakColor = targetWavePeakColor,
                    targetWaveRmsColor = targetWaveRmsColor,
                    baseWavePeakColor = baseWavePeakColor,
                    baseWaveRmsColor = baseWaveRmsColor,
                )

                onDrawBehind {
                    frameTick()
                    val liveWindowMs = zoomWindowMs.coerceAtLeast(1L)
                    val currentPosMs = (scrubPositionMs ?: positionProvider.currentPositionMs())
                        .coerceIn(0L, durationMs)
                    val windowStartMs = currentPosMs - liveWindowMs / 2L
                    val windowEndMs = currentPosMs + liveWindowMs / 2L
                    val centerX = size.width * 0.5f

                    val activeId: ChunkId? = activeChunkIndex?.let { chunks.getOrNull(it)?.id }

                    // 1. チャンク境界線（背景色は付けず、波形自体の色分けを際立たせる）
                    for (chunk in chunks) {
                        if (chunk.endMs <= windowStartMs || chunk.startMs >= windowEndMs) continue
                        val leftX = ((chunk.startMs - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width
                        val rightX = ((chunk.endMs - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width
                        if (leftX in 0f..size.width) {
                            drawLine(
                                color = chunkBoundaryLineColor,
                                start = Offset(leftX, 0f),
                                end = Offset(leftX, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                        if (rightX in 0f..size.width) {
                            drawLine(
                                color = chunkBoundaryLineColor,
                                start = Offset(rightX, 0f),
                                end = Offset(rightX, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }

                    // 2. アルファチャンネル付き波形キャッシュ画像を水平方向に拡大縮小して描画
                    // 表示幅が10秒未満かつ近傍高解像度キャッシュの範囲内なら高解像度画像を使い、
                    // 全体波形やプログレスバー移動直後などで近傍範囲外に飛んだ場合も全体キャッシュ画像で空白なく即座に描画する
                    val localStrip = highResStrip
                    if (liveWindowMs < HIGH_RES_ZOOM_THRESHOLD_MS &&
                        localStrip != null &&
                        windowStartMs >= localStrip.startMs &&
                        windowEndMs <= localStrip.endMs
                    ) {
                        drawWaveformBitmapStrip(
                            strip = localStrip,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                        )
                    } else {
                        drawWaveformBitmapStrip(
                            strip = fallbackFullStrip,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                        )
                    }

                    // 3. アクティブチャンクの枠線
                    for (chunk in chunks) {
                        if (chunk.id != activeId) continue
                        if (chunk.endMs <= windowStartMs || chunk.startMs >= windowEndMs) continue
                        val leftX = ((chunk.startMs - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width
                        val rightX = ((chunk.endMs - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width
                        val clampedLeft = leftX.coerceIn(0f, size.width)
                        val clampedRight = rightX.coerceIn(clampedLeft, size.width)
                        if (clampedRight <= clampedLeft) continue

                        val isTargetChunk = matchesLanguageRole(chunk.language, languagePair?.target)
                        val isBaseChunk = matchesLanguageRole(chunk.language, languagePair?.base)
                        val borderColor = when {
                            isTargetChunk -> targetActiveBorderColor
                            isBaseChunk -> baseActiveBorderColor
                            else -> unknownActiveBorderColor
                        }
                        drawRoundRect(
                            color = borderColor,
                            topLeft = Offset(clampedLeft, 0f),
                            size = Size(clampedRight - clampedLeft, size.height),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
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
                            val leftX = (((abStart - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width)
                                .coerceIn(0f, size.width)
                            val rightX = (((abEnd - windowStartMs).toFloat() / liveWindowMs.toFloat()) * size.width)
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
 */
private fun renderWaveformBitmapStrip(
    waveform: Waveform,
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
): WaveformBitmapStrip {
    val safeWidthPx = totalWidthPx.coerceIn(1, MAX_TOTAL_STRIP_WIDTH_PX)
    val safeHeightPx = heightPx.coerceAtLeast(1)
    val spanMs = (endMs - startMs).coerceAtLeast(1L)
    val columnCount = max(1, (safeWidthPx / 3f).roundToInt())
    val downsampled = downsampler.downsample(
        waveform = waveform,
        startMs = startMs,
        endMs = endMs,
        columnCount = columnCount,
    )

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
            while (chunkSearchIndex < chunks.size && chunks[chunkSearchIndex].endMs < colCenterMs) {
                chunkSearchIndex++
            }
            val matchedChunk = chunks.getOrNull(chunkSearchIndex)?.takeIf {
                colCenterMs in it.startMs..it.endMs
            } ?: chunks.firstOrNull { colCenterMs in it.startMs..it.endMs }

            val isTarget = matchedChunk != null && matchesLanguageRole(matchedChunk.language, languagePair?.target)
            val isBase = matchedChunk != null && matchesLanguageRole(matchedChunk.language, languagePair?.base)

            val peakPaint = when {
                isTarget -> targetPeakPaint
                isBase -> basePeakPaint
                else -> unknownPeakPaint
            }
            val rmsPaint = when {
                isTarget -> targetRmsPaint
                isBase -> baseRmsPaint
                else -> unknownRmsPaint
            }

            val peakAmp = downsampled.peak[i].coerceIn(0f, 1f)
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

            val rmsAmp = downsampled.rms[i].coerceIn(0f, 1f)
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
 * キャッシュ済みの [WaveformBitmapStrip] から `[windowStartMs, windowEndMs]` の範囲を切り出し、
 * 水平方向に拡大縮小して現在の [DrawScope] に描画する。
 */
private fun DrawScope.drawWaveformBitmapStrip(
    strip: WaveformBitmapStrip,
    windowStartMs: Long,
    windowEndMs: Long,
) {
    val stripSpanMs = (strip.endMs - strip.startMs).coerceAtLeast(1L)
    val windowSpanMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)
    val visibleStartMs = max(windowStartMs, strip.startMs)
    val visibleEndMs = min(windowEndMs, strip.endMs)
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
