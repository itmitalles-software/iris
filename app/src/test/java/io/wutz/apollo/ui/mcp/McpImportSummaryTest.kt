package io.wutz.apollo.ui.mcp

import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class McpImportSummaryTest {
    @Test
    fun `formatSummary returns success message when zero errors`() {
        val result = McpImportSummary.formatSummary(3, emptyList())
        assertEquals(UiText.plural(R.plurals.mcp_msg_import_success, 3), result)
    }

    @Test
    fun `formatSummary returns partial success message when some failed`() {
        val result = McpImportSummary.formatSummary(2, listOf("serverA: timeout", "serverB: 404"))
        assertEquals(
            UiText.plural(R.plurals.mcp_msg_import_partial, 2, 2, 2, "serverA: timeout; serverB: 404"),
            result,
        )
    }

    @Test
    fun `formatSummary returns failure message when all failed`() {
        val result = McpImportSummary.formatSummary(0, listOf("serverA: connection refused"))
        assertEquals(UiText.res(R.string.mcp_msg_import_failed, "serverA: connection refused"), result)
    }
}
