// Google Drive API の最小クライアントと OAuth トークン管理（担当: playback、設計書 §7.2）。
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.miuraken.chunkflow.core.drive"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
