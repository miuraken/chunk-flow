// スパイク S4（担当: transcriber）: ML Kit による翻訳（教材のベース言語へ）と校正（提案だけ）の品質・速さ・制約。
// 独立したアプリ（本体と同じ端末に入れられる）。本体のモジュールからこのモジュールへは依存しない（ADR 0003）。
// ML Kit の 3 つの依存は、人間の承認（2026-10-17）にもとづき、このスパイクだけに入れる。本体のアプリには入れない。
plugins {
    alias(libs.plugins.chunkflow.android.application.compose)
}

android {
    namespace = "com.miuraken.chunkflow.spike.textai"

    defaultConfig {
        applicationId = "com.miuraken.chunkflow.spike.textai"
        versionCode = 1
        versionName = "0.0.1-spike"
    }
}

dependencies {
    implementation(project(":core:model"))
    // 本体で書き出した文字起こしファイル（Chunk Flow JSON / SRT / WebVTT / LRC）の読み込みに使う
    implementation(project(":core:transcript"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)

    // 端末内の翻訳（言語ごとのモデルを実行時にダウンロードする）
    implementation(libs.mlkit.translate)
    // 端末内の校正（Gemini Nano。モデルは AICore が管理する）
    implementation(libs.mlkit.genai.proofreading)
    // 端末内の自由なプロンプト（Gemini Nano。翻訳・校正の比較用）
    implementation(libs.mlkit.genai.prompt)
}
