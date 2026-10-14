// 波形の縮約・特徴量・VAD・チャンク分割・リサンプラ（純 Kotlin、Android 非依存。担当: audio-analysis）
plugins {
    alias(libs.plugins.chunkflow.jvm.library)
}

dependencies {
    api(project(":core:model"))
}
