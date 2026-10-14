// DataStore（アプリ設定）
plugins {
    alias(libs.plugins.chunkflow.android.library)
}

android {
    namespace = "com.miuraken.chunkflow.core.datastore"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
}
