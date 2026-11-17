package io.wutz.apollo.ui.sessions.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SessionTransferFormatTest {
    private val labels =
        TranscriptLabels(
            untitled = "Untitled",
            user = "You",
            assistant = "Assistant",
            image = "image",
            truncated = "Shortened.",
        )

    private fun export(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `markdown shows the visible user and assistant turns only`() {
        val md =
            SessionTransferFormat.toMarkdown(
                export(
                    """
                    {"id":"s1","title":"Trip plan","model":"gpt-x","started_at":86400.5,
                     "system_prompt":"SECRET SYSTEM PROMPT",
                     "messages":[
                      {"role":"user","content":"Hi","active":1,"compacted":0},
                      {"role":"assistant","content":"","tool_calls":[{"id":"t"}],"active":1},
                      {"role":"tool","content":"tool output","active":1},
                      {"role":"assistant","content":"Hello!","active":1},
                      {"role":"user","content":"rewound","active":0,"compacted":0},
                      {"role":"user","content":"old compacted","active":0,"compacted":1},
                      {"role":"user","content":"summary","active":1,"_compressed_summary":true},
                      {"role":"user","content":"hidden","active":1,"display_metadata":{"model_only":true}},
                      {"role":"user","content":[{"type":"text","text":"Look"},{"type":"image_url","image_url":{}}],"active":1}
                     ]}
                    """.trimIndent(),
                ),
                labels,
                zone = ZoneOffset.UTC,
            )
        assertTrue(md.startsWith("# Trip plan\n\n_1970-01-02 · gpt-x_\n"))
        assertTrue(md.contains("**You:**\n\nHi\n"))
        assertTrue(md.contains("**Assistant:**\n\nHello!\n"))
        assertTrue(md.contains("old compacted"))
        assertTrue(md.contains("Look\n\n[image]"))
        listOf("SECRET", "tool output", "rewound", "summary", "hidden").forEach {
            assertFalse("must not contain $it", md.contains(it))
        }
    }

    @Test
    fun `markdown falls back to the untitled label and truncates long transcripts`() {
        val long = "x".repeat(60_000)
        val md =
            SessionTransferFormat.toMarkdown(
                export("""{"messages":[{"role":"user","content":"$long"},{"role":"assistant","content":"$long"}]}"""),
                labels,
            )
        assertTrue(md.startsWith("# Untitled\n"))
        assertTrue(md.length <= SessionTransferFormat.MAX_SHARE_CHARS + 100)
        assertTrue(md.endsWith("_Shortened._\n"))
    }

    @Test
    fun `import accepts a single export, a list, a sessions object and json lines`() {
        fun ids(text: String) =
            SessionTransferFormat
                .parseImportSessions(text)
                .getOrThrow()
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals(listOf("a"), ids("""{"id":"a","messages":[]}"""))
        assertEquals(listOf("a", "b"), ids("""[{"id":"a"},{"id":"b"}]"""))
        assertEquals(listOf("a"), ids("""{"sessions":[{"id":"a"},{"title":"no id"}]}"""))
        assertEquals(listOf("a", "b"), ids("{\"id\":\"a\"}\n\n{\"id\":\"b\"}\n"))
        assertEquals(listOf("a"), ids(Char(0xFEFF) + "{\"id\":\"a\"}"))
    }

    @Test
    fun `import rejects files without sessions`() {
        fun error(text: String) =
            (SessionTransferFormat.parseImportSessions(text).exceptionOrNull() as ImportPayloadException).error

        assertEquals(ImportPayloadError.NotJson, error("not json at all"))
        assertEquals(ImportPayloadError.NoSessions, error(""))
        assertEquals(ImportPayloadError.NoSessions, error("""{"title":"no id"}"""))
        assertEquals(ImportPayloadError.NoSessions, error("[]"))
    }

    @Test
    fun `import body carries the profile in the body`() {
        val sessions = SessionTransferFormat.parseImportSessions("""{"id":"a"}""").getOrThrow()
        val body = SessionTransferFormat.importBody(sessions, "work")
        assertEquals(JsonPrimitive("work"), body["profile"])
        assertEquals(1, body["sessions"]!!.jsonArray.size)
        assertNull(SessionTransferFormat.importBody(sessions, null)["profile"])
        assertNull(SessionTransferFormat.importBody(sessions, " ")["profile"])
    }

    @Test
    fun `export file names are safe`() {
        assertEquals("iris-Trip-plan-März.json", SessionTransferFormat.exportFileName("Trip plan / März?", "s1"))
        assertEquals("iris-20260101_abc.json", SessionTransferFormat.exportFileName("  ", "20260101_abc"))
        assertEquals("iris-a-b.json", SessionTransferFormat.exportFileName(null, "a/b"))
    }
}
