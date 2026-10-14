// スパイク S2（担当: playback）: PlayerMessage によるループの精度、MP3 のシーク精度、自動ポーズからの再開、Drive の選択・同期の試作。
// 独立したアプリ（本体と同じ端末に入れられる）。本体のモジュールからこのモジュールへは依存しない（ADR 0003）。
plugins {
    alias(libs.plugins.chunkflow.android.application.compose)
}

android {
    namespace = "com.miuraken.chunkflow.spike.playback"

    defaultConfig {
        applicationId = "com.miuraken.chunkflow.spike.playback"
        versionCode = 1
        versionName = "0.0.1-spike"
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // 端末での計測（TestExoPlayerBuilder、FakeClock など）。JVM の単体テストで使うには Robolectric が別に必要
    androidTestImplementation(libs.androidx.media3.test.utils)
}
