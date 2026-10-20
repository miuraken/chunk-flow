package com.miuraken.chunkflow.core.designsystem.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * OS の「アニメーションを削除」（開発者向けオプションの Animator duration scale = 0、
 * またはユーザー補助の「アニメーションを削除」）が有効かどうかを返す。
 *
 * 形状遷移（shape morphing）などの装飾的なアニメーションは、`true` のとき即時に切り替える。
 * プレビュー / スクリーンショットでは常に `false`。
 * 設定値はコンポジションの開始時に 1 回だけ読む（設定の変更は画面を作り直したときに反映される）。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
