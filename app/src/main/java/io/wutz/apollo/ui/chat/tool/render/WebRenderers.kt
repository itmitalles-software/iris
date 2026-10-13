package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.SearchHit
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import io.wutz.apollo.ui.chat.tool.ToolViewExtras
import io.wutz.apollo.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Resolve the URL a web/browser tool targeted, as a display hostname+path. */
internal fun targetHostname(
    args: JsonObject?,
    result: JsonObject?,
    res: ToolText,
): String {
    val url =
        ToolJson
            .firstString(args, listOf("url", "target"))
            .ifEmpty { ToolJson.firstString(result, listOf("url")) }
            .ifEmpty { ToolJson.findFirstUrl(args, result) }

    return if (url.isNotEmpty()) ToolJson.hostnameOf(url) else res.get(R.string.tool_msg_page)
}

/**
 * `web_search`: quoted-query title, parsed [SearchHit] rows for the
 * structured result list, and a "Search results" section label.
 */
internal object WebSearchRenderer : ToolRenderer {
    private fun searchQueryFor(args: JsonObject?): String =
        ToolJson.firstString(args, listOf("search_term", "query")).ifEmpty { ToolJson.contextValue(args) }

    private fun extractSearchResults(
        result: JsonElement?,
        limit: Int = 6,
    ): List<SearchHit> =
        ToolJson
            .collectResultItems(result)
            .mapNotNull { item ->
                val r = ToolJson.parseMaybeObject(item) ?: return@mapNotNull null

                SearchHit(
                    title = ToolJson.cleanVisibleText(ToolJson.firstString(r, listOf("title", "name"))),
                    url = ToolJson.firstString(r, listOf("url", "href", "link")),
                    snippet =
                        ToolJson.cleanVisibleText(
                            ToolJson.firstString(r, listOf("snippet", "description", "body")),
                        ),
                )
            }.filter { it.title.isNotEmpty() || it.url.isNotEmpty() }
            .take(limit)

    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_web_searching, ToolJson.compactPreview(searchQueryFor(call.args), 48))

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_web_searched, ToolJson.compactPreview(searchQueryFor(call.args), 48))

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String =
        searchQueryFor(call.args).takeIf { it.isNotEmpty() }?.let { res.get(R.string.tool_msg_query, it) }
            ?: res.get(R.string.tool_msg_queried_web)

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
        res: ToolText,
    ): ToolViewExtras {
        val hits = if (status != ToolViewStatus.ERROR) extractSearchResults(call.rawResult) else emptyList()

        return ToolViewExtras(
            detailLabel = if (hits.isNotEmpty()) res.get(R.string.tool_msg_search_results) else null,
            searchHits = hits.ifEmpty { null },
            searchQuery = searchQueryFor(call.args).ifEmpty { null },
        )
    }
}

/** `web_extract`: hostname title/subtitle with the extracted text as detail. */
internal object WebExtractRenderer : ToolRenderer {
    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_reading, targetHostname(call.args, null, res))

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_read, targetHostname(call.args, call.result, res))

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val url =
            ToolJson
                .firstString(call.args, listOf("url"))
                .ifEmpty { ToolJson.firstString(call.result, listOf("url")) }
                .ifEmpty { ToolJson.findFirstUrl(call.args, call.result) }

        return if (url.isNotEmpty()) ToolJson.hostnameOf(url) else res.get(R.string.tool_msg_fetched_webpage)
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val direct =
            ToolJson.firstString(call.result, listOf("content", "text", "markdown", "body", "summary", "message"))
        if (direct.isNotEmpty()) {
            return direct.replace(Regex("\\s*in\\s+\\d+(?:\\.\\d+)?s\\s*$"), "").trim()
        }

        val results = call.result?.get("results") as? JsonArray
        if (results != null) {
            return results
                .mapNotNull { item ->
                    val row = ToolJson.parseMaybeObject(item) ?: return@mapNotNull null
                    ToolJson.firstString(row, listOf("content", "text", "markdown", "body"))
                }.filter { it.isNotEmpty() }
                .joinToString("\n\n---\n\n")
        }

        return ToolJson.fallbackDetailText(call.rawArgs, call.rawResult, res)
    }
}

/** `x_search`: query subtitle plus answer text with numbered citations. */
internal object XSearchRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val query = ToolJson.firstString(call.args, listOf("query"))
        val degraded = ToolJson.boolTrue(call.result?.get("degraded"))

        return when {
            query.isEmpty() -> res.get(R.string.tool_msg_queried_x)
            degraded -> res.get(R.string.tool_msg_no_citations_suffix, query)
            else -> query
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        val answer = ToolJson.firstString(result, listOf("answer"))
        val citations = result?.get("citations") as? JsonArray
        val degraded = ToolJson.boolTrue(result?.get("degraded"))

        return when {
            error.isNotEmpty() -> {
                "❌ $error"
            }

            answer.isNotEmpty() -> {
                val lines = mutableListOf(answer)
                if (citations != null && citations.isNotEmpty()) {
                    lines += "\n" + res.get(R.string.tool_msg_citations_header)
                    citations.forEachIndexed { idx, cit ->
                        val c = ToolJson.parseMaybeObject(cit) ?: return@forEachIndexed
                        val url = ToolJson.firstString(c, listOf("url"))
                        val title = ToolJson.firstString(c, listOf("title"))
                        lines += "${idx + 1}. ${title.ifEmpty { url.ifEmpty { res.get(R.string.tool_msg_source) } }}"
                        if (title.isNotEmpty() && url.isNotEmpty()) lines += "   $url"
                    }
                }
                if (degraded) lines += "\n" + res.get(R.string.tool_msg_no_citations_note)
                lines.joinToString("\n")
            }

            else -> {
                res.get(R.string.tool_msg_no_results)
            }
        }
    }
}
