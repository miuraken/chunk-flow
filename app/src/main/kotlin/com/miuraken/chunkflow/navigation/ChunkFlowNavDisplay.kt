package com.miuraken.chunkflow.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.feature.library.LibraryRoute
import com.miuraken.chunkflow.feature.player.PlayerRoute
import com.miuraken.chunkflow.feature.settings.SettingsRoute
import com.miuraken.chunkflow.feature.settings.SettingsTab

/**
 * アプリの画面遷移（Navigation 3）。ライブラリ → プレイヤー / 設定。
 *
 * 予測型「戻る」は NavDisplay が扱う（§7.5.6）。大画面の 2 ペイン表示（material3-adaptive）は M4 で加える。
 */
@Composable
fun ChunkFlowNavDisplay(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(LibraryKey)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<LibraryKey> {
                LibraryRoute(
                    onOpenPlayer = { id -> backStack.add(PlayerKey(id.value)) },
                    onOpenPlayerAtChunk = { id, chunkId ->
                        backStack.add(PlayerKey(mediaId = id.value, initialChunkId = chunkId.value))
                    },
                    onOpenSettings = { backStack.add(SettingsKey()) },
                    onOpenLanguageSettings = {
                        backStack.add(SettingsKey(initialTab = SettingsTab.LANGUAGE.ordinal))
                    },
                )
            }
            entry<PlayerKey> { key ->
                PlayerRoute(
                    mediaId = MediaId(key.mediaId),
                    initialChunkId = key.initialChunkId?.let { ChunkId(it) },
                    onBack = { backStack.removeLastOrNull() },
                    onOpenPlaybackSettings = { backStack.add(SettingsKey()) },
                    onOpenLanguageSettings = {
                        backStack.add(SettingsKey(initialTab = SettingsTab.LANGUAGE.ordinal))
                    },
                )
            }
            entry<SettingsKey> { key ->
                SettingsRoute(
                    onBack = { backStack.removeLastOrNull() },
                    initialTab = key.initialTab,
                )
            }
        },
    )
}
