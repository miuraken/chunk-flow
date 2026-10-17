// デコード（MediaExtractor + MediaCodec）、メタデータ取得、形式検証（担当: audio-analysis）
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.hilt)
}

android {
    namespace = "com.miuraken.chunkflow.core.media"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:audio"))
    implementation(project(":core:drive"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.inspector)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.genai.speech.recognition)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.robolectric)
}
