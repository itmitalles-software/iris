package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import kotlinx.serialization.json.JsonArray

/** `memory`: action-verb titles that keep the write target visible. */
internal object MemoryRenderer : ToolRenderer {
    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_memory_saving)

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val action = ToolJson.firstString(call.args, listOf("action")).lowercase()
        val target = ToolJson.firstString(call.args, listOf("target"))
        val base =
            when (action) {
                "replace", "update" -> res.get(R.string.tool_title_memory_updated)
                "remove", "delete" -> res.get(R.string.tool_title_memory_removed)
                "add" -> res.get(R.string.tool_title_memory_saved)
                else -> res.get(R.string.tool_title_memory_action, ToolJson.firstString(call.args, listOf("action")))
            }

        // Keep the target visible — "Memory Saved (user)" — like the
        // pre-engine mobile summary did.
        return if (target.isNotEmpty()) "$base ($target)" else base
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.firstString(call.result, listOf("message", "error"))

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.firstString(call.result, listOf("message", "error"))
}

/** `fact_store`: add/remove/update acks and fact listings with trust scores. */
internal object FactStoreRenderer : ToolRenderer {
    private fun facts(call: ToolCall): JsonArray? =
        (call.result?.get("results") as? JsonArray)
            ?: (call.result?.get("facts") as? JsonArray)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val args = call.args
        val result = call.result
        val action =
            ToolJson
                .firstString(
                    args,
                    listOf("action"),
                ).ifEmpty { ToolJson.firstString(result, listOf("action")) }
        val entity = ToolJson.firstString(args, listOf("entity"))
        val query = ToolJson.firstString(args, listOf("query"))
        val facts = facts(call)
        val count = ToolJson.intValue(result?.get("count")) ?: facts?.size ?: 0
        val status = ToolJson.firstString(result, listOf("status"))
        val factId = ToolJson.intValue(result?.get("fact_id"))
        val removed = ToolJson.boolTrue(result?.get("removed"))
        val updated = ToolJson.boolTrue(result?.get("updated"))

        val actionContext =
            when {
                entity.isNotEmpty() -> " ($action: $entity)"
                query.isNotEmpty() -> " ($action: $query)"
                else -> " ($action)"
            }

        return when {
            status == "added" && factId != null -> res.get(R.string.tool_msg_fact_added_id, factId)
            status == "added" -> res.get(R.string.tool_msg_fact_added)
            removed -> res.get(R.string.tool_msg_fact_removed)
            updated -> res.get(R.string.tool_msg_fact_updated)
            facts != null -> res.plural(R.plurals.tool_msg_fact_count, count) + actionContext
            else -> action
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        facts(call)
            ?.mapNotNull { el ->
                val item = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val fid = ToolJson.intValue(item["fact_id"]) ?: 0
                val content = ToolJson.firstString(item, listOf("content"))
                val category = ToolJson.firstString(item, listOf("category"))
                val trust = ToolJson.numberValue(item["trust_score"])
                val tags = ToolJson.firstString(item, listOf("tags"))

                val metaParts = mutableListOf<String>()
                if (category.isNotEmpty()) metaParts += "[$category]"
                if (trust != null) metaParts += res.get(R.string.tool_msg_trust, "%.2f".format(trust))
                if (tags.isNotEmpty()) metaParts += "🏷️ $tags"

                if (metaParts.isNotEmpty()) {
                    "#$fid  $content\n      ${metaParts.joinToString("  ")}"
                } else {
                    "#$fid  $content"
                }
            }?.joinToString("\n")
            ?.takeIf { it.isNotEmpty() }
            ?: ""
}
