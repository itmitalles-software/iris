package io.wutz.apollo.ui.chat.tool

import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [ToolText] fixtures for JVM unit tests (no Android resources at runtime).
 *
 * [english] and [german] resolve resource ids against the real `values` / `values-de`
 * strings.xml, so characterization tests assert the exact text a user sees, including the
 * resource id choice and its format arguments. [recording] renders the [UiText] itself, for
 * tests that assert ids and arguments directly.
 */
internal object TestToolText {
    val english: ToolText by lazy { fromValues("values", Locale.US) }
    val german: ToolText by lazy { fromValues("values-de", Locale.GERMANY) }

    /** Returns `UiText.toString()` (id + args) instead of text. */
    val recording: ToolText = ToolText("recording") { it.toString() }

    /** What [recording] returns for a string resource. */
    fun recorded(
        id: Int,
        vararg args: Any,
    ): String = UiText.res(id, *args).toString()

    /** What [recording] returns for a plurals resource. */
    fun recordedPlural(
        id: Int,
        count: Int,
        vararg args: Any,
    ): String = UiText.plural(id, count, *args).toString()

    private fun fromValues(
        dir: String,
        locale: Locale,
    ): ToolText {
        val file =
            listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml"))
                .first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<Int, String>()
        val plurals = mutableMapOf<Int, Map<String, String>>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val el = root.childNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> {
                    idOf(R.string::class.java, name)?.let { strings[it] = unescape(el.textContent) }
                }

                "plurals" -> {
                    val id = idOf(R.plurals::class.java, name) ?: continue
                    val items = el.getElementsByTagName("item")
                    plurals[id] =
                        (0 until items.length).associate { idx ->
                            val item = items.item(idx) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                }
            }
        }

        fun resolve(text: UiText): String =
            when (text) {
                is UiText.Raw -> {
                    text.text
                }

                is UiText.Resource -> {
                    val pattern = strings[text.id] ?: error("missing string ${text.id} in $dir")
                    if (text.args.isEmpty()) pattern else pattern.format(locale, *args(text.args, ::resolve))
                }

                is UiText.Plural -> {
                    val forms = plurals[text.id] ?: error("missing plurals ${text.id} in $dir")
                    val pattern = forms[if (text.count == 1) "one" else "other"] ?: forms.getValue("other")
                    pattern.format(locale, *args(text.args, ::resolve))
                }

                is UiText.Failure -> {
                    resolve(text.text)
                }
            }

        return ToolText(locale.toLanguageTag()) { resolve(it) }
    }

    private fun args(
        args: List<Any>,
        resolve: (UiText) -> String,
    ): Array<Any> = args.map { if (it is UiText) resolve(it) else it }.toTypedArray()

    private fun idOf(
        type: Class<*>,
        name: String,
    ): Int? = runCatching { type.getField(name).getInt(null) }.getOrNull()

    /** The subset of Android string escaping the tool strings use. */
    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
