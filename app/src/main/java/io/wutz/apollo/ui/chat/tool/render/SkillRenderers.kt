package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `skills_list`: count-with-category subtitle and a name/description list. */
internal object SkillsListRenderer : ToolRenderer {
    private fun skills(call: ToolCall): JsonArray? =
        (call.result?.get("skills") as? JsonArray)
            ?: (call.result?.get("results") as? JsonArray)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val category = ToolJson.firstString(call.args, listOf("category"))
        val count = skills(call)?.size ?: 0

        return if (count > 0) {
            res.plural(R.plurals.tool_msg_count_skill, count) +
                (category.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        } else {
            res.get(R.string.tool_msg_no_skills_found)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        skills(call)
            ?.mapNotNull { el ->
                val s = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val name = ToolJson.firstString(s, listOf("name"))
                val desc = ToolJson.firstString(s, listOf("description"))
                val cat = ToolJson.firstString(s, listOf("category"))
                val lines = mutableListOf("📌 $name")
                if (desc.isNotEmpty()) lines += "     $desc"
                if (cat.isNotEmpty()) lines += "     [$cat]"
                lines.joinToString("\n")
            }?.joinToString("\n\n")
            ?: res.get(R.string.tool_msg_no_skills_found)
}

/** `skill_view`: content preview capped at 500 chars plus linked files. */
internal object SkillViewRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.firstString(call.args, listOf("name"))

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val content = ToolJson.firstString(call.result, listOf("content"))
        val linkedFiles = call.result?.get("linked_files") as? JsonObject
        val contentPreview =
            if (content.length > 500) {
                content.take(500) + "\n" + res.get(R.string.tool_msg_more_chars, content.length - 500)
            } else {
                content
            }
        val filesInfo =
            linkedFiles
                ?.entries
                ?.joinToString("\n") { (k, v) ->
                    val paths =
                        when (v) {
                            is JsonArray -> {
                                v.joinToString(
                                    ", ",
                                ) { (it as? JsonPrimitive)?.content ?: it.toString() }
                            }

                            is JsonPrimitive -> {
                                v.content
                            }

                            else -> {
                                v.toString()
                            }
                        }
                    "     📎 $k: $paths"
                }

        return buildString {
            if (contentPreview.isNotEmpty()) append(contentPreview)
            if (filesInfo != null) {
                if (contentPreview.isNotEmpty()) append("\n\n")
                append(res.get(R.string.tool_msg_linked_files_header) + "\n$filesInfo")
            }
            if (content.isEmpty() && linkedFiles == null) append(res.get(R.string.tool_msg_no_content))
        }
    }
}

/** `skill_manage`: action ack with the skill name. */
internal object SkillManageRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val action = ToolJson.firstString(call.args, listOf("action"))
        val name = ToolJson.firstString(call.args, listOf("name"))

        return if (name.isNotEmpty()) {
            res.get(R.string.tool_msg_skill_action_named, action, name)
        } else {
            res.get(R.string.tool_msg_skill_action, action)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val msg = ToolJson.firstString(call.result, listOf("message"))
        val failed = ToolJson.boolFalse(call.result?.get("success"))
        val succeeded = ToolJson.boolTrue(call.result?.get("success"))

        return when {
            failed -> "❌ ${msg.ifEmpty { res.get(R.string.tool_msg_skill_failed) }}"
            succeeded -> "✅ ${msg.ifEmpty { res.get(R.string.tool_msg_skill_done) }}"
            else -> "❌ ${msg.ifEmpty { res.get(R.string.tool_msg_skill_failed) }}"
        }
    }
}

