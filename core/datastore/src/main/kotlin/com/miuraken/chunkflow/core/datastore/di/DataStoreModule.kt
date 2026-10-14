package com.miuraken.chunkflow.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.miuraken.chunkflow.core.datastore.DataStoreUserSettingsRepository
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * アプリ設定用の [DataStore] と [UserSettingsRepository] を提供する Hilt モジュール（§6.4、§8.2）。
 *
 * 保存先は `filesDir/datastore/user_settings.preferences_pb`（`data_extraction_rules.xml` の `datastore/` 対象）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {
    /** [DataStoreUserSettingsRepository] を [UserSettingsRepository] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsUserSettingsRepository(
        impl: DataStoreUserSettingsRepository,
    ): UserSettingsRepository

    companion object {
        private const val USER_SETTINGS_FILE_NAME = "user_settings"

        /** アプリ全体で共有する Preferences [DataStore] を提供する。 */
        @Provides
        @Singleton
        fun providesPreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(USER_SETTINGS_FILE_NAME) },
        )
    }
}
