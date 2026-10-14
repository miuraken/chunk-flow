// デコード（MediaExtractor + MediaCodec）、メタデータ取得、形式検証（担当: audio-analysis）
plugins {
    alias(libs.plugins.chunkflow.android.library)
}

android {
    namespace = "com.miuraken.chunkflow.core.media"
}

dependencies {
    api(project(":core:model"))
}
