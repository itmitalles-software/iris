package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import io.wutz.apollo.ui.chat.tool.ToolViewExtras
import io.wutz.apollo.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `cronjob`: job listings with schedules, or the mutated job's key fields. */
internal object CronjobRenderer : ToolRenderer {
    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String =
        res.get(R.string.tool_title_cron, ToolJson.firstString(call.args, listOf("action")).ifEmpty { "manage" })

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val jobs = call.result?.get("jobs") as? JsonArray

        if (jobs != null) {
            return if (jobs.isNotEmpty()) {
                res.plural(R.plurals.tool_msg_cron_job_count, jobs.size)
            } else {
                res.get(R.string.tool_msg_no_cron_jobs)
            }
        }

        val message = ToolJson.firstString(call.result, listOf("message"))
        if (message.isNotEmpty()) {
            return message
        }

        val action = ToolJson.firstString(call.args, listOf("action")).ifEmpty { "manage" }
        val name =
            ToolJson
                .firstString(call.result, listOf("name"))
                .ifEmpty { ToolJson.firstString(call.args, listOf("name", "job_id")) }
        val label = action.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        return if (name.isNotEmpty()) "$label $name" else res.get(R.string.tool_title_cron, action)
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val jobs = result?.get("jobs") as? JsonArray

        if (jobs != null) {
            if (jobs.isEmpty()) {
                return res.get(R.string.tool_msg_no_cron_jobs_scheduled)
            }

            return jobs
                .take(20)
                .mapNotNull { job ->
                    val row = ToolJson.parseMaybeObject(job) ?: return@mapNotNull null
                    val name =
                        ToolJson
                            .firstString(
                                row,
                                listOf("name", "id"),
                            ).ifEmpty { res.get(R.string.tool_msg_job) }
                    val sched = ToolJson.firstString(row, listOf("schedule_display", "schedule"))
                    if (sched.isNotEmpty()) "- $name · $sched" else "- $name"
                }.joinToString("\n")
        }

        val rows =
            listOf(
                res.get(R.string.tool_msg_cron_schedule) to ToolJson.firstString(result, listOf("schedule")),
                res.get(R.string.tool_msg_cron_repeat) to ToolJson.firstString(result, listOf("repeat")),
                res.get(R.string.tool_msg_cron_delivery) to ToolJson.firstString(result, listOf("deliver")),
                res.get(R.string.tool_msg_cron_next_run) to ToolJson.firstString(result, listOf("next_run_at")),
            ).filter { it.second.isNotEmpty() }

        return if (rows.isNotEmpty()) {
            rows.joinToString(
                "\n",
            ) { (k, v) -> "$k: $v" }
        } else {
            ToolJson.fallbackDetailText(call.args, result, res)
        }
    }
}

/** `todo`: status-count subtitle plus a checklist with [x]/[>]/[~] markers. */
internal object TodoRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val summary = call.result?.get("summary") as? JsonObject ?: return ""
        val total = ToolJson.intValue(summary["total"]) ?: 0
        val pending = ToolJson.intValue(summary["pending"]) ?: 0
        val inProgress = ToolJson.intValue(summary["in_progress"]) ?: 0
        val completed = ToolJson.intValue(summary["completed"]) ?: 0
        val cancelled = ToolJson.intValue(summary["cancelled"]) ?: 0

        val parts =
            listOf(
                pending.takeIf { it > 0 }?.let { res.get(R.string.tool_msg_todo_pending, it) },
                inProgress.takeIf { it > 0 }?.let { res.get(R.string.tool_msg_todo_in_progress, it) },
                completed.takeIf { it > 0 }?.let { res.get(R.string.tool_msg_todo_completed, it) },
                cancelled.takeIf { it > 0 }?.let { res.get(R.string.tool_msg_todo_cancelled, it) },
            ).filterNotNull()

        return if (parts.isEmpty()) {
            res.plural(R.plurals.tool_msg_item_count, 0)
        } else {
            "${res.plural(R.plurals.tool_msg_item_count, total)} (${parts.joinToString(", ")})"
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val todos = call.result?.get("todos") as? JsonArray ?: return ""

        return todos
            .mapNotNull { el ->
                val item = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val id = ToolJson.firstString(item, listOf("id"))
                val content = ToolJson.firstString(item, listOf("content"))
                val status = ToolJson.firstString(item, listOf("status")).ifEmpty { "pending" }
                val parent = ToolJson.firstString(item, listOf("parent"))
                val marker =
                    when (status) {
                        "completed" -> "[x]"
                        "in_progress" -> "[>]"
                        "cancelled" -> "[~]"
                        else -> "[ ]"
                    }
                val indent = if (parent.isNotEmpty()) "  ↳ " else ""
                "$indent$marker $id. $content"
            }.joinToString("\n")
    }
}

