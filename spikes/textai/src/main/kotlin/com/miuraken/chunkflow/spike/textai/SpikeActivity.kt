package com.miuraken.chunkflow.spike.textai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers

/**
 * スパイク S4 の画面。
 *
 * 本体で書き出した文字起こしファイルを端末内のファイル選択（`ACTION_OPEN_DOCUMENT`。権限は不要）で開き、
 * 学習言語のチャンクごとに ML Kit の翻訳（A・B）と校正（C・D）を試して、結果とかかった時間を並べる。
 * 教材の本文はリポジトリにも端末の外にも出さない（AGENTS.md §10）。CSV には本文を書かない。
 */
class SpikeActivity : ComponentActivity() {

    // Dispatcher はここ（組み立ての場所）で決めて ViewModel に渡す
    private val viewModel: TextAiViewModel by viewModels {
        viewModelFactory { initializer { TextAiViewModel(application, Dispatchers.IO) } }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 画面を消したとき・ほかのアプリに切り替えたときの結果を区別するため、前面かどうかを ViewModel に伝える
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onAppForegroundChanged(true)
                    Lifecycle.Event.ON_STOP -> if (!isChangingConfigurations) viewModel.onAppForegroundChanged(false)
                    else -> Unit
                }
            },
        )
        setContent {
            SpikeTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeRoute(
                        viewModel = viewModel,
                        // SRT や LRC は端末によって MIME の種類が決まらないので、すべてのファイルを選べるようにする（中身はパーサが検証する）
                        onOpenFile = { openDocument.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
    }
}
