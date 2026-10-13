package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import kotlinx.serialization.json.JsonArray

/** `browser_navigate`: hostname titles, including the failed-to-open form. */
internal object BrowserNavigateRenderer : ToolRenderer {
    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_opening, targetHostname(call.args, null, res))

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_opened, targetHostname(call.args, call.result, res))

    override fun errorTitle(
        call: ToolCall,
        res: ToolText,
    ): String? {
        val url =
            ToolJson
                .findFirstUrl(call.result)
                .ifEmpty { call.result?.get("url")?.toString() ?: "" }

        return if (url.isNotEmpty()) res.get(R.string.tool_title_open_failed, ToolJson.hostnameOf(url)) else null
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val url =
            ToolJson
                .firstString(call.args, listOf("url", "target"))
                .ifEmpty { ToolJson.firstString(call.result, listOf("url")) }
                .ifEmpty { ToolJson.findFirstUrl(call.args, call.result) }

        return if (url.isNotEmpty()) ToolJson.hostnameOf(url) else res.get(R.string.tool_msg_navigated_in_browser)
    }
}

/** `browser_snapshot`: control-count summary of the accessibility snapshot. */
internal object BrowserSnapshotRenderer : ToolRenderer {
    private fun summarizeSnapshot(
        snapshot: String,
        res: ToolText,
    ): String {
        fun count(re: Regex): Int = re.findAll(snapshot).count()

        val stats =
            listOf(
                count(Regex("""button\s+"[^"]+""")).let { res.plural(R.plurals.tool_msg_snapshot_buttons, it) },
                count(Regex("""link\s+"[^"]+""")).let { res.plural(R.plurals.tool_msg_snapshot_links, it) },
                count(Regex("""(?:textbox|combobox|searchbox)\s+"[^"]+""")).let {
                    res.plural(R.plurals.tool_msg_snapshot_inputs, it)
                },
            ).joinToString(" · ")

        val labels =
            Regex("""(?:button|link|combobox|textbox)\s+"([^"]+)"""")
                .findAll(snapshot)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .take(4)
                .toList()

        return if (labels.isNotEmpty()) {
            "$stats\n" + res.get(R.string.tool_msg_top_controls, labels.joinToString(", "))
        } else {
            stats
        }
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val snapshot = ToolJson.firstString(call.result, listOf("snapshot"))

        return if (snapshot.isNotEmpty()) {
            summarizeSnapshot(snapshot, res)
        } else {
            res.get(R.string.tool_msg_snapshot_captured)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val snapshot = ToolJson.firstString(call.result, listOf("snapshot"))

        return if (snapshot.isNotEmpty()) {
            summarizeSnapshot(snapshot, res)
        } else {
            ToolJson.fallbackDetailText(call.rawArgs, call.rawResult, res)
        }
    }
}

/** `browser_click`: what got clicked, with internal refs called out. */
internal object BrowserClickRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val clicked =
            ToolJson
                .firstString(call.result, listOf("clicked"))
                .ifEmpty { ToolJson.firstString(call.args, listOf("ref", "target")) }

        return when {
            clicked.isEmpty() -> res.get(R.string.tool_msg_clicked_on_page)
            clicked.startsWith("@") -> res.get(R.string.tool_msg_clicked_internal_ref, clicked)
            else -> res.get(R.string.tool_msg_clicked, clicked)
        }
    }
}

/** `browser_fill` / `browser_type`: which field got which value. */
internal object BrowserTypeRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val field = ToolJson.firstString(call.args, listOf("label", "field", "ref", "target"))
        val value = ToolJson.firstString(call.args, listOf("value", "text"))

        return listOf(
            field.takeIf { it.isNotEmpty() }?.let { res.get(R.string.tool_msg_field, it) },
            value.takeIf { it.isNotEmpty() }?.let { res.get(R.string.tool_msg_value, ToolJson.compactPreview(it, 42)) },
        ).filterNotNull()
            .joinToString(" · ")
            .ifEmpty { res.get(R.string.tool_msg_filled_input) }
    }
}

