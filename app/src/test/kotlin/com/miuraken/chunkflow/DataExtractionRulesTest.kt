package com.miuraken.chunkflow

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * 自動バックアップと端末間移行の規則（`data_extraction_rules.xml` 等）の検証テスト（F2.4、設計書 §8.2・§9）。
 *
 * `cloud-backup` と `device-transfer` の対象が Room DB（`domain="database" path="."`）と
 * DataStore（`domain="file" path="datastore/"`）のみに限定されており、
 * `filesDir/media/`（音声・文字起こしの原本）や `filesDir/analysis/`（解析ファイル）が含まれないことを検証する。
 */
class DataExtractionRulesTest {

    private data class RuleRuleEntry(
        val tag: String,
        val domain: String,
        val path: String,
    )

    private fun resolveAppFile(relativeFromApp: String): File {
        val candidates = listOf(
            File(relativeFromApp),
            File("app/$relativeFromApp"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("File not found in expected paths: $candidates")
    }

    private fun parseSectionEntries(xmlFile: File, sectionTag: String): List<RuleRuleEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(xmlFile)
        val sections = doc.getElementsByTagName(sectionTag)
        assertEquals(1, sections.length, "$sectionTag セクションが 1 つ存在すること (${xmlFile.name})")
        val section = sections.item(0) as Element
        val children = section.childNodes
        val entries = mutableListOf<RuleRuleEntry>()
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            entries += RuleRuleEntry(
                tag = node.tagName,
                domain = node.getAttribute("domain"),
                path = node.getAttribute("path"),
            )
        }
        return entries
    }

    @Test
    fun `data_extraction_rules_cloud_backupとdevice_transferがDBとDataStoreのみを含みmediaとanalysisを含まない`() {
        val rulesFile = resolveAppFile("src/main/res/xml/data_extraction_rules.xml")
        val expectedIncludes = listOf(
            RuleRuleEntry(tag = "include", domain = "database", path = "."),
            RuleRuleEntry(tag = "include", domain = "file", path = "datastore/"),
        )

        for (section in listOf("cloud-backup", "device-transfer")) {
            val entries = parseSectionEntries(rulesFile, section)
            val includes = entries.filter { it.tag == "include" }
            assertEquals(
                expectedIncludes,
                includes,
                "$section の include は database(.) と file(datastore/) のみであること",
            )
            assertFalse(
                includes.any { it.path.contains("media") || it.path.contains("analysis") || (it.path == "." && it.domain == "file") },
                "$section に filesDir 全体や media/・analysis/ が含まれていないこと",
            )
        }

        // backup_rules.xml が存在する場合も同様に DB と DataStore のみであることを検証する
        val optionalBackupRules = listOf(
            File("src/main/res/xml/backup_rules.xml"),
            File("app/src/main/res/xml/backup_rules.xml"),
        ).firstOrNull { it.isFile }
        if (optionalBackupRules != null) {
            val entries = parseSectionEntries(optionalBackupRules, "full-backup-content")
            val includes = entries.filter { it.tag == "include" }
            assertEquals(expectedIncludes, includes)
        }
    }

    @Test
    fun `AndroidManifest_allowBackupが有効でdataExtractionRulesが設定されている`() {
        val manifestFile = resolveAppFile("src/main/AndroidManifest.xml")
        val content = manifestFile.readText(Charsets.UTF_8)
        assertTrue(content.contains("android:allowBackup=\"true\""))
        assertTrue(content.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
