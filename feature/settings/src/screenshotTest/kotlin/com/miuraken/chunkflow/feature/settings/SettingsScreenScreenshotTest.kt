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
