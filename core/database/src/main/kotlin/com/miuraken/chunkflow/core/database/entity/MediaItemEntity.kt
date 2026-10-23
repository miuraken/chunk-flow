package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId

/**
 * 取り込んだ教材のメタデータ（§6.4 `media_item` テーブル、ADR 0008）。
 *
 * `sha256` には一意インデックスを張り、重複取り込みの検出と文字起こしファイルとの対応付けに使う。
 * `display_name` は元のファイル名（SAF の `DISPLAY_NAME`）で、ファイル名順の並びに使う（ADR 0015）。
 */
@Entity(
    tableName = "media_item",
    indices = [
        Index(value = ["sha256"], unique = true),
    ],
)
data class MediaItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "course")
    val course: String?,
    @ColumnInfo(name = "target_lang")
    val targetLang: String,
    @ColumnInfo(name = "base_lang")
    val baseLang: String?,
    @ColumnInfo(name = "transcript_offset_ms")
    val transcriptOffsetMs: Int = 0,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
    @ColumnInfo(name = "mp3_has_header", defaultValue = "0")
    val mp3HasHeader: Boolean = false,
    @ColumnInfo(name = "mp3_samples_per_frame", defaultValue = "1152")
    val mp3SamplesPerFrame: Int = 1152,
    @ColumnInfo(name = "mp3_encoder_delay", defaultValue = "576")
    val mp3EncoderDelay: Int = 576,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String? = null,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String? = null,
    @ColumnInfo(name = "storage_state", defaultValue = "'APP_MANAGED'")
    val storageState: String = AudioStorageState.APP_MANAGED.name,
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
)

/** [MediaItemEntity] をドメインモデルの [AudioItem] に変換する。 */
fun MediaItemEntity.toExternalModel(): AudioItem = AudioItem(
    id = MediaId(id),
    title = title,
    durationMs = durationMs,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    contentSha256 = sha256,
    course = course,
    languagePair = LanguagePair(
        target = LanguageTag(targetLang),
        base = baseLang?.let(::LanguageTag),
    ),
    transcriptOffsetMs = transcriptOffsetMs,
    importedAtEpochMs = importedAt,
    sourceUri = sourceUri,
    localFilePath = localFilePath,
    storageState = AudioStorageState.entries.firstOrNull { it.name == storageState }
        ?: AudioStorageState.APP_MANAGED,
    displayName = displayName,
)

/** ドメインモデルの [AudioItem] を [MediaItemEntity] に変換する。 */
fun AudioItem.toEntity(
    mp3HasHeader: Boolean = false,
    mp3SamplesPerFrame: Int = 1152,
    mp3EncoderDelay: Int = 576,
): MediaItemEntity = MediaItemEntity(
    id = id.value,
    title = title,
    durationMs = durationMs,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = contentSha256,
    course = course,
    targetLang = languagePair.target.value,
    baseLang = languagePair.base?.value,
    transcriptOffsetMs = transcriptOffsetMs,
    importedAt = importedAtEpochMs,
    mp3HasHeader = mp3HasHeader,
    mp3SamplesPerFrame = mp3SamplesPerFrame,
    mp3EncoderDelay = mp3EncoderDelay,
    sourceUri = sourceUri,
    localFilePath = localFilePath,
    storageState = storageState.name,
    displayName = displayName,
)
