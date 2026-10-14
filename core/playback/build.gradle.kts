// 再生（PlaybackService、LearningPlayer、ChunkScheduler、PlaybackController。担当: playback）
// ExoPlayer を直接触るのはこのモジュールだけ（§5.2）。
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.hilt)
}

android {
    namespace = "com.miuraken.chunkflow.core.playback"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.media3.common)
    api(libs.kotlinx.coroutines.core)
    // ChunkScheduler はチャンクと言語を AnalysisRepository から直接受け取る（§7.2.1）
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
