package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider

/**
 * ライブラリ画面の状態（Phase 0 の雛形。ui が作り込む）。
 *
 * @property items 教材の一覧。
 */
data class LibraryUiState(val items: List<AudioItem> = emptyList())

/** ライブラリ画面のイベント。 */
sealed interface LibraryEvent {
    /** 教材を開く。 */
    data class OpenItem(val id: MediaId) : LibraryEvent

    /** 設定画面を開く。 */
    data object OpenSettings : LibraryEvent
}

/**
 * ライブラリ画面の Route。画面遷移のコールバックを受け取り、[LibraryScreen] に状態を渡す。
 *
 * Phase 0 の雛形のため ViewModel はまだない（ui が `LibraryViewModel` を加える）。
 * 教材がない間も遷移を確かめられるよう、仮の教材（[PLACEHOLDER_MEDIA_ID]）を開くボタンを出す。
 */
@Composable
fun LibraryRoute(
    onOpenPlayer: (MediaId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { LibraryUiState() }
    LibraryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is LibraryEvent.OpenItem -> onOpenPlayer(event.id)
                LibraryEvent.OpenSettings -> onOpenSettings()
            }
        },
        modifier = modifier,
    )
}

/** 雛形の画面遷移で使う仮の教材の ID。 */
val PLACEHOLDER_MEDIA_ID: MediaId = MediaId("00000000-0000-4000-8000-000000000000")

/** ライブラリ画面（状態を受け取って描くだけ）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    TextButton(onClick = { onEvent(LibraryEvent.OpenSettings) }) {
                        Text(stringResource(R.string.library_open_settings))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { onEvent(LibraryEvent.OpenItem(PLACEHOLDER_MEDIA_ID)) }) {
                    Text(stringResource(R.string.library_open_placeholder))
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                items(state.items, key = { it.id.value }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        modifier = Modifier.clickable { onEvent(LibraryEvent.OpenItem(item.id)) },
                        supportingContent = item.course?.let { course -> { Text(course) } },
                    )
                }
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenPreview(
    @PreviewParameter(SampleLibraryPreviewParameterProvider::class) items: List<AudioItem>,
) {
    ChunkFlowTheme {
        LibraryScreen(state = LibraryUiState(items), onEvent = {})
    }
}
