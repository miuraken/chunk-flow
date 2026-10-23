package com.miuraken.chunkflow.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.miuraken.chunkflow.R
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.feature.library.LibraryRoute
import com.miuraken.chunkflow.feature.library.TranscriptExportRoute
import com.miuraken.chunkflow.feature.player.PlayerRoute
import com.miuraken.chunkflow.feature.settings.SettingsRoute
import com.miuraken.chunkflow.feature.settings.SettingsTab

/**
 * アプリの画面遷移（Navigation 3）。ライブラリ → プレイヤー / 設定 / 文字起こしエクスポート。
 *
 * 予測型「戻る」は NavDisplay が扱い、大画面（Expanded 以上）では [ListDetailSceneStrategy] により
 * 「ライブラリ | プレイヤー」の 2 ペイン表示になる（§7.5.1、§7.5.6）。
 *
 * 「戻る」が再構成の前に 2 回届いてもバックスタックが空にならないよう（空になると NavDisplay が
 * 例外で落ちる）、システムの「戻る」は [popUnlessRoot]（ルートの [LibraryKey] は消さない）、
 * 画面の「戻る」ボタンは [popIfTop]（自分のエントリーが一番上のときだけ消す）で取り除く。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChunkFlowNavDisplay(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(LibraryKey)
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.popUnlessRoot() },
        sceneStrategies = listOf(listDetailStrategy),
        entryProvider = entryProvider {
            entry<LibraryKey>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = {
                        PlayerDetailPlaceholder()
                    },
                ),
            ) {
                LibraryRoute(
                    onOpenPlayer = { id ->
                        backStack.openPlayer(PlayerKey(mediaId = id.value))
                    },
                    onOpenPlayerAtChunk = { id, chunkId ->
                        backStack.openPlayer(
                            PlayerKey(mediaId = id.value, initialChunkId = chunkId.value),
                        )
                    },
                    onOpenSettings = { backStack.add(SettingsKey()) },
                    onOpenLanguageSettings = {
                        backStack.add(SettingsKey(initialTab = SettingsTab.LANGUAGE.ordinal))
                    },
                    onOpenTranscriptExport = { ids ->
                        if (ids.isNotEmpty()) {
                            backStack.add(TranscriptExportKey(mediaIds = ids.map { it.value }))
                        }
                    },
                )
            }
            entry<PlayerKey>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) { key ->
                PlayerRoute(
                    mediaId = MediaId(key.mediaId),
                    initialChunkId = key.initialChunkId?.let { ChunkId(it) },
                    onBack = { backStack.popIfTop(key) },
                    onOpenPlaybackSettings = { backStack.add(SettingsKey()) },
                    onOpenLanguageSettings = {
                        backStack.add(SettingsKey(initialTab = SettingsTab.LANGUAGE.ordinal))
                    },
                    onOpenTranscriptExport = { id ->
                        backStack.add(TranscriptExportKey(mediaIds = listOf(id.value)))
                    },
                )
            }
            entry<TranscriptExportKey> { key ->
                TranscriptExportRoute(
                    mediaIds = key.mediaIds.map { MediaId(it) },
                    onBack = { backStack.popIfTop(key) },
                )
            }
            entry<SettingsKey> { key ->
                SettingsRoute(
                    onBack = { backStack.popIfTop(key) },
                    initialTab = key.initialTab,
                )
            }
        },
    )
}

@Composable
private fun PlayerDetailPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.nav_detail_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
