// アプリ（Application、MainActivity、Navigation 3、DI の組み立て）
plugins {
    alias(libs.plugins.chunkflow.android.application.compose)
    alias(libs.plugins.chunkflow.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.miuraken.chunkflow"

    defaultConfig {
        applicationId = "com.miuraken.chunkflow"
        versionCode = 1
        versionName = "0.1.0"
    }

    androidResources {
        localeFilters += listOf("ja", "en")
    }

    buildTypes {
        release {
            // §8.2: R8 による縮小・難読化。署名はリポジトリの外で行う（署名の設定はここに書かない）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                // Play Console のクラッシュレポート解析用にネイティブシンボル（.so の関数名）を AAB に同梱する
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
}

dependencies {
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))
    implementation(project(":core:playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
}
