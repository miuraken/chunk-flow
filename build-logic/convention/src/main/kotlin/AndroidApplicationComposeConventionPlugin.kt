import com.android.build.api.dsl.ApplicationExtension
import com.miuraken.chunkflow.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Compose を使うアプリのモジュール。 */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("chunkflow.android.application")
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
            configureCompose()
        }
    }
}
