package com.miuraken.chunkflow

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.navigation.ChunkFlowNavDisplay
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 唯一の Activity。edge-to-edge で表示し、画面遷移は Navigation 3（[ChunkFlowNavDisplay]）で行う（§7.5.6）。
 *
 * 他アプリからの共有・「アプリで開く」（`ACTION_VIEW` / `ACTION_SEND` / `ACTION_SEND_MULTIPLE`）で渡された
 * `content://` URI は [LibraryRepository.import] に渡して取り込む（F1.2、§7.1、§8.1）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var libraryRepository: LibraryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
        addOnNewIntentListener { newIntent ->
            setIntent(newIntent)
            handleIncomingIntent(newIntent)
        }
        setContent {
            ChunkFlowTheme {
                ChunkFlowNavDisplay()
            }
        }
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        val uris = extractSharedContentUris(incomingIntent)
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                libraryRepository.import(uris).collect()
            }
        }
    }
}

/**
 * 共有・「開く」インテントから `content://` スキームの URI 一覧を抽出する（§8.1）。
 */
internal fun extractSharedContentUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    val action = intent.action
    if (
        action != Intent.ACTION_VIEW &&
        action != Intent.ACTION_SEND &&
        action != Intent.ACTION_SEND_MULTIPLE
    ) {
        return emptyList()
    }
    val collected = linkedSetOf<Uri>()
    intent.data?.let(collected::add)
    if (action == Intent.ACTION_SEND) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let(collected::add)
    }
    if (action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.filterNotNull()
            ?.forEach(collected::add)
    }
    val clipData = intent.clipData
    if (clipData != null) {
        for (i in 0 until clipData.itemCount) {
            clipData.getItemAt(i)?.uri?.let(collected::add)
        }
    }
    return collected.filter { uri -> uri.scheme == ContentResolver.SCHEME_CONTENT }
}
