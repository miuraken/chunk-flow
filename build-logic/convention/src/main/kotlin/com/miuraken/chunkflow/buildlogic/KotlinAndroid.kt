package com.miuraken.chunkflow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/*
 * JDK の方針（ADR 0001）:
 * - Gradle は JDK 17 以上（ローカルは Android Studio の JBR、CI は Temurin）で動かし、Java ツールチェーンは使わない。
 * - 出力するバイトコードは Java 17 に揃える（Android は compileOptions、JVM モジュールは --release 17 / -Xjdk-release=17）。
 */
private val JAVA_VERSION = JavaVersion.VERSION_17
private val KOTLIN_JVM_TARGET = JvmTarget.JVM_17

/** アプリのモジュールの共通設定。 */
internal fun Project.configureAndroidApplication(extension: ApplicationExtension) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig {
            minSdk = libs.version("minSdk").toInt()
            targetSdk = libs.version("targetSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        lint { configureLint(this@configureAndroidApplication) }
    }
    disableAndroidJdkImageTransform()
    configureKotlinJvmTarget()
    configureUnitTests()
}

/** ライブラリのモジュールの共通設定。 */
internal fun Project.configureAndroidLibrary(extension: LibraryExtension) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig {
            minSdk = libs.version("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        lint {
            configureLint(this@configureAndroidLibrary)
            targetSdk = libs.version("targetSdk").toInt()
        }
        testOptions {
            targetSdk = libs.version("targetSdk").toInt()
        }
    }
    disableAndroidJdkImageTransform()
    configureKotlinJvmTarget()
    configureUnitTests()
}

/**
 * AGP が Java 9+ の [JavaCompile] に自動で追加する `JdkImageInput`（内部で `jmod` / `jlink` を起動する
 * `JdkImageTransform`）を `afterEvaluate` で取り除き、`jmod` / `jlink` に依存せずに `android.jar` に対してコンパイルする。
 */
private fun Project.disableAndroidJdkImageTransform() {
    afterEvaluate {
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgumentProviders.removeAll {
                it.javaClass.name.contains("JdkImageInput")
            }
            if ("-Xlint:-options" !in options.compilerArgs) {
                options.compilerArgs.add("-Xlint:-options")
            }
        }
    }
}

/** JVM（純 Kotlin）モジュールの共通設定。 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(JAVA_VERSION.majorVersion.toInt())
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(KOTLIN_JVM_TARGET)
            // Gradle を動かす JDK が 17 より新しくても、Java 17 にない API を使えないようにする
            freeCompilerArgs.add("-Xjdk-release=${JAVA_VERSION.majorVersion}")
        }
    }
    extensions.configure<Lint> { configureLint(this@configureKotlinJvm) }
    configureUnitTests()
}

private fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(KOTLIN_JVM_TARGET)
        }
    }
}

/** Android Lint の共通設定。新しい警告はエラーとして扱う（§9）。 */
internal fun Lint.configureLint(project: Project) {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = false
    lintConfig = project.rootProject.file("lint.xml")
}

private fun Project.configureUnitTests() {
    dependencies {
        add("testImplementation", libs.lib("kotlin-test-junit"))
        add("testImplementation", libs.lib("junit4"))
    }
    tasks.withType<Test>().configureEach {
        // まだテストや @PreviewTest がないモジュールでもタスクが失敗しないようにする（Gradle 9 対応）
        failOnNoDiscoveredTests.set(false)
        // Compose Preview Screenshot Testing（Layoutlib）で多数のプレビューを描画した際の Heap OOM / GC ハングを防ぐ
        maxHeapSize = "2g"
        // 端末やロケールに依存しないよう、テストの既定のロケールとタイムゾーンを固定する
        systemProperty("user.language", "ja")
        systemProperty("user.country", "JP")
        systemProperty("user.timezone", "UTC")
    }
}
