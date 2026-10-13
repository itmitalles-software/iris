package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.CommandSummarizer
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import io.wutz.apollo.ui.chat.tool.ToolViewExtras
import io.wutz.apollo.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `terminal` and `execute_code`: shell-styled rows with a summarized command
 * title, first output line as subtitle, and split (or merged) streams for
 * the ANSI-aware expanded view. Exit code and the `$` command line are only
 * meaningful for `terminal`.
 */
internal object TerminalRenderer : ToolRenderer {
    private fun shellCommand(args: JsonObject?): String =
        ToolJson
            .firstString(args, listOf("command", "code"))
            .ifEmpty { ToolJson.firstString(args, listOf("context", "preview")) }
            .ifEmpty { ToolJson.contextValue(args) }

    private fun shellOutput(result: JsonObject?): String {
        val output = ToolJson.firstString(result, listOf("output", "stdout", "stderr"))
        val lines =
            (result?.get("lines") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.joinToString("\n")
                ?: ""

        return listOf(output, lines).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun commandTitle(
        call: ToolCall,
        runningVerb: Boolean,
        res: ToolText,
    ): String? {
        val command = shellCommand(call.args)
        val titleRes =
            when {
                call.name == "execute_code" && runningVerb -> R.string.tool_title_running_code
                call.name == "execute_code" -> R.string.tool_title_ran_code
                runningVerb -> R.string.tool_title_running
                else -> R.string.tool_title_ran
            }

        if (command.isEmpty()) {
            return null
        }

        return res.get(titleRes, ToolJson.compactPreview(CommandSummarizer.summarizeShellCommand(command, res), 160))
    }

    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String? = commandTitle(call, runningVerb = true, res)

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String? = commandTitle(call, runningVerb = false, res)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val firstMeaningfulLine =
            shellOutput(call.result)
                .split("\n")
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

        // A terminal row with no output shows its command in the title;
        // nothing more to add.
        return firstMeaningfulLine?.let { ToolJson.compactPreview(it, 160) } ?: ""
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val output = shellOutput(call.result)

        return when {
            output.isNotEmpty() -> output

            call.name == "execute_code" -> ToolJson.fallbackDetailText(call.rawArgs, call.rawResult, res)

            // A terminal row with no output already shows its command in the
            // title; the generic fallback would repeat it.
            else -> ""
        }
    }

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
        res: ToolText,
    ): ToolViewExtras {
        // The gateway's terminal_tool emits a single merged `output` string
        // (stdout+stderr, ANSI-stripped); older payloads carried separate
        // `stdout`/`stderr`. Prefer a real stream split, fall back to the
        // merged output as the single renderable stream.
        val stdout = ToolJson.firstString(call.result, listOf("stdout", "output"))
        val stderr = ToolJson.firstString(call.result, listOf("stderr"))
        val hasSplitStreams = stdout.isNotEmpty() || stderr.isNotEmpty()

        return ToolViewExtras(
            stdout = if (hasSplitStreams) stdout else null,
            stderr = if (hasSplitStreams) stderr else null,
            exitCode = if (call.name == "terminal") ToolJson.intValue(call.result?.get("exit_code")) else null,
            terminalCommand = if (call.name == "terminal") shellCommand(call.args) else null,
        )
    }
}

/** `read_terminal`: a line-window over a live terminal session. */
internal object ReadTerminalRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val total = ToolJson.intValue(result?.get("total_lines")) ?: 0
        val start = ToolJson.intValue(result?.get("start")) ?: 0
        val end = ToolJson.intValue(result?.get("end")) ?: 0
        val text = ToolJson.firstString(result, listOf("text"))

        if (text.isEmpty()) {
            return res.get(R.string.tool_msg_terminal_output)
        }

        val cursor = ToolJson.intValue(result?.get("cursor_row"))

        return if (cursor != null) {
            res.get(R.string.tool_msg_terminal_lines_cursor, start, end, total, cursor)
        } else {
            res.get(R.string.tool_msg_terminal_lines, start, end, total)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        val text = ToolJson.firstString(result, listOf("text"))
        val total = ToolJson.intValue(result?.get("total_lines")) ?: 0
        val start = ToolJson.intValue(result?.get("start")) ?: 0
        val end = ToolJson.intValue(result?.get("end")) ?: 0
        val cursor = ToolJson.intValue(result?.get("cursor_row"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
            } else if (text.isNotEmpty()) {
                append("━━━ Terminal ━━━     ($start:$end / $total)")
                if (cursor != null) append(" " + res.get(R.string.tool_msg_cursor_row, cursor))
                append("\n$text")
            } else {
                append(res.get(R.string.tool_msg_no_terminal_output))
            }
        }
    }
}
