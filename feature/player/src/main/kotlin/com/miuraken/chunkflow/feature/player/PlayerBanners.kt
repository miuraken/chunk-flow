package com.miuraken.chunkflow.feature.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.playback.PlaybackError
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AutoPauseCountdownBanner(
    autoPauseEndsAtMs: Long,
    onResumeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    var remainingMs by remember(autoPauseEndsAtMs) {
        val initial = if (isInspectionMode) {
            2_400L
        } else {
            (autoPauseEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
        mutableLongStateOf(initial)
    }
    if (!isInspectionMode) {
        LaunchedEffect(autoPauseEndsAtMs) {
            while (isActive) {
                remainingMs = (autoPauseEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                delay(100L)
            }
        }
    }

    val secondsStr = String.format(Locale.ROOT, "%.1f", remainingMs / 1_000f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.Timer,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.player_autopause_active_banner, secondsStr),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResumeNow) {
                Text(text = stringResource(R.string.player_autopause_resume_now))
            }
        }
    }
}

@Composable
internal fun PlayerErrorBanner(
    error: PlaybackError,
    modifier: Modifier = Modifier,
) {
    val errorText = when (error) {
        PlaybackError.SOURCE_MISSING -> stringResource(R.string.player_error_source_missing)
        PlaybackError.DECODER -> stringResource(R.string.player_error_decoder)
        PlaybackError.UNKNOWN -> stringResource(R.string.player_error_unknown)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
