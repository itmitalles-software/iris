package io.wutz.apollo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards manifest-level security wiring that no unit test of Kotlin code can see. */
class ManifestPlatformContractTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun `no app data leaves the device by cloud backup or device transfer`() {
        val application = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(androidNs, "allowBackup"))
        // allowBackup=false no longer stops device-to-device transfer for targetSdk 31+.
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(androidNs, "dataExtractionRules"))

        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        val domains =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as Element
            assertEquals("$section must not include anything", 0, element.getElementsByTagName("include").length)
            val excludes = element.getElementsByTagName("exclude")
            val excluded =
                (0 until excludes.length)
                    .map { excludes.item(it) as Element }
                    .filter { it.getAttribute("path") == "." }
                    .map { it.getAttribute("domain") }
                    .toSet()
            assertEquals("$section must exclude every domain", domains, excluded)
        }
    }

    @Test
    fun `notification entry alias is private and targets MainActivity`() {
        val aliases = parse("src/main/AndroidManifest.xml").getElementsByTagName("activity-alias")
        val alias =
            (0 until aliases.length)
                .map { aliases.item(it) as Element }
                .single {
                    "io.wutz.apollo" + it.getAttributeNS(androidNs, "name") ==
                        MainActivity.NOTIFICATION_ENTRY_ALIAS
                }
        assertEquals("false", alias.getAttributeNS(androidNs, "exported"))
        assertEquals(".MainActivity", alias.getAttributeNS(androidNs, "targetActivity"))
        assertTrue(
            "the alias must not be reachable by intent filters",
            alias.getElementsByTagName("intent-filter").length == 0,
        )
    }

    private fun parse(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(listOf(File(path), File("app/$path")).first { it.exists() })
}
