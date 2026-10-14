package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * プレイヤー画面の Route。ViewModel から状態と [PositionProvider] を受け取り、[PlayerScreen] に渡す。
 */
@Composable
fun PlayerRoute(
    mediaId: MediaId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaId) {
        viewModel.open(mediaId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PlayerScreen(
        state = state,
        positionProvider = viewModel.positionProvider,
        onEvent = { event ->
            when (event) {
                PlayerEvent.Back -> onBack()
                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * プレイヤー画面（状態を受け取って描くだけの Composable。§6.2、§7.5.2）。
 *
 * 再生位置は [PlayerUiState] に含めず、[positionProvider] を子 Composable（[PlayerProgressSection]）にのみ渡す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title.ifEmpty { stringResource(R.string.player_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onEvent(PlayerEvent.Back) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.player_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayerHeaderCard(
                title = state.title.ifEmpty { stringResource(R.string.player_title) },
                course = state.course,
                languagePair = state.languagePair,
            )

            state.error?.let { error ->
                PlayerErrorBanner(error = error)
            }

            PlayerProgressSection(
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                positionProvider = positionProvider,
                onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
            )

            PlayerTransportControls(
                isPlaying = state.isPlaying,
                seekBackMs = state.seekBackMs,
                seekForwardMs = state.seekForwardMs,
                onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                onSeekForward = { onEvent(PlayerEvent.SeekForward) },
            )

            PlayerSpeedSection(
                speed = state.speed,
                onSetSpeed = { speed -> onEvent(PlayerEvent.SetSpeed(speed)) },
                onAdjustSpeed = { delta -> onEvent(PlayerEvent.AdjustSpeed(delta)) },
            )
        }
    }
}

@Composable
private fun PlayerHeaderCard(
    title: String,
    course: String?,
    languagePair: LanguagePair?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            if (languagePair != null || course != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    languagePair?.let { pair ->
                        val targetText = pair.target.value.uppercase(Locale.ROOT)
                        val badgeText = pair.base?.value?.uppercase(Locale.ROOT)?.let { baseText ->
                            "$baseText → $targetText"
                        } ?: targetText
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    course?.let { courseName ->
                        Text(
                            text = courseName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerErrorBanner(
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
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * 再生位置とシークバーを表示する子 Composable（§6.2）。
 *
 * [PositionProvider] のサンプリングをこの Composable の内部に閉じ込めることで、
 * 再生位置の更新によって [PlayerScreen] 全体が再コンポーズされるのを防ぐ。
 */
@Composable
private fun PlayerProgressSection(
    durationMs: Long,
    isPlaying: Boolean,
    positionProvider: PositionProvider,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    var sampledPositionMs by remember(positionProvider) {
        mutableLongStateOf(positionProvider.currentPositionMs())
    }
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }

    if (!isInspectionMode) {
        LaunchedEffect(positionProvider, isPlaying) {
            sampledPositionMs = positionProvider.currentPositionMs()
            while (isActive) {
                sampledPositionMs = positionProvider.currentPositionMs()
                delay(if (isPlaying) 50L else 200L)
            }
        }
    }

    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val displayPositionMs = if (isDragging) {
        dragPositionMs
    } else if (safeDurationMs > 0L) {
        sampledPositionMs.coerceIn(0L, safeDurationMs)
    } else {
        sampledPositionMs.coerceAtLeast(0L)
    }
    val sliderFraction = if (safeDurationMs > 0L) {
        (displayPositionMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderDescription = stringResource(R.string.player_seek_slider_cd)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = sliderFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragPositionMs = (fraction * safeDurationMs).roundToLong()
            },
            onValueChangeFinished = {
                if (isDragging) {
                    isDragging = false
                    sampledPositionMs = dragPositionMs
                    onSeekTo(dragPositionMs)
                }
            },
            enabled = safeDurationMs > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = sliderDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(displayPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDurationMs(safeDurationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    seekBackMs: Long,
    seekForwardMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekBackSeconds = (seekBackMs / 1_000L).coerceAtLeast(1L)
    val seekForwardSeconds = (seekForwardMs / 1_000L).coerceAtLeast(1L)
    val seekBackCd = stringResource(R.string.player_seek_back_cd, seekBackSeconds)
    val seekForwardCd = stringResource(R.string.player_seek_forward_cd, seekForwardSeconds)
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.player_pause)
    } else {
        stringResource(R.string.player_play)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onSeekBack,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekBackCd },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = ChunkFlowIcons.SeekBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.player_seek_back_label, seekBackSeconds))
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                contentDescription = playPauseCd,
                modifier = Modifier.size(32.dp),
            )
        }

        FilledTonalButton(
            onClick = onSeekForward,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekForwardCd },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text = stringResource(R.string.player_seek_forward_label, seekForwardSeconds))
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = ChunkFlowIcons.SeekForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSpeedSection(
    speed: Float,
    onSetSpeed: (Float) -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val decreaseCd = stringResource(R.string.player_speed_decrease_cd)
    val increaseCd = stringResource(R.string.player_speed_increase_cd)
    val sliderCd = stringResource(R.string.player_speed_slider_cd)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_speed_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = formatSpeed(speed),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onAdjustSpeed(-PlayerUiState.SPEED_STEP) },
                    enabled = speed > ItemState.MIN_SPEED + 0.001f,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = decreaseCd },
                ) {
                    Text(text = "-0.05x")
                }
                OutlinedButton(
                    onClick = { onAdjustSpeed(PlayerUiState.SPEED_STEP) },
                    enabled = speed < ItemState.MAX_SPEED - 0.001f,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = increaseCd },
                ) {
                    Text(text = "+0.05x")
                }
            }

            Slider(
                value = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
                onValueChange = onSetSpeed,
                valueRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
                steps = 29,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = sliderCd },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlayerUiState.SPEED_PRESETS.forEach { preset ->
                    val isSelected = abs(speed - preset) < 0.01f
                    val labelText = String.format(
                        Locale.ROOT,
                        if (preset * 100f % 10f == 0f) "%.1fx" else "%.2fx",
                        preset,
                    )
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSetSpeed(preset) },
                        label = { Text(text = labelText) },
                    )
                }
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenPreview() {
    val item = SampleMaterials.jaKo.item
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = true,
                durationMs = item.durationMs,
                speed = 1.25f,
            ),
            positionProvider = FakePositionProvider(12_000L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenErrorPreview() {
    val item = SampleMaterials.enYue.item
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = false,
                durationMs = item.durationMs,
                speed = 1.0f,
                error = PlaybackError.SOURCE_MISSING,
            ),
            positionProvider = FakePositionProvider(0L),
            onEvent = {},
        )
    }
}
