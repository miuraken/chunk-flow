// Room（Entity / DAO / Migration）。スキーマは lead が管理する（§6.4）。
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.room)
    alias(libs.plugins.chunkflow.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.miuraken.chunkflow.core.database"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
