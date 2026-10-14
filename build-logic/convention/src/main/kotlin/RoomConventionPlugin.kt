import androidx.room.gradle.RoomExtension
import com.miuraken.chunkflow.buildlogic.lib
import com.miuraken.chunkflow.buildlogic.libs
import com.miuraken.chunkflow.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Room（KSP）を使うモジュール（core:database）。スキーマは schemas/ に書き出してコミットする（マイグレーションのテストに使う）。 */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("ksp"))
            pluginManager.apply(libs.pluginId("room"))
            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }
            dependencies {
                add("implementation", libs.lib("androidx-room-runtime"))
                add("implementation", libs.lib("androidx-room-ktx"))
                add("ksp", libs.lib("androidx-room-compiler"))
            }
        }
    }
}
