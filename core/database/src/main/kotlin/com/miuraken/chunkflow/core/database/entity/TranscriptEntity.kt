package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.WordTiming
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * チャンクごとの文字起こし（§6.4 `transcript` テーブル）。
 *
 * `chunk_id` は `chunk.id` への外部キー（`ON DELETE CASCADE`）。
 * チャンクを作り直すときは、新しいチャンクを挿入し、文字起こしを付け替えてから古いチャンクを削除する（§6.4）。
 */
@Entity(
    tableName = "transcript",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranscriptEntity(
    @PrimaryKey
    @ColumnInfo(name = "chunk_id")
    val chunkId: Long,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "text_variants")
    val textVariants: String = "{}",
    @ColumnInfo(name = "language")
    val language: String? = null,
    @ColumnInfo(name = "language_confidence")
    val languageConfidence: Float? = null,
    @ColumnInfo(name = "words")
    val words: String = "[]",
    @ColumnInfo(name = "engine")
    val engine: String,
    @ColumnInfo(name = "user_edited")
    val userEdited: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "translated_text")
    val translatedText: String? = null,
    @ColumnInfo(name = "translated_lang")
    val translatedLang: String? = null,
)

@Serializable
private data class SerializableWordTiming(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float? = null,
)

private val transcriptJson = Json {
    ignoreUnknownKeys = true
}

/** [TranscriptEntity] をドメインモデルの [Transcript] に変換する。 */
fun TranscriptEntity.toExternalModel(): Transcript {
    val parsedVariants = runCatching {
        transcriptJson.decodeFromString<Map<String, String>>(textVariants)
    }.getOrDefault(emptyMap())
    val parsedWords = runCatching {
        transcriptJson.decodeFromString<List<SerializableWordTiming>>(words).map { word ->
            WordTiming(
                startMs = word.startMs,
                endMs = word.endMs,
                text = word.text,
                confidence = word.confidence,
            )
        }
    }.getOrDefault(emptyList())
    return Transcript(
        chunkId = ChunkId(chunkId),
        text = text,
        textVariants = parsedVariants,
        language = language?.takeIf { it.isNotBlank() }?.let(::LanguageTag),
        languageConfidence = languageConfidence,
        words = parsedWords,
        engine = EngineId(engine),
        userEdited = userEdited,
        translatedText = translatedText?.takeIf { it.isNotBlank() },
        translatedLanguage = translatedLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag),
    )
}

/** ドメインモデルの [Transcript] を [TranscriptEntity] に変換する。 */
fun Transcript.toEntity(createdAtEpochMs: Long = 0L): TranscriptEntity {
    val encodedVariants = transcriptJson.encodeToString(textVariants)
    val encodedWords = transcriptJson.encodeToString(
        words.map { word ->
            SerializableWordTiming(
                startMs = word.startMs,
                endMs = word.endMs,
                text = word.text,
                confidence = word.confidence,
            )
        },
    )
    return TranscriptEntity(
        chunkId = chunkId.value,
        text = text,
        textVariants = encodedVariants,
        language = language?.value,
        languageConfidence = languageConfidence,
        words = encodedWords,
        engine = engine.value,
        userEdited = userEdited,
        createdAt = createdAtEpochMs,
        translatedText = translatedText,
        translatedLang = translatedLanguage?.value,
    )
}
