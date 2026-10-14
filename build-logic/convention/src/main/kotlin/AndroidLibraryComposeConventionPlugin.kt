import com.android.build.api.dsl.LibraryExtension
import com.miuraken.chunkflow.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Compose を使う Android ライブラリのモジュール（core:designsystem など）。 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("chunkflow.android.library")
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
            configureCompose()
        }
    }
}
