package com.miuraken.chunkflow.spike.textai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * スパイク S4 の画面（雛形）。
 *
 * 本体で書き出した文字起こしファイルを読み込み、チャンクごとに ML Kit の翻訳・校正を試して、
 * 結果とかかった時間を並べる画面を、担当（transcriber）がここに作る。
 * 教材の本文はリポジトリにも端末の外にも出さない（AGENTS.md §10）。
 */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.spike_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(text = stringResource(R.string.spike_placeholder))
                    }
                }
            }
        }
    }
}
