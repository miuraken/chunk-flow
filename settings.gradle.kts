pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "chunk-flow"

// モジュール構成は設計書 §5.2。依存ルールもそちらを参照。
include(":app")
include(":feature:library")
include(":feature:player")
include(":feature:settings")
include(":core:model")
include(":core:audio")
include(":core:transcript")
include(":core:media")
include(":core:playback")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:designsystem")
include(":core:testing")

// スパイク（S2: playback、S3: audio-analysis、S4: transcriber）。build.gradle.kts を lead が置いたときだけ取り込む（ADR 0003）。
// 本体のモジュールからスパイクへ依存してはいけない。
listOf("playback", "audio", "textai").forEach { name ->
    if (file("spikes/$name/build.gradle.kts").exists()) {
        include(":spikes:$name")
    }
}
