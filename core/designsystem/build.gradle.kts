// テーマ、共通 Composable（担当: ui）。Phase 0 の雛形は lead が置いた（ADR 0003）。
plugins {
    alias(libs.plugins.chunkflow.android.library.compose)
}

android {
    namespace = "com.miuraken.chunkflow.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    // @ChunkFlowPreviews がプレビューの注釈を使うため、利用側にも公開する
    api(libs.androidx.compose.ui.tooling.preview)
}
