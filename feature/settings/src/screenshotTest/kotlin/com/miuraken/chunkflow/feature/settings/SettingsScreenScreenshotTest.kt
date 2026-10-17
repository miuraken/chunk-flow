package com.miuraken.chunkflow.feature.settings

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.UserSettings

// Compose Preview Screenshot Testing（ADR 0002、§10.5）。

@PreviewTest
@ChunkFlowPreviews
@Composable
fun SettingsScreenDefaultScreenshot() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(
                settings = UserSettings(
                    defaultLanguagePair = LanguagePair(
                        target = LanguageTag("ko"),
                        base = LanguageTag("ja"),
                    ),
                    chineseScript = ChineseScript.HANS,
                    cantoneseScript = ChineseScript.HANT,
                    defaultSpeed = 1.0f,
                    seekBackMs = 3_000L,
                    seekForwardMs = 5_000L,
                ),
            ),
            onEvent = {},
        )
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun SettingsScreenTranscriptionTabPreview() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(
                languageModels = listOf(
                    com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                        language = LanguageTag("ja"),
                        status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                        mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                        mlKitMode = com.miuraken.chunkflow.core.model.MlKitRecognitionMode.ADVANCED,
                        sherpaModelSizeBytes = 50 * 1024 * 1024,
                        canDeleteSherpaModel = true,
                    ),
                    com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                        language = LanguageTag("en"),
                        status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                    )
                ),
            ),
            onEvent = {},
            initialTab = 2, // TRANSCRIPTION
        )
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun SettingsScreenLanguageTabPreview() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(
                settings = UserSettings(
                    enabledLanguages = setOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko"), LanguageTag("zh"))
                )
            ),
            onEvent = {},
            initialTab = 3, // LANGUAGE
        )
    }
}
