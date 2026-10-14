package com.miuraken.chunkflow.spike.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 計測中にプロセスが凍結されないようにするための、何もしないフォアグラウンドサービス（種類は API 35 以上で mediaProcessing、API 34 で dataSync）。
 *
 * エミュレータを他の担当と共用していて、計測アプリが背面に回るとキャッシュされたアプリとして凍結され、
 * 計測が止まった（30 分の ADTS のデコードに 722 秒）。本実装の AnalysisWorker も setForeground() で
 * 同じ種類のフォアグラウンドサービスとして動かす（§7.3.4）。前面にいる間に開始すること。
 */
class MeasureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "S3 計測", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("S3 計測中")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        // §7.3.4 と同じく、API 35 以上は mediaProcessing、API 34 は dataSync
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(ID, n, type)
        return START_NOT_STICKY
    }

    /** 開始と停止 */
    companion object {
        private const val CHANNEL = "s3"
        private const val ID = 1

        /** 開始する（前面にいる間に呼ぶ）。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeasureService::class.java))
        }

        /** 停止する。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MeasureService::class.java))
        }
    }
}
