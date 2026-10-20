package com.miuraken.chunkflow.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.LoopMode

/**
 * 画面下部（下部ナビバーのすぐ上）に常駐するミニプレイヤー・拡張ブックマーク再生バー（計画書 §3.3、Phase C）。
 *
 * - 通常のタブ（フォルダ・ファイル・プレイリスト）ではコンパクトな [MiniPlayerBar] を表示し、
 *   ブックマークが存在する場合は右端の展開ボタンからチャンク単位の再生コントローラーへ展開できる。
 * - ブックマークタブに切り替えたときは自動的に展開状態になり、折りたたみボタンでコンパクト表示へ戻せる。
 */
@Composable
internal fun ExpandableBottomPlayerBar(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val miniPlayer = state.miniPlayer
    val isBookmarksTab = state.selectedTab == LibraryViewTab.BOOKMARKS
    val hasBookmarks = state.bookmarkedFiles.any { it.bookmarkedChunks.isNotEmpty() }
    val showBar = miniPlayer != null || isBookmarksTab

    var isExpanded by rememberSaveable { mutableStateOf(isBookmarksTab) }
    LaunchedEffect(isBookmarksTab) {
        isExpanded = isBookmarksTab
    }

    AnimatedVisibility(
        visible = showBar,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn() + expandVertically(expandFrom = Alignment.Bottom))
                    .togetherWith(fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom))
            },
            label = "bottomPlayerBarState",
        ) { expanded ->
            if (expanded) {
                LibraryBookmarkPlaybackController(
                    state = state,
                    onEvent = onEvent,
                    onCollapse = if (miniPlayer != null) {
                        { isExpanded = false }
                    } else {
                        null
                    },
                )
            } else if (miniPlayer != null) {
                MiniPlayerBar(
                    miniPlayer = miniPlayer,
                    onOpenPlayer = { onEvent(LibraryEvent.OpenItem(miniPlayer.mediaId)) },
                    onTogglePlayPause = { onEvent(LibraryEvent.ToggleMiniPlayerPlayPause) },
                    onExpand = if (isBookmarksTab || hasBookmarks) {
                        { isExpanded = true }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun MiniPlayerBar(
    miniPlayer: MiniPlayerUiState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    val openPlayerDescription = stringResource(R.string.library_mini_player_open, miniPlayer.title)
    val playPauseDescription = if (miniPlayer.isPlaying) {
        stringResource(R.string.library_mini_player_pause)
    } else {
        stringResource(R.string.library_mini_player_play)
    }
    val expandCd = stringResource(R.string.library_mini_player_expand_cd)
    val durationAndPositionText = if (miniPlayer.positionMs > 0L) {
        stringResource(
            R.string.library_item_duration_progress,
            formatDurationMs(miniPlayer.positionMs),
            formatDurationMs(miniPlayer.durationMs),
        )
    } else {
        formatDurationMs(miniPlayer.durationMs)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .semantics { contentDescription = openPlayerDescription }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioItemThumbnail(
                mediaId = miniPlayer.mediaId,
                localFilePath = miniPlayer.localFilePath,
                sourceUri = miniPlayer.sourceUri,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = miniPlayer.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = durationAndPositionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onExpand != null) {
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.ExpandLess,
                        contentDescription = expandCd,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (miniPlayer.isPlaying) {
                        ChunkFlowIcons.Pause
                    } else {
                        ChunkFlowIcons.Play
                    },
                    contentDescription = playPauseDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