/** `session_search`: mode-specific subtitles and rich session/message lists. */
internal object SessionSearchRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val mode = ToolJson.firstString(result, listOf("mode"))
        val query = ToolJson.firstString(result, listOf("query"))
        val count = ToolJson.intValue(result?.get("count"))
        val msgCount = ToolJson.intValue(result?.get("message_count"))
        val messages = result?.get("messages") as? JsonArray
        val truncated = ToolJson.boolTrue(result?.get("truncated"))

        return when (mode) {
            "discover" -> {
                res.plural(R.plurals.tool_msg_session_count, count ?: 0) +
                    (query.takeIf { it.isNotEmpty() }?.let { ": ${it.take(60)}" } ?: "")
            }

            "scroll" -> {
                res.plural(R.plurals.tool_msg_messages_scroll, messages?.size ?: 0)
            }

            "read" -> {
                res.plural(R.plurals.tool_msg_message_count, msgCount ?: messages?.size ?: 0) +
                    if (truncated) " " + res.get(R.string.tool_msg_truncated_suffix) else ""
            }

            "browse" -> {
                res.plural(R.plurals.tool_msg_recent_sessions, count ?: 0)
            }

            else -> {
                "session_search"
            }
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val results = result?.get("results") as? JsonArray
        val messages = result?.get("messages") as? JsonArray

        val formattedResults =
            results
                ?.mapIndexedNotNull { idx, el ->
                    val item = ToolJson.parseMaybeObject(el) ?: return@mapIndexedNotNull null
                    val whenField = ToolJson.firstString(item, listOf("when", "started_at"))
                    val source = ToolJson.firstString(item, listOf("source"))
                    val title = ToolJson.firstString(item, listOf("title"))
                    val snippet =
                        ToolJson
                            .firstString(item, listOf("snippet"))
                            .ifEmpty { ToolJson.firstString(item, listOf("preview")) }
                    val model = ToolJson.firstString(item, listOf("model"))
                    val matchedRole = ToolJson.firstString(item, listOf("matched_role"))
                    val sessionMsgCount = ToolJson.intValue(item["message_count"])

                    val header = whenField.takeIf { it.isNotEmpty() }?.let { "📅 $it" } ?: ""
                    val sourceTag = source.takeIf { it.isNotEmpty() }?.let { "[$it]" } ?: ""
                    val titleLine = title.takeIf { it.isNotEmpty() }?.let { "\n     📄 $it" } ?: ""
                    val modelLine = model.takeIf { it.isNotEmpty() }?.let { "\n     🤖 $it" } ?: ""
                    val matchedLine =
                        matchedRole.takeIf { it.isNotEmpty() }?.let {
                            "\n     " + res.get(R.string.tool_msg_matched_role, it)
                        } ?: ""
                    val countLine =
                        sessionMsgCount?.let { "\n     " + res.plural(R.plurals.tool_msg_msgs_count, it) } ?: ""
                    val snippetLine =
                        snippet.takeIf { it.isNotEmpty() }?.let { "\n     ┃ ${it.take(200).replace("\n", " ")}" } ?: ""

                    "━━━ #${idx + 1}  $header$sourceTag$titleLine$modelLine$matchedLine$countLine$snippetLine"
                }?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }

        val anchorId = ToolJson.intValue(result?.get("around_message_id"))

        val formattedMessages =
            messages
                ?.mapNotNull { el ->
                    val item = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                    val msgId = ToolJson.intValue(item["id"])
                    val role = ToolJson.firstString(item, listOf("role")).ifEmpty { "?" }
                    val content = ToolJson.firstString(item, listOf("content"))
                    val toolName = ToolJson.firstString(item, listOf("tool_name"))

                    val roleEmoji =
                        when (role) {
                            "user" -> "👤"
                            "assistant" -> "🤖"
                            "tool" -> "🔧"
                            else -> "❓"
                        }
                    val namePart = if (toolName.isNotEmpty()) " ($toolName)" else ""
                    val anchor = if (anchorId != null && msgId == anchorId) "  ⬅️" else ""
                    val cleanContent = content.take(300).replace("\n", " ")

                    "[$msgId] $roleEmoji $role$namePart$anchor\n     $cleanContent"
                }?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }

        return formattedResults ?: formattedMessages ?: ""
    }
}

/** `process`: background session listings and lifecycle acks. */
internal object ProcessRenderer : ToolRenderer {
    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
        res: ToolText,
    ): ToolViewExtras {
        if (status == ToolViewStatus.RUNNING) return ToolViewExtras.NONE
        val count = (call.result?.get("output_cut") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        return ToolViewExtras(outputCut = count?.takeIf { it > 0 })
    }

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val action = ToolJson.firstString(call.args, listOf("action"))
        val procId = ToolJson.firstString(call.args, listOf("session_id"))

        return if (procId.isNotEmpty()) "$action: $procId" else action
    }

    override fun pendingDetail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val action = ToolJson.firstString(call.args, listOf("action"))
        return res.get(R.string.tool_msg_in_progress, action.ifEmpty { res.get(R.string.tool_msg_process_operation) })
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val processes = result?.get("processes") as? JsonArray
        if (processes != null) {
            return processes
                .mapNotNull { el ->
                    val p = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                    val pid =
                        ToolJson
                            .firstString(p, listOf("session_id"))
                            .ifEmpty { ToolJson.firstString(p, listOf("id")) }
                    val pStatus = ToolJson.firstString(p, listOf("status"))
                    val cmd = ToolJson.firstString(p, listOf("command"))
                    val running =
                        (p["running"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" }
                    val parts = mutableListOf("📌 $pid${cmd.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}")
                    if (pStatus.isNotEmpty()) parts += "     " + res.get(R.string.tool_msg_status, pStatus)
                    if (running != null) parts += "     " + res.get(R.string.tool_msg_running_flag, running.toString())
                    parts.joinToString("\n")
                }.joinToString("\n\n")
        }

        val output = ToolJson.firstString(result, listOf("output", "output_preview"))
        if (output.isNotEmpty()) {
            val status = ToolJson.firstString(result, listOf("status"))
            return "${status.takeIf { it.isNotEmpty() }?.let {
                res.get(
                    R.string.tool_msg_status,
                    it,
                ) + "\n"
            } ?: ""}$output"
        }

        val status = ToolJson.firstString(result, listOf("status"))
        if (status.isNotEmpty()) {
            return "✅ $status"
        }

        val action = ToolJson.firstString(call.args, listOf("action"))
        return res.get(R.string.tool_msg_action_done, action.ifEmpty { res.get(R.string.tool_msg_done) })
    }
}
