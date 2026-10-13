package io.wutz.apollo.ui.chat.tool

import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #10: the tool engine's own words come from string resources (asserted by id and
 * arguments via [TestToolText.recording]); server data only ever appears as an argument.
 */
class ToolTextLocalizationTest {
    private val rec = TestToolText.recording

    private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun build(
        tool: String,
        args: String?,
        result: String?,
        running: Boolean = false,
        res: ToolText = rec,
    ) = ToolViewBuilder.build(tool, args?.let(::json), result?.let(::json), running = running, res = res)

    private fun call(
        tool: String,
        result: JsonElement?,
    ) = ToolCall.of(tool, null, result)

    @Test
    fun `terminal titles use the ran and running resources with the raw command`() {
        assertEquals(
            TestToolText.recorded(R.string.tool_title_running, "sleep 10"),
            build("terminal", """{"command":"sleep 10"}""", null, running = true).title,
        )
        assertEquals(
            TestToolText.recorded(R.string.tool_title_ran, "ls"),
            build("terminal", """{"command":"ls"}""", """{"output":"a","exit_code":0}""").title,
        )
        assertEquals(
            TestToolText.recorded(R.string.tool_title_ran_code, "print(1)"),
            build("execute_code", """{"code":"print(1)"}""", """{"output":"1"}""").title,
        )
    }

    @Test
    fun `web search title, subtitle and detail label are resources`() {
        val view =
            build(
                "web_search",
                """{"query":"cats"}""",
                """{"results":[{"title":"Cat","url":"https://cats.example"}]}""",
            )
        assertEquals(TestToolText.recorded(R.string.tool_title_web_searched, "cats"), view.title)
        assertEquals(TestToolText.recorded(R.string.tool_msg_query, "cats"), view.subtitle)
        assertEquals(TestToolText.recorded(R.string.tool_msg_search_results), view.detailLabel)
        assertEquals(TestToolText.recordedPlural(R.plurals.tool_msg_count_result, 1), view.countLabel)
    }

    @Test
    fun `navigate fallback hostname is the localized page word`() {
        assertEquals(
            TestToolText.recorded(R.string.tool_title_opening, TestToolText.recorded(R.string.tool_msg_page)),
            build("browser_navigate", null, null, running = true).title,
        )
    }

    @Test
    fun `delegate failure title is a plural over all tasks with the failed count`() {
        val view =
            build(
                "delegate_task",
                """{"tasks":[{"goal":"a"},{"goal":"b"}]}""",
                """{"results":[{"task_index":0,"status":"completed"},{"task_index":1,"status":"failed"}]}""",
            )
        assertEquals(TestToolText.recordedPlural(R.plurals.tool_title_delegated_tasks_failed, 2, 2, 1), view.title)
    }

    @Test
    fun `read terminal subtitle carries the line window and cursor as arguments`() {
        val view =
            build(
                "read_terminal",
                null,
                """{"text":"x","start":1,"end":10,"total_lines":50,"cursor_row":3}""",
            )
        assertEquals(TestToolText.recorded(R.string.tool_msg_terminal_lines_cursor, 1, 10, 50, 3), view.subtitle)
    }

    @Test
    fun `status resolver keeps server errors raw and localizes its own fallbacks`() {
        assertEquals(
            UiText.res(R.string.tool_msg_error_generic),
            ToolStatusResolver.errorText(call("weird_tool", null), isError = true),
        )
        assertEquals(
            UiText.raw("everything broke"),
            ToolStatusResolver.errorText(call("weird_tool", JsonPrimitive("everything broke")), isError = true),
        )
        assertEquals(
            UiText.res(R.string.tool_msg_error_success_false),
            ToolStatusResolver.errorText(call("weird_tool", json("""{"success":false}""")), isError = false),
        )
        assertEquals(
            UiText.raw("disk full"),
            ToolStatusResolver.errorText(
                call("weird_tool", json("""{"success":false,"message":"disk full"}""")),
                isError = false,
            ),
        )
        assertEquals(
            UiText.res(R.string.tool_msg_error_status, "failed"),
            ToolStatusResolver.errorText(call("weird_tool", json("""{"status":"failed"}""")), isError = false),
        )
        assertEquals(
            UiText.res(R.string.tool_msg_error_exit_code, 2),
            ToolStatusResolver.errorText(call("terminal", json("""{"exit_code":2}""")), isError = false),
        )
        assertNull(ToolStatusResolver.errorText(call("terminal", json("""{"output":"ok"}""")), isError = false))
    }

    @Test
    fun `count labels localize known nouns and keep server-derived nouns`() {
        assertEquals(
            TestToolText.recordedPlural(R.plurals.tool_msg_count_result, 3),
            ToolCounts.formatCountLabel(3, "result", rec),
        )
        assertEquals(
            TestToolText.recordedPlural(R.plurals.tool_msg_item_count, 1),
            ToolCounts.formatCountLabel(1, "item", rec),
        )
        assertEquals("3 widgets", ToolCounts.formatCountLabel(3, "widget", rec))
        assertEquals("3 Ergebnisse", ToolCounts.formatCountLabel(3, "result", TestToolText.german))
    }

    @Test
    fun `truncation note is a plural over the omitted characters`() {
        assertEquals(
            "abc\n" + TestToolText.recordedPlural(R.plurals.tool_msg_output_truncated, 2),
            ToolJson.clampForDisplay("abcde", rec, max = 3),
        )
    }

    @Test
    fun `German resolution formats arguments into the translated strings`() {
        val german = TestToolText.german
        assertEquals(
            "example.com geöffnet",
            build("browser_navigate", """{"url":"https://example.com"}""", "{}", res = german).title,
        )
        assertEquals(
            "„cats“ gesucht",
            build("web_search", """{"query":"cats"}""", """{"results":[]}""", res = german).title,
        )
        assertEquals(
            "Befehl mit Exitcode 2 fehlgeschlagen.",
            build("terminal", """{"command":"false"}""", """{"exit_code":2}""", res = german).error,
        )
    }
}
