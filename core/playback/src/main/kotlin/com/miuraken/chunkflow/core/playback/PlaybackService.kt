package com.miuraken.chunkflow.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.miuraken.chunkflow.core.playback.di.PlaybackMainDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * バックグラウンド再生・メディア通知・ロック画面 / イヤホン操作・再生再開を担う [MediaSessionService]（§7.2.1〜§7.2.6、§8.1）。
 *
 * - 標準の再生コマンドはシステム UI・Bluetooth・[androidx.media3.session.MediaButtonReceiver] からの接続を許可する。
 * - カスタムコマンドは自アプリおよび信頼できるシステムのコントローラからのみ受け付ける（§8.1）。
 * - [MediaSession.Callback.onPlaybackResumption] で、最後に更新・再生していた教材の位置・速度から再生を再開する（§7.2.5）。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackController: MediaControllerPlaybackController

    @Inject
    @PlaybackMainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    private var serviceScope: CoroutineScope? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val learningPlayer = playbackController.getOrCreateLearningPlayer()
        mediaSession = MediaSession.Builder(this, learningPlayer)
            .apply {
                createSessionActivityPendingIntent()?.let { setSessionActivity(it) }
            }
            .setCallback(PlaybackSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    internal fun createSessionActivityPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * [controller] が自アプリまたは信頼済みのシステムコントローラであるかを判定する（§8.1）。
     */
    internal fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean = controller.packageName == packageName ||
        controller.isTrusted ||
        controller.uid == Process.myUid() ||
        controller.uid == Process.SYSTEM_UID

    private inner class PlaybackSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val defaultResult = super.onConnect(session, controller)
            return if (isTrustedController(controller)) {
                defaultResult
            } else {
                // 外部アプリからの接続ではカスタムの SessionCommand を公開せず、標準の Player コマンドのみ許可する（§8.1）
                MediaSession.ConnectionResult.accept(
                    androidx.media3.session.SessionCommands.EMPTY,
                    defaultResult.availablePlayerCommands,
                )
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!isTrustedController(controller)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val scope = serviceScope ?: return Futures.immediateFailedFuture(
                IllegalStateException("PlaybackService scope is not active"),
            )
            scope.launch {
                try {
                    val restored = playbackController.prepareForResumption()
                    if (restored != null) {
                        future.set(restored)
                    } else {
                        future.setException(IllegalStateException("No resumable media item available"))
                    }
                } catch (ce: CancellationException) {
                    future.cancel(false)
                    throw ce
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }
}
