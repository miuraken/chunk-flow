// Fake 実装とフィクスチャ（§6.3、§10.5.1）。プレビューでも使うため、テストの枠組み（JUnit など）は含めない。
plugins {
    alias(libs.plugins.chunkflow.android.library)
}

android {
    namespace = "com.miuraken.chunkflow.core.testing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:playback"))
    api(project(":core:data"))
    api(project(":core:transcript"))
    api(platform(libs.androidx.compose.bom))
    // PreviewParameterProvider を公開 API で使う
    api(libs.androidx.compose.ui.tooling.preview)
}
