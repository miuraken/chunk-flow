import com.android.build.api.dsl.ApplicationExtension
import com.miuraken.chunkflow.buildlogic.configureAndroidApplication
import com.miuraken.chunkflow.buildlogic.libs
import com.miuraken.chunkflow.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** アプリのモジュール（:app）。AGP 9 の組み込みの Kotlin を使う（kotlin-android プラグインは適用しない）。 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("android-application"))
            extensions.configure<ApplicationExtension> { configureAndroidApplication(this) }
        }
    }
}
