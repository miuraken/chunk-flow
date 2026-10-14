// Repository、取り込み処理、Worker。パッケージごとに担当が分かれる（AGENTS.md §3）:
// library = playback、analysis = audio-analysis、transcript = transcriber、それ以外（di など）= lead
plugins {
    alias(libs.plugins.chunkflow.android.library)
    alias(libs.plugins.chunkflow.hilt)
}

android {
    namespace = "com.miuraken.chunkflow.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:transcript"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))
    implementation(project(":core:drive"))
    implementation(project(":core:audio"))
}
