package com.miuraken.chunkflow.core.drive

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive API 呼び出し用の OAuth 2.0 アクセストークン取得結果（§5.1、§8.3、§9.2）。
 *
 * トークン文字列はログや例外メッセージに一切出力しないこと（§8.5）。
 */
sealed interface DriveAuthResult {
    /** 有効なアクセストークンが取得できた。 */
    data class Authorized(val accessToken: String) : DriveAuthResult

    /** 未認証、またはトークンの取得・更新に失敗した。 */
    data object NotAuthorized : DriveAuthResult
}

/**
 * Google Drive (`drive.readonly` / ファイル作成・更新) 用のアクセストークンを供給するインターフェース（§6.3、§8.3）。
 */
fun interface DriveAuthProvider {
    /** 現在のアクセストークンを取得する。 */
    suspend fun acquireAccessToken(): DriveAuthResult
}

/**
 * [DriveAuthProvider] のデフォルト実装。
 *
 * 実行時またはテストからトークン供給関数を設定でき、未設定時は安全に [DriveAuthResult.NotAuthorized] を返す。
 */
@Singleton
class DefaultDriveAuthProvider @Inject constructor() : DriveAuthProvider {

    private val supplierRef = AtomicReference<(suspend () -> DriveAuthResult)?>(null)

    /**
     * トークン供給関数を登録する。
     */
    fun setTokenSupplier(supplier: (suspend () -> DriveAuthResult)?) {
        supplierRef.set(supplier)
    }

    /**
     * 固定のアクセストークンを設定する（`null` または空文字列で未認証状態に戻す）。
     */
    fun setAccessToken(accessToken: String?) {
        val trimmed = accessToken?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            supplierRef.set(null)
        } else {
            supplierRef.set { DriveAuthResult.Authorized(trimmed) }
        }
    }

    override suspend fun acquireAccessToken(): DriveAuthResult {
        val supplier = supplierRef.get() ?: return DriveAuthResult.NotAuthorized
        return when (val result = supplier()) {
            is DriveAuthResult.Authorized -> {
                if (result.accessToken.isBlank()) {
                    DriveAuthResult.NotAuthorized
                } else {
                    result
                }
            }

            DriveAuthResult.NotAuthorized -> DriveAuthResult.NotAuthorized
        }
    }
}
