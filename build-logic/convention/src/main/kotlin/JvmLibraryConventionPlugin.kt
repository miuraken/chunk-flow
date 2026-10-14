import com.miuraken.chunkflow.buildlogic.configureKotlinJvm
import com.miuraken.chunkflow.buildlogic.libs
import com.miuraken.chunkflow.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project

/** 純 Kotlin（Android 非依存）のモジュール（core:model、core:audio、core:transcript）。Android Lint も適用する。 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("kotlin-jvm"))
            pluginManager.apply(libs.pluginId("android-lint"))
            configureKotlinJvm()
        }
    }
}
