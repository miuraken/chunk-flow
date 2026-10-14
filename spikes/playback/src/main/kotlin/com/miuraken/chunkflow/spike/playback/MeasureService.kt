package com.miuraken.chunkflow.spike.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 計測の間だけプロセスをフォアグラウンドに保つサービス（スパイク専用）。
 *
 * 共用のエミュレータで他のアプリが前面に出ると、このアプリはキャッシュ済みのプロセスとして凍結され
 * （ActivityManager の freezer）、計測が止まるため。
 */
class MeasureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "計測", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("S2 計測中")
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "measure"

        /** 計測を始める前に呼ぶ（前面の Activity から）。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeasureService::class.java))
        }

        /** 計測が終わったら呼ぶ。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MeasureService::class.java))
        }
    }
}
