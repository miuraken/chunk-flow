// スパイク S3（担当: audio-analysis）: デコード速度と、解析・再生の時間軸の一致
// （MediaExtractor と media3-inspector の MediaExtractorCompat を比べる）。
// 独立したアプリ（本体と同じ端末に入れられる）。本体のモジュールからこのモジュールへは依存しない（ADR 0003）。
plugins {
    alias(libs.plugins.chunkflow.android.application.compose)
}

android {
    namespace = "com.miuraken.chunkflow.spike.audio"

    defaultConfig {
        applicationId = "com.miuraken.chunkflow.spike.audio"
        versionCode = 1
        versionName = "0.0.1-spike"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:audio"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.inspector)
    // 再生位置との時間軸の比較に使う
    implementation(libs.androidx.media3.exoplayer)
}
