package com.miuraken.chunkflow.feature.library

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.miuraken.chunkflow.core.designsystem.component.language.LanguagePairBadge
import com.miuraken.chunkflow.core.designsystem.component.playback.CompletedTranscriptBadgeColor
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.MediaId
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LAST_ACCESSED_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)

/** お気に入り登録済み（⭐️）の塗りつぶし星アイコン色。 */
internal val FavoriteStarColor = Color(0xFFFFB300)

/** デフォルトの音符サムネイル背景色（落ち着いたグレー）。 */
private val DefaultThumbnailBackgroundColor = Color(0xFF8A8A8E)

/** この割合（%）を超えて聴いた教材を既習として、進捗バーと再生状況を緑系で表示する。 */
internal const val COMPLETED_PROGRESS_PERCENT_THRESHOLD = 95

/** 音声ファイルの埋め込みカバーアート（ImageBitmap）のメモリキャッシュ。 */
private val embeddedArtworkCache = object : LruCache<String, ImageBitmap>(64) {}

private val noEmbeddedArtworkKeys = java.util.Collections.synchronizedSet(HashSet<String>())

private fun loadEmbeddedArtworkBitmap(
    context: Context,
    mediaId: MediaId,
    localFilePath: String?,
    sourceUri: String?,
): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        var initialized = false
        if (!localFilePath.isNullOrBlank()) {
            val file = File(localFilePath)
            if (file.exists()) {
                runCatching {
                    retriever.setDataSource(file.absolutePath)
                    initialized = true
                }
            }
        }
        if (!initialized) {
            val internalMediaDir = File(context.filesDir, "media")
            val directFile = File(internalMediaDir, mediaId.value)
            val candidateFile = when {
                directFile.exists() -> directFile

                internalMediaDir.exists() -> internalMediaDir.listFiles()?.firstOrNull {
                    it.name == mediaId.value || it.name.startsWith("${mediaId.value}.")
                }

                else -> null
            }
            if (candidateFile != null && candidateFile.exists()) {
                runCatching {
                    retriever.setDataSource(candidateFile.absolutePath)
                    initialized = true
                }
            }
        }
        if (!initialized && !sourceUri.isNullOrBlank()) {
            runCatching {
                retriever.setDataSource(context, sourceUri.toUri())
                initialized = true
            }
        }
        if (!initialized) {
            null
        } else {
            val pictureBytes = retriever.embeddedPicture
            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)?.asImageBitmap()
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatEpochMsLocal(epochMs: Long): String = runCatching {
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(LAST_ACCESSED_FORMATTER)
}.getOrDefault("")

/**
 * 左端のドラッグハンドル。上下へのドラッグ量を検知して 1 行分の高さごとに [onMove] を発火する。
 */
