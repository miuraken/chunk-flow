package com.miuraken.chunkflow.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/** 設定画面の状態（Phase 0 の雛形。項目は ui が加える）。 */
data object SettingsUiState

/** 設定画面のイベント（雛形）。 */
sealed interface SettingsEvent {
    /** 前の画面に戻る。 */
    data object Back : SettingsEvent
}

/**
 * 設定画面の Route（Phase 0 の雛形。ViewModel は ui が加える）。
 *
 * @param onBack 前の画面に戻る。
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(
        state = SettingsUiState,
        onEvent = { event ->
            when (event) {
                SettingsEvent.Back -> onBack()
            }
        },
        modifier = modifier,
    )
}

/** 設定画面（状態を受け取って描くだけ）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = { onEvent(SettingsEvent.Back) }) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.settings_placeholder), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun SettingsScreenPreview() {
    ChunkFlowTheme {
        SettingsScreen(state = SettingsUiState, onEvent = {})
    }
}
