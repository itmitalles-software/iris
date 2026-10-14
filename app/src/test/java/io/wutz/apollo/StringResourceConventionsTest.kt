package io.wutz.apollo

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the wording conventions of the maintained locales (English and German). */
class StringResourceConventionsTest {
    private val maintained = listOf("src/main/res/values/strings.xml", "src/main/res/values-de/strings.xml")

    @Test
    fun `count labels use plurals instead of parenthesized suffixes`() {
        // "file(s)", "Zeile(n)", "Ergebnis(se)", "geladene(r)": DESIGN.md asks for <plurals>.
        val pseudoPlural = Regex("""\p{L}\((s|e|en|n|se|r|er|es)\)""")
        val offenders =
            maintained.flatMap { path ->
                texts(path).filter { (_, text) -> pseudoPlural.containsMatchIn(text) }.map { "$path: ${it.first}" }
            }
        assertTrue("Use <plurals> for counts: $offenders", offenders.isEmpty())
    }

    @Test
    fun `German strings say Sitzung, not Session`() {
        val anglicism = Regex("""\bSessions?\b""")
        val offenders =
            texts("src/main/res/values-de/strings.xml")
                .filter { (_, text) -> anglicism.containsMatchIn(text) }
                .map { it.first }
        assertTrue("Use \"Sitzung\" in German: $offenders", offenders.isEmpty())
    }

    /** (name, text) of every string and plurals item; comments are not included. */
    private fun texts(path: String): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val strings = document.getElementsByTagName("string")
        val items = document.getElementsByTagName("item")
        val stringTexts =
            (0 until strings.length)
                .map { strings.item(it) as Element }
                .map { it.getAttribute("name") to it.textContent }
        val pluralTexts =
            (0 until items.length)
                .map { items.item(it) as Element }
                .map { (it.parentNode as Element).getAttribute("name") to it.textContent }
        return stringTexts + pluralTexts
    }
}
