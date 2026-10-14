package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.StageStatus

/**
 * 端末内の解析（特徴量・分割）の段階ごとの状態（§6.4 `analysis_state` テーブル）。
 */
@Entity(
    tableName = "analysis_state",
    primaryKeys = ["media_id", "stage"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnalysisStateEntity(
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "stage")
    val stage: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "progress")
    val progress: Float? = null,
    @ColumnInfo(name = "error")
    val error: String? = null,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: Int = 1,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
) {
    companion object {
        const val STATUS_NOT_STARTED = "NOT_STARTED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}

/** [AnalysisStateEntity] から [StageStatus] を復元する。 */
fun AnalysisStateEntity.toStageStatus(): StageStatus = when (status) {
    AnalysisStateEntity.STATUS_RUNNING -> StageStatus.Running(progress)

    AnalysisStateEntity.STATUS_DONE -> StageStatus.Done

    AnalysisStateEntity.STATUS_FAILED -> {
        val analysisError = error?.let { name -> AnalysisError.entries.firstOrNull { it.name == name } }
            ?: AnalysisError.IO
        StageStatus.Failed(analysisError)
    }

    else -> StageStatus.NotStarted
}

/** [AnalysisStateEntity] から（[AnalysisStage], [StageStatus]）の組を復元する。未知の stage 名は `null` を返す。 */
fun AnalysisStateEntity.toStageAndStatus(): Pair<AnalysisStage, StageStatus>? {
    val parsedStage = AnalysisStage.entries.firstOrNull { it.name == stage } ?: return null
    return parsedStage to toStageStatus()
}

/** ドメインモデルの [StageStatus] を [AnalysisStateEntity] に変換する。 */
fun StageStatus.toEntity(
    mediaId: MediaId,
    stage: AnalysisStage,
    algorithmVersion: Int = 1,
    updatedAtEpochMs: Long = 0L,
): AnalysisStateEntity = when (this) {
    StageStatus.NotStarted -> AnalysisStateEntity(
        mediaId = mediaId.value,
        stage = stage.name,
        status = AnalysisStateEntity.STATUS_NOT_STARTED,
        algorithmVersion = algorithmVersion,
        updatedAt = updatedAtEpochMs,
    )

    is StageStatus.Running -> AnalysisStateEntity(
        mediaId = mediaId.value,
        stage = stage.name,
        status = AnalysisStateEntity.STATUS_RUNNING,
        progress = progress,
        algorithmVersion = algorithmVersion,
        updatedAt = updatedAtEpochMs,
    )

    StageStatus.Done -> AnalysisStateEntity(
        mediaId = mediaId.value,
        stage = stage.name,
        status = AnalysisStateEntity.STATUS_DONE,
        algorithmVersion = algorithmVersion,
        updatedAt = updatedAtEpochMs,
    )

    is StageStatus.Failed -> AnalysisStateEntity(
        mediaId = mediaId.value,
        stage = stage.name,
        status = AnalysisStateEntity.STATUS_FAILED,
        error = error.name,
        algorithmVersion = algorithmVersion,
        updatedAt = updatedAtEpochMs,
    )
}
