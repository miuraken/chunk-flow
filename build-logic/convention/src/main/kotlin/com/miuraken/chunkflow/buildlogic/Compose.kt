package com.miuraken.chunkflow.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Compose を使うモジュールの共通設定（Compose コンパイラ、BOM、プレビュー）。 */
internal fun Project.configureCompose() {
    pluginManager.apply(libs.pluginId("kotlin-compose"))
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // core:model の型（Compose に依存しない）を安定型として扱う（§6.2）
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
    }
    dependencies {
        val bom = platform(libs.lib("androidx-compose-bom"))
        add("implementation", bom)
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        add("testImplementation", bom)
    }
}
