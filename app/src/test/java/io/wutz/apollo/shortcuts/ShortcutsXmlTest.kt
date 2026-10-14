package io.wutz.apollo.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static shortcuts name the application id as targetPackage, so the debug build (".dev") has its own
 * copy of shortcuts.xml. This keeps both copies, the manifest alias and the Kotlin constants in sync.
 */
class ShortcutsXmlTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun `debug copy differs from main only in comments and the application id`() {
        val main = normalized("src/main/res/xml/shortcuts.xml")
        val debug = normalized("src/debug/res/xml/shortcuts.xml")
        assertEquals(main.replace("\"io.wutz.apollo\"", "\"io.wutz.apollo.dev\""), debug)
    }

    @Test
    fun `static shortcuts target the private entry alias with the known actions`() {
        val shortcuts = parse("src/main/res/xml/shortcuts.xml")
        val intents = shortcuts.elements("intent")
        assertEquals(
            listOf(AppShortcuts.ACTION_NEW_CHAT, AppShortcuts.ACTION_VOICE_CHAT, AppShortcuts.ACTION_HISTORY),
            intents.map { it.getAttributeNS(androidNs, "action") },
        )
        intents.forEach { intent ->
            assertEquals(AppShortcuts.SHORTCUT_ENTRY_ALIAS, intent.getAttributeNS(androidNs, "targetClass"))
            assertEquals("io.wutz.apollo", intent.getAttributeNS(androidNs, "targetPackage"))
        }
        val shareTarget = shortcuts.elements("share-target").single()
        assertEquals("io.wutz.apollo.share.ShareTargetActivity", shareTarget.getAttributeNS(androidNs, "targetClass"))
        assertEquals(
            AppShortcuts.SHARE_TARGET_CATEGORY,
            shareTarget.elements("category").single().getAttributeNS(androidNs, "name"),
        )
    }

    @Test
    fun `shortcut entry alias is private and targets MainActivity`() {
        val alias =
            parse("src/main/AndroidManifest.xml")
                .elements("activity-alias")
                .single { "io.wutz.apollo" + it.getAttributeNS(androidNs, "name") == AppShortcuts.SHORTCUT_ENTRY_ALIAS }
        assertEquals("false", alias.getAttributeNS(androidNs, "exported"))
        assertEquals(".MainActivity", alias.getAttributeNS(androidNs, "targetActivity"))
        assertTrue(alias.getElementsByTagName("intent-filter").length == 0)
    }

    @Test
    fun `quick settings tile is bound only by the system`() {
        val tile =
            parse("src/main/AndroidManifest.xml")
                .elements("service")
                .single { it.getAttributeNS(androidNs, "name") == ".shortcuts.NewChatTileService" }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttributeNS(androidNs, "permission"))
    }

    private fun normalized(path: String): String =
        file(path)
            .readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .joinToString("\n")

    private fun parse(path: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file(path))

    private fun file(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    private fun Document.elements(tag: String): List<Element> = documentElement.elements(tag)

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
