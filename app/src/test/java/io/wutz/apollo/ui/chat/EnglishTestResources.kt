package io.wutz.apollo.ui.chat

import android.app.Application
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolText
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.resolve
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English string resources for plain JVM tests (issue #10).
 *
 * Unit tests run without Robolectric, so `Resources` is a mock. This mock answers `getString` and
 * `getQuantityString` from `src/main/res/values/strings.xml`, so tests of rows that are resolved at
 * creation time (local feedback, subagent logs) can keep asserting the real English text.
 */
internal object EnglishTestResources {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        // strings.xml plus the per-area files (strings_<area>.xml) next to it.
        val dir =
            listOf("src/main/res/values", "app/src/main/res/values")
                .map(::File)
                .first { File(it, "strings.xml").isFile }
        val files =
            dir
                .listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }
                .orEmpty()
                .sortedBy { it.name }
        files.forEach(::load)
    }

    private fun load(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    strings[name] = unescape(node.textContent)
                }

                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[name] =
                        (0 until items.length).associate { index ->
                            val item = items.item(index) as org.w3c.dom.Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                }
            }
        }
    }

    private val stringNames: Map<Int, String> by lazy { fieldNames(R.string::class.java) }
    private val pluralNames: Map<Int, String> by lazy { fieldNames(R.plurals::class.java) }

    private fun fieldNames(type: Class<*>): Map<Int, String> =
        type.fields.associate { field -> field.getInt(null) to field.name }

    private fun unescape(raw: String): String =
        raw
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\@", "@")

    private fun format(
        template: String,
        args: Array<out Any?>,
    ): String = if (args.isEmpty()) template else String.format(Locale.US, template, *args)

    fun string(
        id: Int,
        vararg args: Any?,
    ): String {
        val name = stringNames[id] ?: error("Unknown string id $id")
        return format(strings[name] ?: error("Missing English string $name"), args)
    }

    fun quantityString(
        id: Int,
        count: Int,
        vararg args: Any?,
    ): String {
        val name = pluralNames[id] ?: error("Unknown plurals id $id")
        val items = plurals[name] ?: error("Missing English plurals $name")
        val template = (if (count == 1) items["one"] else null) ?: items.getValue("other")
        return format(template, args)
    }

    /** A mock [Resources] that resolves strings and plurals in English. */
    fun resources(): Resources =
        mockk(relaxed = true) {
            every { getString(any()) } answers { string(firstArg()) }
            every { getString(any(), *anyVararg()) } answers {
                string(firstArg(), *varargs(args))
            }
            every { getQuantityString(any(), any(), *anyVararg()) } answers {
                quantityString(firstArg(), secondArg(), *varargs(args.drop(1)))
            }
        }

    /** Makes [app] resolve strings in English (both `app.getString` and `app.resources`). */
    fun install(app: Application) {
        val res = resources()
        every { app.resources } returns res
        every { app.getString(any()) } answers { string(firstArg()) }
        every { app.getString(any(), *anyVararg()) } answers { string(firstArg(), *varargs(args)) }
    }

    fun resolve(text: UiText): String = text.resolve(resources())

    /** English tool-display texts for search and tool view tests. */
    val toolText: ToolText by lazy { ToolText("en-test") { resolve(it) } }

    /** MockK passes a vararg either flattened or as one array argument after the id. */
    private fun varargs(args: List<Any?>): Array<Any?> {
        val rest = args.drop(1)
        return if (rest.size == 1 && rest[0] is Array<*>) {
            (rest[0] as Array<*>).map { it }.toTypedArray()
        } else {
            rest.toTypedArray()
        }
    }
}
