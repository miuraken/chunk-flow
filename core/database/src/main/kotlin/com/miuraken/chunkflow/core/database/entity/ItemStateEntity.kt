package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams

/**
 * 教材ごとの再生位置・速度・学習モードの状態（§6.4 `item_state` テーブル）。
 */
@Entity(
    tableName = "item_state",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ItemStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long = 0L,
    @ColumnInfo(name = "speed")
    val speed: Float = 1.0f,
    @ColumnInfo(name = "chunk_source")
    val chunkSource: String = ChunkOrigin.AUTO.name,
    @ColumnInfo(name = "auto_pause")
    val autoPause: String = AUTO_PAUSE_OFF,
    @ColumnInfo(name = "auto_pause_scope")
    val autoPauseScope: String = AutoPauseScope.ALL_CHUNKS.name,
    @ColumnInfo(name = "language_filter")
    val languageFilter: String = LanguageFilter.ALL.name,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
    @ColumnInfo(name = "is_listened", defaultValue = "0")
    val isListened: Boolean = false,
    @ColumnInfo(name = "max_position_ms", defaultValue = "0")
    val maxPositionMs: Long = 0L,
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "queued_at", defaultValue = "0")
    val queuedAt: Long = 0L,
    @ColumnInfo(name = "access_count", defaultValue = "0")
    val accessCount: Int = 0,
    @ColumnInfo(name = "custom_segmentation")
    val customSegmentation: String? = null,
) {
    companion object {
        internal const val AUTO_PAUSE_OFF = "OFF"
        private const val PREFIX_FIXED = "FIXED:"
        private const val PREFIX_PROPORTIONAL = "PROPORTIONAL:"

        /** [AutoPause] を DB 保存用の文字列表現に変換する。 */
        fun encodeAutoPause(autoPause: AutoPause): String = when (autoPause) {
            AutoPause.Off -> AUTO_PAUSE_OFF
            is AutoPause.Fixed -> "$PREFIX_FIXED${autoPause.pauseMs}"
            is AutoPause.Proportional -> "$PREFIX_PROPORTIONAL${autoPause.factor}:${autoPause.minMs}"
        }

        /** DB 保存用の文字列表現から [AutoPause] を復元する。不正な文字列は [AutoPause.Off] にフォールバックする。 */
        fun decodeAutoPause(raw: String): AutoPause = when {
            raw == AUTO_PAUSE_OFF -> AutoPause.Off

            raw.startsWith(PREFIX_FIXED) -> {
                val pauseMs = raw.removePrefix(PREFIX_FIXED).toLongOrNull()
                if (pauseMs != null && pauseMs >= 0L) AutoPause.Fixed(pauseMs) else AutoPause.Off
            }

            raw.startsWith(PREFIX_PROPORTIONAL) -> {
                val parts = raw.removePrefix(PREFIX_PROPORTIONAL).split(':')
                val factor = parts.getOrNull(0)?.toFloatOrNull()
                val minMs = parts.getOrNull(1)?.toLongOrNull()
                if (factor != null && minMs != null) {
                    AutoPause.Proportional(factor = factor, minMs = minMs)
                } else {
                    AutoPause.Off
                }
            }

            else -> AutoPause.Off
        }

        /** [SegmentationParams] を DB 保存用の文字列表現に変換する（`null` のときは `null`）。 */
        fun encodeSegmentationParams(params: SegmentationParams?): String? = params?.let {
            "${it.minSilenceMs}:${it.minSpeechMs}:${it.mergeGapMs}:${it.maxChunkMs}:" +
                "${it.prePaddingMs}:${it.postPaddingMs}:${it.thresholdRatio}"
        }

        /** DB 保存用の文字列表現から [SegmentationParams] を復元する。不正・未設定は `null` を返す。 */
        fun decodeSegmentationParams(raw: String?): SegmentationParams? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size < 7) return null
            val minSilenceMs = parts[0].toIntOrNull() ?: return null
            val minSpeechMs = parts[1].toIntOrNull() ?: return null
            val mergeGapMs = parts[2].toIntOrNull() ?: return null
            val maxChunkMs = parts[3].toIntOrNull() ?: return null
            val prePaddingMs = parts[4].toIntOrNull() ?: return null
            val postPaddingMs = parts[5].toIntOrNull() ?: return null
            val thresholdRatio = parts[6].toFloatOrNull() ?: return null
            return runCatching {
                SegmentationParams(
                    minSilenceMs = minSilenceMs,
                    minSpeechMs = minSpeechMs,
                    mergeGapMs = mergeGapMs,
                    maxChunkMs = maxChunkMs,
                    prePaddingMs = prePaddingMs,
                    postPaddingMs = postPaddingMs,
                    thresholdRatio = thresholdRatio.coerceIn(0f, 1f),
                )
            }.getOrNull()
        }
    }
}

/** [ItemStateEntity] をドメインモデルの [ItemState] に変換する。 */
fun ItemStateEntity.toExternalModel(): ItemState {
    val coercedPos = positionMs.coerceAtLeast(0L)
    val coercedMaxPos = maxOf(maxPositionMs.coerceAtLeast(0L), coercedPos)
    return ItemState(
        mediaId = MediaId(mediaId),
        positionMs = coercedPos,
        speed = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
        chunkSource = ChunkOrigin.entries.firstOrNull { it.name == chunkSource } ?: ChunkOrigin.AUTO,
        autoPause = ItemStateEntity.decodeAutoPause(autoPause),
        autoPauseScope = AutoPauseScope.entries.firstOrNull { it.name == autoPauseScope }
            ?: AutoPauseScope.ALL_CHUNKS,
        languageFilter = LanguageFilter.entries.firstOrNull { it.name == languageFilter }
            ?: LanguageFilter.ALL,
        updatedAtEpochMs = updatedAt,
        isListened = isListened,
        maxPositionMs = coercedMaxPos,
        isFavorite = isFavorite,
        queuedAtEpochMs = queuedAt.coerceAtLeast(0L),
        accessCount = accessCount.coerceAtLeast(0),
        customSegmentationParams = ItemStateEntity.decodeSegmentationParams(customSegmentation),
    )
}

/** ドメインモデルの [ItemState] を [ItemStateEntity] に変換する。 */
fun ItemState.toEntity(): ItemStateEntity = ItemStateEntity(
    mediaId = mediaId.value,
    positionMs = positionMs,
    speed = speed,
    chunkSource = chunkSource.name,
    autoPause = ItemStateEntity.encodeAutoPause(autoPause),
    autoPauseScope = autoPauseScope.name,
    languageFilter = languageFilter.name,
    updatedAt = updatedAtEpochMs,
    isListened = isListened,
    maxPositionMs = maxOf(maxPositionMs, positionMs),
    isFavorite = isFavorite,
    queuedAt = queuedAtEpochMs.coerceAtLeast(0L),
    accessCount = accessCount.coerceAtLeast(0),
    customSegmentation = ItemStateEntity.encodeSegmentationParams(customSegmentationParams),
)
