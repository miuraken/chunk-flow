package com.miuraken.chunkflow.core.playback

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.miuraken.chunkflow.core.model.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * M1 品質ゲート（qa 担当）: [PlaybackPlayerFactory] の設定検証（§7.2.2、ADR 0006、ADR 0007）および
 * [PlaybackService] の外部コントローラによるカスタムコマンド拒否の検証テスト（§7.2.5、§8.1、§9）。
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceAndFactoryQaTest {

    @Test
    fun `PlaybackPlayerFactory_設計書7_2_2のAudioAttributesとシーク増分が設定される`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val player = PlaybackPlayerFactory.createExoPlayer(context)
        try {
            assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, player.audioAttributes.contentType)
            assertEquals(UserSettings.DEFAULT_SEEK_BACK_MS, player.seekBackIncrement)
            assertEquals(UserSettings.DEFAULT_SEEK_FORWARD_MS, player.seekForwardIncrement)
        } finally {
            player.release()
        }
    }

    @Test
    fun `PlaybackService_isTrustedController_自パッケージとシステムUIDは許可し外部アプリは拒否する`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val service = PlaybackService()
        val attachMethod = android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java,
        ).apply {
            isAccessible = true
        }
        attachMethod.invoke(service, context)
        val ownPackage = service.packageName

        val ownController = createControllerInfo(
            packageName = ownPackage,
            uid = Process.myUid(),
            isTrusted = false,
        )
        val systemController = createControllerInfo(
            packageName = "com.android.systemui",
            uid = Process.SYSTEM_UID,
            isTrusted = false,
        )
        val trustedController = createControllerInfo(
            packageName = "com.android.bluetooth",
            uid = 1002,
            isTrusted = true,
        )
        val externalUntrustedController = createControllerInfo(
            packageName = "com.example.malicious",
            uid = Process.myUid() + 9999,
            isTrusted = false,
        )

        assertTrue(service.isTrustedController(ownController))
        assertTrue(service.isTrustedController(systemController))
        assertTrue(service.isTrustedController(trustedController))
        assertFalse(service.isTrustedController(externalUntrustedController))
    }

    @Test
    fun `PlaybackService_外部コントローラからのカスタムコマンドはERROR_PERMISSION_DENIEDで拒否される`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val service = PlaybackService()
        val attachMethod = android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java,
        ).apply {
            isAccessible = true
        }
        attachMethod.invoke(service, context)

        val externalController = createControllerInfo(
            packageName = "com.example.external",
            uid = Process.myUid() + 5000,
            isTrusted = false,
        )

        val innerPlayer = PlaybackPlayerFactory.createExoPlayer(context)
        val session = MediaSession.Builder(context, innerPlayer).build()
        try {
            val callbackClass = PlaybackService::class.java.declaredClasses
                .first { it.simpleName == "PlaybackSessionCallback" }
            val constructor = callbackClass.getDeclaredConstructor(PlaybackService::class.java).apply {
                isAccessible = true
            }
            val callback = constructor.newInstance(service) as MediaSession.Callback

            val connectionResult = callback.onConnect(session, externalController)
            assertTrue(connectionResult.isAccepted)
            assertEquals(0, connectionResult.availableSessionCommands.commands.size)

            val customCommand = SessionCommand("com.miuraken.chunkflow.action.TOGGLE_LOOP", Bundle.EMPTY)
            val resultFuture = callback.onCustomCommand(
                session,
                externalController,
                customCommand,
                Bundle.EMPTY,
            )
            val sessionResult = resultFuture.get()
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, sessionResult.resultCode)
        } finally {
            session.release()
            innerPlayer.release()
        }
    }

    private fun createControllerInfo(
        packageName: String,
        uid: Int,
        isTrusted: Boolean,
    ): MediaSession.ControllerInfo {
        val constructor = MediaSession.ControllerInfo::class.java.declaredConstructors.first {
            it.parameterCount >= 5
        }.apply {
            isAccessible = true
        }
        val remoteUserInfoClass = constructor.parameterTypes[0]
        val remoteUserInfoCtor = remoteUserInfoClass.getDeclaredConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
        }
        val remoteUserInfo = remoteUserInfoCtor.newInstance(packageName, 1234, uid)
        val args = arrayOfNulls<Any?>(constructor.parameterCount)
        args[0] = remoteUserInfo
        for (i in 1 until constructor.parameterCount) {
            args[i] = when (constructor.parameterTypes[i]) {
                Int::class.javaPrimitiveType -> 0
                Boolean::class.javaPrimitiveType -> isTrusted
                Bundle::class.java -> Bundle.EMPTY
                else -> null
            }
        }
        return constructor.newInstance(*args) as MediaSession.ControllerInfo
    }
}
