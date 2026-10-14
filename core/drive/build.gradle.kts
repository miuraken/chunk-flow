// Google Drive の手動同期（担当: playback）。ネットワークに触るのはこのモジュールだけ（§5.2）。
plugins {
    alias(libs.plugins.chunkflow.android.library)
}

android {
    namespace = "com.miuraken.chunkflow.core.drive"
}

dependencies {
    api(project(":core:model"))
}
