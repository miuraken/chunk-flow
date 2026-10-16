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
)
