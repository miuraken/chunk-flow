package com.miuraken.chunkflow.feature.player

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials

/**
 * プレイヤー画面の状態（Phase 0 の雛形）。設計書 §6.2 の `PlayerUiState` は ui が作る。
 *
 * @property mediaId 開いている教材。
 * @property title 教材のタイトル。
 */
data class PlayerPlaceholderState(val mediaId: MediaId, val title: String = "")

/** プレイヤー画面のイベント（雛形）。 */
sealed interface PlayerEvent {
    /** 前の画面に戻る。 */
    data object Back : PlayerEvent
}

/**
 * プレイヤー画面の Route（Phase 0 の雛形。ViewModel は ui が加える）。
 *
 * @param mediaId 開く教材。
 * @param onBack 前の画面に戻る。
 */
@Composable
fun PlayerRoute(
    mediaId: MediaId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(mediaId) { PlayerPlaceholderState(mediaId) }
    PlayerScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                PlayerEvent.Back -> onBack()
            }
        },
        modifier = modifier,
    )
}

/** プレイヤー画面（状態を受け取って描くだけ）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerPlaceholderState,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifEmpty { stringResource(R.string.player_title) }) },
                navigationIcon = {
                    TextButton(onClick = { onEvent(PlayerEvent.Back) }) {
                        Text(stringResource(R.string.player_back))
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
            Text(text = stringResource(R.string.player_placeholder), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenPreview() {
    val item = SampleMaterials.jaKo.item
    ChunkFlowTheme {
        PlayerScreen(state = PlayerPlaceholderState(item.id, item.title), onEvent = {})
    }
}
