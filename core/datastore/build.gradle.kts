// DataStore（アプリ設定）
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.hilt)
}

android {
    namespace = "com.miuraken.chunkflow.core.datastore"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
}
