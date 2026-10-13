package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import io.wutz.apollo.ui.chat.tool.ToolViewExtras
import io.wutz.apollo.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `search_files`: ripgrep-backed content/file search.
 * Handles both result shapes: dense path-grouped `matches_text` string (≥5 hits)
 * and `matches:[{path,line,content}]` (<5 hits), plus `files:[path]`.
 */
internal object SearchFilesRenderer : ToolRenderer {
    private fun pattern(call: ToolCall) = ToolJson.firstString(call.args, listOf("pattern"))

    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String? =
        pattern(call).takeIf { it.isNotEmpty() }?.let {
            res.get(R.string.tool_title_files_searching, ToolJson.compactPreview(it, 48))
        }

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String? {
        val p = pattern(call).takeIf { it.isNotEmpty() } ?: return null
        val total = ToolJson.intValue(call.result?.get("total_count"))
        val shown = ToolJson.compactPreview(p, 48)
        return if (total != null) {
            res.plural(R.plurals.tool_title_files_searched_matches, total, shown, total)
        } else {
            res.get(R.string.tool_title_files_searched, shown)
        }
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val path = ToolJson.firstString(call.args, listOf("path")).takeIf { it != "." && it.isNotEmpty() } ?: ""
        val glob = ToolJson.firstString(call.args, listOf("file_glob"))
        return listOf(path, glob).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result ?: return ""
        // Dense ≥5-match block: already ripgrep-grouped, use verbatim.
        val dense = ToolJson.firstString(result, listOf("matches_text"))
        val body =
            if (dense.isNotEmpty()) {
                dense
            } else {
                (result["matches"] as? JsonArray)
                    ?.mapNotNull { el ->
                        val m = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                        val path = ToolJson.firstString(m, listOf("path"))
                        val line = ToolJson.intValue(m["line"])
                        val content = ToolJson.firstString(m, listOf("content"))
                        "$path:${line ?: "?"}: ${content.trim()}"
                    }?.joinToString("\n")
                    ?: (result["files"] as? JsonArray)
                        ?.mapNotNull {
                            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        }?.joinToString("\n") ?: ""
            }
        val hint =
            if (ToolJson.boolTrue(result["truncated"])) {
                ToolJson.firstString(result, listOf("_hint")).ifEmpty { res.get(R.string.tool_msg_results_truncated) }
            } else {
                ""
            }
        return listOf(body, hint).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
        res: ToolText,
    ): ToolViewExtras = ToolViewExtras(detailLabel = res.get(R.string.tool_msg_matches))
}