@Composable
internal fun ReorderDragHandle(
    currentIndex: Int,
    totalCount: Int,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stepPx = with(density) { 56.dp.toPx() }
    val updatedIndex by rememberUpdatedState(currentIndex)
    val updatedTotalCount by rememberUpdatedState(totalCount)
    val updatedOnMove by rememberUpdatedState(onMove)
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    val dragHandleCd = stringResource(R.string.library_drag_handle_cd)

    Box(
        modifier = modifier
            .size(32.dp)
            .semantics { contentDescription = dragHandleCd }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { accumulatedDragY = 0f },
                    onDragEnd = { accumulatedDragY = 0f },
                    onDragCancel = { accumulatedDragY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDragY += dragAmount
                        val steps = (accumulatedDragY / stepPx).toInt()
                        if (steps != 0) {
                            val from = updatedIndex
                            val to = (from + steps).coerceIn(0, (updatedTotalCount - 1).coerceAtLeast(0))
                            if (to != from) {
                                updatedOnMove(from, to)
                                accumulatedDragY -= (to - from) * stepPx
                            } else {
                                accumulatedDragY = 0f
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ChunkFlowIcons.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryItemRow(
    itemModel: LibraryItemUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditMetadataClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteClick: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavoriteClick: (() -> Unit)? = null,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val editCd = stringResource(R.string.library_edit_metadata_cd)
    val favoriteCd = stringResource(
        if (isFavorite) R.string.library_favorite_remove_cd else R.string.library_favorite_add_cd,
    )
    val removeFromPlaylistCd = stringResource(R.string.library_playlist_remove_item_cd)
    val langPalette = chunkFlowLanguagePalette()
    val maxPercent = itemModel.maxProgressPercent
    // 既習（95% 超）の教材は、進捗バーを緑系にして一目でわかるようにする。
    val isCompleted = maxPercent > COMPLETED_PROGRESS_PERCENT_THRESHOLD

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(
                start = if (dragHandle != null && !isSelectionMode) 8.dp else 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (dragHandle != null) {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                dragHandle()
            }
        }

        AudioItemThumbnail(
            mediaId = item.id,
            localFilePath = item.localFilePath,
            sourceUri = item.sourceUri,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(40.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
        ) {
            // 1 行目: ファイル名（タイトル）を横幅いっぱいに表示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))

            // 2 行目: 言語ペアバッジ + SpeechToText 文字起こし状態 + 右端に ☆ と ✏️
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguagePairBadge(pair = item.languagePair)
                    TranscriptStatusIndicator(
                        transcriptStatus = itemModel.transcriptStatus,
                        transcriptProgressPercent = itemModel.transcriptProgressPercent,
                        isTranscribing = itemModel.isTranscribing,
                        completedBadgeColor = CompletedTranscriptBadgeColor,
                    )
                    if (itemModel.isAnalyzing) {
                        Text(
                            text = stringResource(R.string.library_item_analyzing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                    if (itemModel.isAudioMissing) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = stringResource(R.string.library_item_missing_audio),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (!isSelectionMode) {
                    if (onToggleFavoriteClick != null) {
                        IconButton(
                            onClick = onToggleFavoriteClick,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) {
                                    ChunkFlowIcons.Favorite
                                } else {
                                    ChunkFlowIcons.FavoriteBorder
                                },
                                contentDescription = favoriteCd,
                                tint = if (isFavorite) {
                                    FavoriteStarColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onEditMetadataClick,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Edit,
                            contentDescription = editCd,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    if (onRemoveFromPlaylistClick != null) {
                        IconButton(
                            onClick = onRemoveFromPlaylistClick,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = removeFromPlaylistCd,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { itemModel.maxProgressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (isCompleted) {
                    langPalette.completedProgressIndicator
                } else {
                    ProgressIndicatorDefaults.linearColor
                },
            )
            Spacer(modifier = Modifier.height(2.dp))

            // 3 行目: 再生時間・進捗 と 最終アクセス日時（History アイコン + 日時）
            val hasProgress = itemModel.maxPositionMs > 0L || itemModel.positionMs > 0L
            val durationProgressText = if (hasProgress) {
                val baseResume = stringResource(
                    R.string.library_item_resume_position,
                    formatDurationMs(itemModel.positionMs),
                    formattedDuration,
                    maxPercent,
                )
                val speedSuffix = if (itemModel.speed != 1.0f) {
                    " · ${formatSpeed(itemModel.speed)}"
                } else {
                    ""
                }
                baseResume + speedSuffix
            } else {
                stringResource(
                    R.string.library_item_duration_progress,
                    formatDurationMs(0L),
                    formattedDuration,
                )
            }
            val formattedLastAccessed = itemModel.lastAccessedAtEpochMs?.let { epochMs ->
                formatEpochMsLocal(epochMs).takeIf { it.isNotEmpty() }
            }
            val progressStatusColor = when {
                isCompleted -> langPalette.completedProgressText
                hasProgress || itemModel.lastAccessedAtEpochMs != null -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val lastAccessedColor = MaterialTheme.colorScheme.onSurfaceVariant

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = durationProgressText,
                    style = MaterialTheme.typography.labelSmall,
                    color = progressStatusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (!isSelectionMode && formattedLastAccessed != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = lastAccessedColor,
                        )
                        Icon(
                            imageVector = ChunkFlowIcons.History,
                            contentDescription = null,
                            tint = lastAccessedColor,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = formattedLastAccessed,
                            style = MaterialTheme.typography.labelSmall,
                            color = lastAccessedColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptStatusIndicator(
    transcriptStatus: LibraryTranscriptStatus,
    transcriptProgressPercent: Int,
    isTranscribing: Boolean,
    completedBadgeColor: Color,
    modifier: Modifier = Modifier,
) {
    when {
        transcriptStatus == LibraryTranscriptStatus.COMPLETED -> {
            val completedCd = stringResource(R.string.library_item_transcript_completed)
            Box(
                modifier = modifier
                    .size(22.dp)
                    .background(color = completedBadgeColor, shape = CircleShape)
                    .semantics { contentDescription = completedCd },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.SpeechToText,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        isTranscribing || transcriptStatus == LibraryTranscriptStatus.PARTIAL -> {
            val statusCd = if (isTranscribing) {
                stringResource(R.string.library_item_transcript_in_progress, transcriptProgressPercent)
            } else {
                stringResource(R.string.library_item_transcript_partial, transcriptProgressPercent)
            }
            Row(
                modifier = modifier.semantics(mergeDescendants = true) {
                    contentDescription = statusCd
                },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.SpeechToText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                if (isTranscribing) {
                    if (LocalInspectionMode.current) {
                        CircularProgressIndicator(
                            progress = { 0.75f },
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = Color.Transparent,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "$transcriptProgressPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            val noneCd = stringResource(R.string.library_item_transcript_none)
            Icon(
                imageVector = ChunkFlowIcons.SpeechToText,
                contentDescription = noneCd,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                modifier = modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun AudioItemThumbnail(
    mediaId: MediaId,
    localFilePath: String?,
    sourceUri: String?,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    val context = LocalContext.current
    val cacheKey = remember(mediaId.value, localFilePath, sourceUri) {
        "${mediaId.value}|${localFilePath.orEmpty()}|${sourceUri.orEmpty()}"
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            if (isInspectionMode) null else embeddedArtworkCache.get(cacheKey),
        )
    }

    LaunchedEffect(cacheKey, isInspectionMode) {
        if (!isInspectionMode && bitmap == null && !noEmbeddedArtworkKeys.contains(cacheKey)) {
            val loaded = withContext(Dispatchers.IO) {
                loadEmbeddedArtworkBitmap(
                    context = context,
                    mediaId = mediaId,
                    localFilePath = localFilePath,
                    sourceUri = sourceUri,
                )
            }
            if (loaded != null) {
                embeddedArtworkCache.put(cacheKey, loaded)
                bitmap = loaded
            } else {
                noEmbeddedArtworkKeys.add(cacheKey)
            }
        }
    }

    val shape = RoundedCornerShape(6.dp)
    val previewStyle = if (isInspectionMode) {
        when {
            localFilePath == "preview:cover_pimsleur" || sourceUri == "preview:cover_pimsleur" -> 1
            localFilePath == "preview:cover_book" || sourceUri == "preview:cover_book" -> 2
            else -> 0
        }
    } else {
        0
    }

    val currentBitmap = bitmap
    when {
        currentBitmap != null -> {
            Image(
                bitmap = currentBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(shape),
            )
        }

        previewStyle == 1 -> {
            PreviewPimsleurArtwork(modifier = modifier.clip(shape))
        }

        previewStyle == 2 -> {
            PreviewBookArtwork(modifier = modifier.clip(shape))
        }

        else -> {
            Box(
                modifier = modifier
                    .background(color = DefaultThumbnailBackgroundColor, shape = shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewPimsleurArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color.White)) {
        val w = size.width
        val h = size.height
        drawRect(
            color = Color(0xFF0D47A1),
            topLeft = Offset.Zero,
            size = Size(w * 0.55f, h),
        )
        drawCircle(
            color = Color(0xFF1976D2),
            radius = w * 0.42f,
            center = Offset(w * 0.35f, h * 0.45f),
        )
        val wavePath = Path().apply {
            moveTo(0f, h * 0.65f)
            cubicTo(w * 0.3f, h * 0.5f, w * 0.5f, h * 0.85f, w, h * 0.7f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(path = wavePath, color = Color(0xFF42A5F5).copy(alpha = 0.75f))
    }
}

@Composable
private fun PreviewBookArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFFECEFF1))) {
        val w = size.width
        val h = size.height
        drawRect(
            color = Color(0xFF81D4FA),
            topLeft = Offset(w * 0.08f, h * 0.08f),
            size = Size(w * 0.84f, h * 0.28f),
        )
        drawRect(
            color = Color(0xFF90A4AE),
            topLeft = Offset(w * 0.08f, h * 0.36f),
            size = Size(w * 0.84f, h * 0.56f),
        )
        drawRect(
            color = Color(0xFF546E7A),
            topLeft = Offset(w * 0.18f, h * 0.48f),
            size = Size(w * 0.64f, h * 0.34f),
        )
    }
}
