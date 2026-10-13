package io.wutz.apollo.ui.mcp

import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText

object McpImportSummary {
    /**
     * Formats the summary message reporting the result of a batch MCP JSON import.
     * [errors] are server-provided `name: message` entries and stay untranslated.
     */
    fun formatSummary(
        successCount: Int,
        errors: List<String>,
    ): UiText {
        val errSummary = errors.joinToString("; ")
        return when {
            errors.isEmpty() -> {
                UiText.plural(R.plurals.mcp_msg_import_success, successCount)
            }

            successCount > 0 -> {
                UiText.plural(R.plurals.mcp_msg_import_partial, successCount, successCount, errors.size, errSummary)
            }

            else -> {
                UiText.res(R.string.mcp_msg_import_failed, errSummary)
            }
        }
    }
}
