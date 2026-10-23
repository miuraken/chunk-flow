package com.miuraken.chunkflow.analytics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.miuraken.chunkflow.core.model.UsageAnalyticsTracker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Google Analytics for Firebase を用いた [UsageAnalyticsTracker] 実装。
 *
 * - 広告 ID（`AD_ID`）や SSAID はマニフェスト設定で無効化している。
 * - ファイル名・URI・文字起こし本文・翻訳本文などのユーザーコンテンツは一切送信しない（設計書 §8）。
 * - CI など `google-services.json` が存在しない環境でも安全に動作するよう、リソース不在時は No-Op にフォールバックする。
 */
@Singleton
internal class FirebaseUsageAnalyticsTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageAnalyticsTracker {

    private val firebaseAnalytics: FirebaseAnalytics? by lazy {
        createFirebaseAnalytics()
    }

    @SuppressLint("MissingPermission")
    private fun createFirebaseAnalytics(): FirebaseAnalytics? = runCatching {
        val appIdRes = context.resources.getIdentifier(
            "google_app_id",
            "string",
            context.packageName,
        )
        if (appIdRes != 0) {
            FirebaseAnalytics.getInstance(context)
        } else {
            null
        }
    }.getOrNull()

    override fun logScreenView(screenName: String) {
        logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logMediaImport(
        sourceType: String,
        itemCount: Int,
    ) {
        logEvent("media_import") {
            putString("source_type", sourceType.take(MAX_PARAM_LENGTH))
            putLong("item_count", itemCount.toLong())
        }
    }

    override fun logPlaybackStart(
        targetLanguage: String,
        baseLanguage: String,
    ) {
        logEvent("playback_start") {
            putString("target_language", targetLanguage.take(MAX_PARAM_LENGTH))
            putString("base_language", baseLanguage.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logPlaybackSpeedChange(
        speed: Float,
        isBaseLanguageSpeed: Boolean,
    ) {
        logEvent("playback_speed_change") {
            putLong("speed_percent", (speed * 100f).roundToInt().toLong())
            putString("is_base_language_speed", isBaseLanguageSpeed.toString())
        }
    }

    override fun logRepeatCountChange(repeatCount: Int) {
        logEvent("playback_repeat_change") {
            putLong("repeat_count", repeatCount.toLong())
        }
    }

    override fun logAbRepeatSet(chunkSpan: Int) {
        logEvent("playback_ab_repeat_set") {
            putLong("chunk_span", chunkSpan.toLong())
        }
    }

    override fun logAutoPauseChange(
        mode: String,
        scope: String,
    ) {
        logEvent("playback_auto_pause_change") {
            putString("mode", mode.take(MAX_PARAM_LENGTH))
            putString("scope", scope.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logLanguageFilterChange(filter: String) {
        logEvent("playback_language_filter") {
            putString("filter", filter.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logFileEndActionChange(action: String) {
        logEvent("playback_file_end_action") {
            putString("action", action.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logBlindModeToggle(enabled: Boolean) {
        logEvent("playback_blind_mode_toggle") {
            putString("enabled", enabled.toString())
        }
    }

    override fun logFullTranscriptionStart(
        targetLanguage: String,
        baseLanguage: String,
        targetEngine: String,
        baseEngine: String,
    ) {
        logEvent("transcription_full_start") {
            putString("target_language", targetLanguage.take(MAX_PARAM_LENGTH))
            putString("base_language", baseLanguage.take(MAX_PARAM_LENGTH))
            putString("target_engine", targetEngine.take(MAX_PARAM_LENGTH))
            putString("base_engine", baseEngine.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logChunkDetailTranscription(
        language: String,
        engine: String,
    ) {
        logEvent("transcription_chunk_detail") {
            putString("language", language.take(MAX_PARAM_LENGTH))
            putString("engine", engine.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logLookaheadTranscription(
        language: String,
        engine: String,
    ) {
        logEvent("transcription_lookahead") {
            putString("language", language.take(MAX_PARAM_LENGTH))
            putString("engine", engine.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logTranslationToggle(
        enabled: Boolean,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        logEvent("translation_toggle") {
            putString("enabled", enabled.toString())
            putString("source_language", sourceLanguage.take(MAX_PARAM_LENGTH))
            putString("target_language", targetLanguage.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logTranslationRun(
        sourceLanguage: String,
        targetLanguage: String,
        chunkCount: Int,
    ) {
        logEvent("translation_run") {
            putString("source_language", sourceLanguage.take(MAX_PARAM_LENGTH))
            putString("target_language", targetLanguage.take(MAX_PARAM_LENGTH))
            putLong("chunk_count", chunkCount.toLong())
        }
    }

    override fun logSttModelDownload(
        modelId: String,
        language: String,
    ) {
        logEvent("stt_model_download") {
            putString("model_id", modelId.take(MAX_PARAM_LENGTH))
            putString("language", language.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logChunkBoundaryEdit(editType: String) {
        logEvent("chunk_boundary_edit") {
            putString("edit_type", editType.take(MAX_PARAM_LENGTH))
        }
    }

    override fun logTranscriptExport(format: String) {
        logEvent("transcript_export") {
            putString("format", format.take(MAX_PARAM_LENGTH))
        }
    }

    private inline fun logEvent(
        name: String,
        block: Bundle.() -> Unit,
    ) {
        val analytics = firebaseAnalytics ?: return
        runCatching {
            val bundle = Bundle().apply(block)
            analytics.logEvent(name, bundle)
        }
    }

    private companion object {
        private const val MAX_PARAM_LENGTH = 40
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AnalyticsModule {
    @Binds
    @Singleton
    abstract fun bindUsageAnalyticsTracker(
        impl: FirebaseUsageAnalyticsTracker,
    ): UsageAnalyticsTracker
}
