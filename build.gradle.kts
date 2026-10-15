// すべてのプラグインをここで `apply false` で宣言し、build-logic の convention plugins から使えるようにする。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.compose.screenshot) apply false
    alias(libs.plugins.spotless)
}

// 整形（Spotless + ktlint）。ルールは .editorconfig。`./gradlew spotlessApply` で直す。
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
}

// Android Studio 等が実行する `updateDaemonJvm` タスクが Toolchain download repository 未設定で失敗するのを防ぐ（ADR 0001）。
tasks.withType<org.gradle.buildconfiguration.tasks.UpdateDaemonJvm>().configureEach {
    toolchainPlatforms.empty()
}
