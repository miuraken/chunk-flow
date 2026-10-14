package com.miuraken.chunkflow.core.designsystem.util

import java.util.Locale

/**
 * ミリ秒単位の時間を `mm:ss`（1 時間未満）または `h:mm:ss`（1 時間以上）の文字列に整形する。
 *
 * 負の値が渡された場合は `00:00` として扱う。
 */
fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/**
 * 再生速度（例: `1.0f`, `1.25f`）を表示用の文字列（例: `"1.00x"`）に整形する。
 */
fun formatSpeed(speed: Float): String = String.format(Locale.ROOT, "%.2fx", speed)
