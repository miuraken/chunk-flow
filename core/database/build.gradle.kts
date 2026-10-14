// Room（Entity / DAO / Migration）。スキーマは lead が管理する（§6.4）。
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.room)
}

android {
    namespace = "com.miuraken.chunkflow.core.database"
}

dependencies {
    api(project(":core:model"))
}
