// テーマ、共通 Composable（担当: designsystem。ADR 0012）。Phase 0 の雛形は lead が置いた（ADR 0003）。
plugins {
    alias(libs.plugins.chunkflow.android.library.compose)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.miuraken.chunkflow.core.designsystem"
    // Compose Preview Screenshot Testing（ADR 0002）。共通部品の基準画像を feature と同じ仕組みで管理する
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(project(":core:model"))
    api(libs.androidx.compose.material3)
    // @ChunkFlowPreviews がプレビューの注釈を使うため、利用側にも公開する
    api(libs.androidx.compose.ui.tooling.preview)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
