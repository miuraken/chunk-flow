package com.miuraken.chunkflow.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** ルートの Version Catalog（gradle/libs.versions.toml）。 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Version Catalog のライブラリ。 */
fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** Version Catalog のバージョン（文字列）。 */
fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/** Version Catalog のプラグイン ID。 */
fun VersionCatalog.pluginId(alias: String): String = findPlugin(alias).get().get().pluginId
