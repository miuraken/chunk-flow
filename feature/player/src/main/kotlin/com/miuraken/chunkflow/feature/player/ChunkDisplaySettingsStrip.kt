package com.miuraken.chunkflow.feature.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageBadgeCompactPadding
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageBadgeRole
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageRoleBadge
import com.miuraken.chunkflow.core.designsystem.component.playback.brightnessToggleChipColors
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguageFilter
import java.util.Locale
import kotlin.math.min

/**
 * チャンクの表示に関する設定の行（チャンクリストの上に置く）。
 *
 * 表示項目:
 * - 「言語」（`[base][target]`、`[base]`、`[target]`）
 * - 「{target}を隠す」（on, *off）
 * - 「読み」（on, *off）
 * - 右端: 「🔖」（ブックマークしたチャンクのみ表示）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChunkDisplaySettingsStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var langFilterMenuExpanded by remember { mutableStateOf(false) }

    val targetLangCode = state.languagePair?.target?.value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)
    val baseLangCode = state.languagePair?.base?.value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    val languageFilter = state.practice.languageFilter
    val isHideTargetLangOn = state.blindMode != BlindMode.OFF
    val isReadingOn = state.showTargetRomanization
    val isBookmarksOnlyOn = state.practice.bookmarksOnly
    val toggleColors = brightnessToggleChipColors()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. 「言語」（[base][target]、[base]、[target]、☑ のみ）
            val transcriptChipModifier = Modifier.alpha(if (state.hasTranscript) 1f else 0.28f)
            val hasLangOrTranscript = state.hasTranscript || state.hasChunkLanguage
            val hasCheckedChunks = state.practice.repeatChunkIds.isNotEmpty()
            val isLanguageFilterEnabled = hasLangOrTranscript ||
                hasCheckedChunks ||
                languageFilter == LanguageFilter.CHECKED_ONLY ||
                state.isLanguageFilterFallbackHighlighted
            val isLangFilterSelected = languageFilter != LanguageFilter.ALL
            val isInspection = LocalInspectionMode.current
            val highlightPulseAlpha = if (state.isLanguageFilterFallbackHighlighted && !isInspection) {
                val infiniteTransition = rememberInfiniteTransition(label = "langFilterFallbackHighlight")
                val animatedAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "langFilterFallbackAlpha",
                )
                animatedAlpha
            } else {
                1f
            }
            val langFilterContainerColor = when {
                state.isLanguageFilterFallbackHighlighted ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f * highlightPulseAlpha)

                isLangFilterSelected ->
                    MaterialTheme.colorScheme.secondaryContainer

                else ->
                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
            }
            val langFilterBorder = when {
                state.isLanguageFilterFallbackHighlighted ->
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = highlightPulseAlpha))

                isLangFilterSelected ->
                    null

                else ->
                    FilterChipDefaults.filterChipBorder(enabled = isLanguageFilterEnabled, selected = false)
            }
            val baseDisplayCode = baseLangCode ?: stringResource(R.string.player_settings_base_lang_label)
            val targetDisplayCode = targetLangCode ?: stringResource(R.string.player_settings_target_lang_label)
            Column {
                Surface(
                    shape = FilterChipDefaults.shape,
                    color = langFilterContainerColor,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = langFilterBorder,
                    modifier = Modifier
                        .alpha(if (isLanguageFilterEnabled) 1f else 0.28f)
                        .heightIn(min = FilterChipDefaults.Height)
                        .clip(FilterChipDefaults.shape)
                        .combinedClickable(
                            enabled = isLanguageFilterEnabled,
                            onClick = { langFilterMenuExpanded = true },
                            onLongClick = { langFilterMenuExpanded = true },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (languageFilter) {
                            LanguageFilter.ALL -> {
                                if (baseLangCode != null) {
                                    LanguageRoleBadge(
                                        code = baseLangCode,
                                        role = LanguageBadgeRole.BASE,
                                        contentPadding = LanguageBadgeCompactPadding,
                                    )
                                }
                                if (targetLangCode != null) {
                                    LanguageRoleBadge(
                                        code = targetLangCode,
                                        role = LanguageBadgeRole.TARGET,
                                        contentPadding = LanguageBadgeCompactPadding,
                                    )
                                }
                                if (baseLangCode == null && targetLangCode == null) {
                                    Text(
                                        text = stringResource(R.string.player_chip_lang_filter_all),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                    )
                                }
                            }

                            LanguageFilter.BASE_ONLY -> {
                                LanguageRoleBadge(
                                    code = baseDisplayCode,
                                    role = LanguageBadgeRole.BASE,
                                    contentPadding = LanguageBadgeCompactPadding,
                                )
                            }

                            LanguageFilter.TARGET_ONLY -> {
                                LanguageRoleBadge(
                                    code = targetDisplayCode,
                                    role = LanguageBadgeRole.TARGET,
                                    contentPadding = LanguageBadgeCompactPadding,
                                )
                            }

                            LanguageFilter.CHECKED_ONLY -> {
                                Text(
                                    text = stringResource(R.string.player_menu_lang_filter_checked_only),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                DropdownMenu(
                    expanded = langFilterMenuExpanded,
                    onDismissRequest = { langFilterMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (baseLangCode != null) {
                                    LanguageRoleBadge(
                                        code = baseLangCode,
                                        role = LanguageBadgeRole.BASE,
                                        contentPadding = LanguageBadgeCompactPadding,
                                    )
                                }
                                LanguageRoleBadge(
                                    code = targetDisplayCode,
                                    role = LanguageBadgeRole.TARGET,
                                    contentPadding = LanguageBadgeCompactPadding,
                                )
                                Text(
                                    text = stringResource(R.string.player_menu_lang_filter_both_play),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                        },
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.ALL))
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LanguageRoleBadge(
                                    code = baseDisplayCode,
                                    role = LanguageBadgeRole.BASE,
                                    contentPadding = LanguageBadgeCompactPadding,
                                )
                                Text(
                                    text = stringResource(R.string.player_menu_lang_filter_only_play),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                        },
                        enabled = hasLangOrTranscript,
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.BASE_ONLY))
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LanguageRoleBadge(
                                    code = targetDisplayCode,
                                    role = LanguageBadgeRole.TARGET,
                                    contentPadding = LanguageBadgeCompactPadding,
                                )
                                Text(
                                    text = stringResource(R.string.player_menu_lang_filter_only_play),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            }
                        },
                        enabled = hasLangOrTranscript,
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.TARGET_ONLY))
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.player_menu_lang_filter_checked_only),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        },
                        enabled = hasCheckedChunks,
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.CHECKED_ONLY))
                        },
                    )
                }
            }

            // 2. 「隠す」（on, *off）
            val blindChipLabel = stringResource(R.string.player_chip_blind_target)
            FilterChip(
                selected = isHideTargetLangOn,
                enabled = state.hasTranscript,
                onClick = {
                    val nextBlindMode = if (isHideTargetLangOn) {
                        BlindMode.OFF
                    } else {
                        BlindMode.HIDE_TARGET_ONLY
                    }
                    onEvent(PlayerEvent.SetBlindMode(nextBlindMode))
                },
                label = {
                    Text(
                        text = blindChipLabel,
                        fontWeight = if (isHideTargetLangOn) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                },
                colors = toggleColors,
                modifier = transcriptChipModifier,
            )

            // 3. 「読み」（on, *off）
            FilterChip(
                selected = isReadingOn,
                enabled = state.hasTranscript,
                onClick = {
                    onEvent(PlayerEvent.SetShowTargetRomanization(!isReadingOn))
                },
                label = {
                    Text(
                        text = stringResource(R.string.player_chip_reading),
                        fontWeight = if (isReadingOn) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                },
                colors = toggleColors,
                modifier = transcriptChipModifier,
            )

            // 4. 「翻訳」（on, *off）
            if (state.isTranslationSupported) {
                FilterChip(
                    selected = state.showTargetTranslation,
                    enabled = state.hasTranscript,
                    onClick = {
                        onEvent(PlayerEvent.SetShowTargetTranslation(!state.showTargetTranslation))
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.player_chip_translate),
                            fontWeight = if (state.showTargetTranslation) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    },
                    colors = toggleColors,
                    modifier = transcriptChipModifier,
                )
            }

            // 5. 「🔖」（ブックマークしたチャンクのみ表示。1つでもブックマークがあるときのみ表示）
            if (state.chunks.any { it.isBookmarked }) {
                FilterChip(
                    selected = isBookmarksOnlyOn,
                    onClick = { onEvent(PlayerEvent.ToggleBookmarksOnly) },
                    label = {
                        Icon(
                            imageVector = ChunkFlowIcons.Bookmark,
                            contentDescription = stringResource(R.string.player_chip_bookmarks_only_cd),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    colors = toggleColors,
                )
            }
        }
    }
}