/** browser_scroll / browser_back / browser_press: one-line acks. */
internal object BrowserActionRenderer : ToolRenderer {
    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String? =
        when (call.name) {
            "browser_scroll" -> {
                val scrolled =
                    ToolJson
                        .firstString(call.result, listOf("scrolled"))
                        .ifEmpty { ToolJson.firstString(call.args, listOf("direction")) }
                        .ifEmpty { res.get(R.string.tool_msg_page) }
                res.get(R.string.tool_title_scrolled, scrolled)
            }

            "browser_back" -> {
                val url = ToolJson.firstString(call.result, listOf("url"))
                if (url.isNotEmpty()) {
                    res.get(R.string.tool_title_went_back_to, ToolJson.hostnameOf(url))
                } else {
                    res.get(R.string.tool_title_went_back)
                }
            }

            "browser_press" -> {
                res.get(
                    R.string.tool_title_pressed,
                    ToolJson
                        .firstString(call.result, listOf("pressed"))
                        .ifEmpty { ToolJson.firstString(call.args, listOf("key")) },
                )
            }

            else -> {
                null
            }
        }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ""

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String = ""
}

/** browser_get_images: image inventory of the current page. */
internal object BrowserImagesRenderer : ToolRenderer {
    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val n = ToolJson.intValue(call.result?.get("count")) ?: 0
        return res.plural(R.plurals.tool_title_found_images, n)
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        (call.result?.get("images") as? JsonArray)
            ?.take(12)
            ?.mapNotNull { el ->
                val img = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val src = ToolJson.firstString(img, listOf("src"))
                val alt = ToolJson.firstString(img, listOf("alt"))
                listOf(alt, src)
                    .filter { it.isNotEmpty() }
                    .joinToString(" — ")
                    .takeIf { it.isNotEmpty() }
                    ?.let { "- $it" }
            }?.joinToString("\n") ?: ""
}

/** browser_vision: screenshot Q&A — the analysis text is the payload. */
internal object BrowserVisionRenderer : ToolRenderer {
    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_looking_at_page)

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_analyzed_page)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("question")), 120)

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        ToolJson
            .firstString(call.result, listOf("analysis"))
            .ifEmpty { ToolJson.firstString(call.result, listOf("note")) }
}

/** browser_console: message/error counts, or a JS eval result. */
internal object BrowserConsoleRenderer : ToolRenderer {
    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String? {
        val result = call.result ?: return null
        if (result.containsKey("result")) return res.get(R.string.tool_title_evaluated_js)
        val msgs = ToolJson.intValue(result["total_messages"]) ?: 0
        val errs = ToolJson.intValue(result["total_errors"]) ?: 0
        val messages = res.plural(R.plurals.tool_msg_console_messages, msgs)
        return if (errs > 0) {
            res.get(
                R.string.tool_title_console_with_errors,
                messages,
                res.plural(R.plurals.tool_msg_console_errors, errs),
            )
        } else {
            res.get(R.string.tool_title_console, messages)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result ?: return ""
        result["result"]?.let { return it.toString() }
        val lines =
            (result["console_messages"] as? JsonArray)?.take(30)?.mapNotNull { el ->
                val m = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                "[${ToolJson.firstString(m, listOf("type")).ifEmpty { "log" }}] " +
                    ToolJson.compactPreview(ToolJson.firstString(m, listOf("text")), 200)
            } ?: emptyList()
        val errors =
            (result["js_errors"] as? JsonArray)?.take(10)?.mapNotNull { el ->
                val e = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                "[error] ${ToolJson.compactPreview(ToolJson.firstString(e, listOf("message")), 200)}"
            } ?: emptyList()
        return (errors + lines).joinToString("\n")
    }
}