/** `tool_search`: on-demand tool discovery matches. */
internal object ToolSearchRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val query =
            ToolJson
                .firstString(call.args, listOf("query"))
                .ifEmpty { (call.args?.get("queries") as? JsonArray)?.joinToString(", ") { text(it) } ?: "" }
        val matches =
            (call.result?.get("results") as? JsonArray)?.sumOf { row ->
                (ToolJson.parseMaybeObject(row)?.get("matches") as? JsonArray)?.size ?: 0
            } ?: (call.result?.get("matches") as? JsonArray)?.size

        return if (matches != null) res.plural(R.plurals.tool_msg_query_matches, matches, query, matches) else query
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val tools = call.result?.get("tools") as? JsonObject
        val matches =
            (call.result?.get("results") as? JsonArray)
                ?.flatMap { row -> (ToolJson.parseMaybeObject(row)?.get("matches") as? JsonArray).orEmpty() }
                ?: (call.result?.get("matches") as? JsonArray).orEmpty()
        return matches
            .mapNotNull { match ->
                val item = ToolJson.parseMaybeObject(match)
                val name = item?.let { ToolJson.firstString(it, listOf("name")) } ?: text(match)
                if (name.isEmpty()) return@mapNotNull null
                val metadata = tools?.get(name) as? JsonObject
                val desc =
                    ToolJson
                        .firstString(metadata, listOf("description"))
                        .ifEmpty { ToolJson.firstString(item, listOf("description")) }
                val source = ToolJson.firstString(metadata, listOf("source_name"))
                val required = (metadata?.get("required") as? JsonArray)?.joinToString(", ") { text(it) }.orEmpty()
                buildString {
                    append("🔧 $name")
                    if (source.isNotEmpty()) append(" · $source")
                    if (desc.isNotEmpty()) append("\n     $desc")
                    if (required.isNotEmpty()) append("\n     " + res.get(R.string.tool_msg_required, required))
                }
            }.distinct()
            .joinToString("\n\n")
            .ifEmpty { res.get(R.string.tool_msg_no_matching_tools) }
    }
}

/** `tool_describe`: readable schema summaries instead of a collapsed JSON object. */
internal object ToolDescribeRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val names = (call.args?.get("names") as? JsonArray)?.joinToString(", ") { text(it) }.orEmpty()
        val count = (call.result?.get("tools") as? JsonObject)?.size
        return if (count != null) res.plural(R.plurals.tool_msg_names_tools, count, names, count) else names
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        (call.result?.get("tools") as? JsonObject)
            ?.entries
            ?.joinToString("\n\n") { (name, value) ->
                val tool = ToolJson.parseMaybeObject(value)
                val desc = ToolJson.firstString(tool, listOf("description"))
                val schema = tool?.get("parameters") as? JsonObject
                val properties = schema?.get("properties") as? JsonObject
                val required = (schema?.get("required") as? JsonArray)?.map(::text).orEmpty().toSet()
                buildString {
                    append("🔧 $name")
                    if (desc.isNotEmpty()) append("\n     $desc")
                    if (!properties.isNullOrEmpty()) {
                        val parameters =
                            properties.entries.joinToString("; ") { (paramName, value) ->
                                val param = ToolJson.parseMaybeObject(value)
                                val type = ToolJson.firstString(param, listOf("type"))
                                val help = ToolJson.firstString(param, listOf("description"))
                                val default = param?.get("default")
                                buildString {
                                    append(paramName)
                                    if (type.isNotEmpty()) append(" ($type)")
                                    if (paramName in required) append(" *")
                                    if (help.isNotEmpty()) append(" — $help")
                                    if (default !=
                                        null
                                    ) {
                                        append(" " + res.get(R.string.tool_msg_default_value, text(default)))
                                    }
                                }
                            }
                        append("\n     " + res.get(R.string.tool_msg_parameters, parameters))
                    } else if (required.isNotEmpty()) {
                        append("\n     " + res.get(R.string.tool_msg_required, required.joinToString(", ")))
                    }
                }
            }?.ifEmpty { null } ?: res.get(R.string.tool_msg_no_tool_descriptions)
}

private fun text(value: kotlinx.serialization.json.JsonElement): String =
    (value as? JsonPrimitive)?.content ?: value.toString()
