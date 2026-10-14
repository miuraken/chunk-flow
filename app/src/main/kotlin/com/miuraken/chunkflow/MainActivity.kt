package com.miuraken.chunkflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.navigation.ChunkFlowNavDisplay
import dagger.hilt.android.AndroidEntryPoint

/** 唯一の Activity。edge-to-edge で表示し、画面遷移は Navigation 3（[ChunkFlowNavDisplay]）で行う（§7.5.6）。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ChunkFlowTheme {
                ChunkFlowNavDisplay()
            }
        }
    }
}
