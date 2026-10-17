package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SttEngine

/**
 * 教材ごとの文字起こしエンジンの上書き（`item_stt_override` テーブル。ADR-0011 §2.3）。
 *
 * エンジンの列が `NULL` なら、その言語は言語ごとの設定に従う。両方とも `NULL` の行は作らない（行を消す）。
 *
 * @property mediaId 教材の ID（主キー。`media_item` への外部キーで、教材を消すと一緒に消える）。
 * @property targetEngine 学習言語のチャンクに使うエンジン（[SttEngine.name]）。
 * @property baseEngine ベース言語のチャンクに使うエンジン（[SttEngine.name]）。
 */
@Entity(
    tableName = "item_stt_override",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ItemSttOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "target_engine")
    val targetEngine: String? = null,
    @ColumnInfo(name = "base_engine")
    val baseEngine: String? = null,
)

/** DB の行をドメインモデルに変換する。知らないエンジン名は `null`（言語ごとの設定に従う）として扱う。 */
fun ItemSttOverrideEntity.toExternalModel(): ItemSttOverride = ItemSttOverride(
    targetEngine = SttEngine.fromNameOrNull(targetEngine),
    baseEngine = SttEngine.fromNameOrNull(baseEngine),
)

/** ドメインモデルを、教材 [mediaId] の DB の行に変換する。 */
fun ItemSttOverride.toEntity(mediaId: MediaId): ItemSttOverrideEntity = ItemSttOverrideEntity(
    mediaId = mediaId.value,
    targetEngine = targetEngine?.name,
    baseEngine = baseEngine?.name,
)
