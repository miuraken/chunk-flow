package com.miuraken.chunkflow.core.drive.di

import com.miuraken.chunkflow.core.drive.DefaultDriveAuthProvider
import com.miuraken.chunkflow.core.drive.DriveAuthProvider
import com.miuraken.chunkflow.core.drive.DriveSyncClient
import com.miuraken.chunkflow.core.drive.HttpUrlConnectionDriveSyncClient
import com.miuraken.chunkflow.core.drive.HttpUrlConnectionModelArtifactDownloader
import com.miuraken.chunkflow.core.drive.ModelArtifactDownloader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `core:drive` の Hilt バインディングモジュール（§6.2、§6.3）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DriveModule {

    /** [DriveAuthProvider] の実装をバインドする。 */
    @Binds
    @Singleton
    abstract fun bindDriveAuthProvider(impl: DefaultDriveAuthProvider): DriveAuthProvider

    /** [DriveSyncClient] の実装をバインドする。 */
    @Binds
    @Singleton
    abstract fun bindDriveSyncClient(impl: HttpUrlConnectionDriveSyncClient): DriveSyncClient

    /** [ModelArtifactDownloader] の実装をバインドする。 */
    @Binds
    @Singleton
    abstract fun bindModelArtifactDownloader(
        impl: HttpUrlConnectionModelArtifactDownloader,
    ): ModelArtifactDownloader
}
