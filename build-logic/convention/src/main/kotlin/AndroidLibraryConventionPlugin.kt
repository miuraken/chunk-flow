import com.android.build.api.dsl.LibraryExtension
import com.miuraken.chunkflow.buildlogic.configureAndroidLibrary
import com.miuraken.chunkflow.buildlogic.libs
import com.miuraken.chunkflow.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android ライブラリのモジュール（core:* のうち Android に依存するもの）。 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("android-library"))
            extensions.configure<LibraryExtension> { configureAndroidLibrary(this) }
        }
    }
}
