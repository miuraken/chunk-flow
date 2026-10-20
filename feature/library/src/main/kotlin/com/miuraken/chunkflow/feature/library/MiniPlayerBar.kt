package com.miuraken.chunkflow.feature.library

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs

@Composable
internal fun MiniPlayerBar(
    miniPlayer: MiniPlayerUiState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openPlayerDescription = stringResource(R.string.library_mini_player_open, miniPlayer.title)
    val playPauseDescription = if (miniPlayer.isPlaying) {
        stringResource(R.string.library_mini_player_pause)
    } else {
        stringResource(R.string.library_mini_player_play)
    }
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
