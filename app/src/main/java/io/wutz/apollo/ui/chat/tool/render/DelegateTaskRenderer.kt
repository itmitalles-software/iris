package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `delegate_task`: fan-out of subagent tasks.
 * Covers joined results, background dispatch handles, and list/steer/stop acks.
 */
internal object DelegateTaskRenderer : ToolRenderer {
    private fun goals(
        call: ToolCall,
        res: ToolText,
    ): List<String> {
        val tasks = call.args?.get("tasks") as? JsonArray
        val fromTasks =
            tasks?.mapIndexedNotNull { i, el ->
                ToolJson
                    .parseMaybeObject(el)
                    ?.let { ToolJson.firstString(it, listOf("goal")) }
                    ?.ifEmpty { res.get(R.string.tool_msg_task_n, i + 1) }
            } ?: emptyList()
        if (fromTasks.isNotEmpty()) return fromTasks
        return ToolJson
            .firstString(call.args, listOf("goal"))
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(it) } ?: emptyList()
    }

    private fun action(call: ToolCall) = ToolJson.firstString(call.args, listOf("action")).ifEmpty { "spawn" }

    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String =
        when (action(call)) {
            "list" -> {
                res.get(R.string.tool_title_listing_subagents)
            }

            "steer" -> {
                res.get(R.string.tool_title_steering_subagent)
            }

            "stop" -> {
                res.get(R.string.tool_title_stopping_subagent)
            }

            else -> {
                val n = goals(call, res).size.coerceAtLeast(1)
                res.plural(R.plurals.tool_title_delegating_tasks, n)
            }
        }

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        return when {
            action(call) == "list" -> {
                val n = ToolJson.intValue(result?.get("count")) ?: 0
                res.plural(R.plurals.tool_title_subagent_count, n)
            }

            action(call) == "steer" -> {
                res.get(R.string.tool_title_steer_queued)
            }

            action(call) == "stop" -> {
                res.get(R.string.tool_title_stop_requested)
            }

            ToolJson.firstString(result, listOf("status")) == "dispatched" -> {
                val n = ToolJson.intValue(result?.get("count")) ?: goals(call, res).size
                res.plural(R.plurals.tool_title_dispatched_tasks, n)
            }

            else -> {
                val results = result?.get("results") as? JsonArray
                val n = results?.size ?: goals(call, res).size.coerceAtLeast(1)
                val failed =
                    results?.count {
                        ToolJson.firstString(ToolJson.parseMaybeObject(it), listOf("status")) == "failed"
                    } ?: 0
                if (failed > 0) {
                    res.plural(R.plurals.tool_title_delegated_tasks_failed, n, n, failed)
                } else {
                    res.plural(R.plurals.tool_title_delegated_tasks, n)
                }
            }
        }
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.compactPreview(goals(call, res).firstOrNull() ?: "", 120)

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        // list action: one row per live subagent
        (result?.get("subagents") as? JsonArray)?.let { subs ->
            return subs
                .mapNotNull { el ->
                    val s = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                    val goal = ToolJson.compactPreview(ToolJson.firstString(s, listOf("goal")), 80)
                    val status = ToolJson.firstString(s, listOf("status"))
                    val secs = ToolJson.numberValue(s["running_seconds"])
                    val time = secs?.let { " · ${ToolJson.formatDurationSeconds(it)}" } ?: ""
                    "- [$status] $goal$time"
                }.joinToString("\n")
        }
        // joined spawn: per-task result rows
        val rows =
            (result?.get("results") as? JsonArray)?.mapNotNull { el ->
                val r = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val idx = ToolJson.intValue(r["task_index"]) ?: 0
                val ok = ToolJson.firstString(r, listOf("status")) != "failed"
                val marker = if (ok) "[x]" else "[!]"
                val goal = goals(call, res).getOrNull(idx) ?: res.get(R.string.tool_msg_task_n, idx + 1)
                val model = ToolJson.firstString(r, listOf("model"))
                val dur =
                    ToolJson
                        .numberValue(r["duration_seconds"])
                        ?.let { ToolJson.formatDurationSeconds(it) } ?: ""
                val meta = listOf(model, dur).filter { it.isNotEmpty() }.joinToString(" · ")
                val summary =
                    ToolJson.compactPreview(
                        ToolJson.firstString(r, listOf("summary", "error")),
                        160,
                    )
                buildString {
                    append("$marker ${ToolJson.compactPreview(goal, 80)}")
                    if (meta.isNotEmpty()) append("  ($meta)")
                    if (summary.isNotEmpty()) append("\n    $summary")
                }
            } ?: emptyList()
        if (rows.isNotEmpty()) return rows.joinToString("\n")
        // dispatched handle / steer / stop acks
        val note = ToolJson.firstString(result, listOf("note"))
        val goalsArr =
            (result?.get("goals") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.joinToString("\n") { "- ${ToolJson.compactPreview(it, 100)}" } ?: ""
        return listOf(goalsArr, note).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
