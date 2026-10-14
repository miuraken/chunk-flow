// 文字起こしファイルの解析・検証・チャンクへの対応付け（純 Kotlin、Android 非依存。担当: transcriber）
plugins {
    alias(libs.plugins.chunkflow.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:audio"))
    implementation(libs.kotlinx.serialization.json)
}
