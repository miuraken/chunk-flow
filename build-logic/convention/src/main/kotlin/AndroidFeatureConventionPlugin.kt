import com.miuraken.chunkflow.buildlogic.lib
import com.miuraken.chunkflow.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * 画面のモジュール（feature:*）。
 *
 * 依存ルール（§5.2）: feature 同士は依存しない。画面遷移は :app で組み立てる。
 * プレビュー用のフィクスチャのため core:testing に依存する（Fake とフィクスチャだけで、テストの枠組みは含まない）。
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("chunkflow.android.library.compose")
            pluginManager.apply("chunkflow.hilt")
            dependencies {
                add("implementation", project(":core:model"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:testing"))
                add("implementation", libs.lib("androidx-compose-material3"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            }
        }
    }
}
