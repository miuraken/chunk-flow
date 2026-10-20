// ライブラリ画面（担当: ui）。Phase 0 の雛形は lead が置いた（ADR 0003）。
plugins {
    alias(libs.plugins.chunkflow.android.feature)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.miuraken.chunkflow.feature.library"
    // Compose Preview Screenshot Testing（ADR 0002）
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
