package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** `project_list`: project roster with the active project starred. */
internal object ProjectListRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val projects = (call.result?.get("projects") as? JsonArray)?.size
        val active = ToolJson.firstString(call.result, listOf("active_id"))

        return if (projects != null) {
            res.plural(R.plurals.tool_msg_project_count, projects) +
                (active.takeIf { it.isNotEmpty() }?.let { " " + res.get(R.string.tool_msg_one_active) } ?: "")
        } else {
            res.get(R.string.tool_msg_projects)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String =
        (call.result?.get("projects") as? JsonArray)
            ?.mapNotNull { el ->
                val p = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val name = ToolJson.firstString(p, listOf("name"))
                val slug = ToolJson.firstString(p, listOf("slug"))
                val path = ToolJson.firstString(p, listOf("primary_path"))
                val isActive =
                    (p["active"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
                val lines = mutableListOf("${if (isActive) "⭐ " else "📁 "}$name")
                if (slug.isNotEmpty()) lines += "     🏷️ $slug"
                if (path.isNotEmpty()) lines += "     📍 $path"
                if (isActive) lines += "     " + res.get(R.string.tool_msg_active_project)
                lines.joinToString("\n")
            }?.joinToString("\n\n")
            ?: ""
}

/** `project_create` / `project_switch`: mutation ack with name/slug/path. */
internal object ProjectMutateRenderer : ToolRenderer {
    private fun isCreate(call: ToolCall): Boolean = call.name == "project_create"

    private fun actionLabel(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(if (isCreate(call)) R.string.tool_msg_project_created else R.string.tool_msg_project_switched)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val name = ToolJson.firstString(call.result, listOf("name"))
        return when {
            name.isEmpty() -> actionLabel(call, res)
            isCreate(call) -> res.get(R.string.tool_msg_project_created_named, name)
            else -> res.get(R.string.tool_msg_project_switched_named, name)
        }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val name = ToolJson.firstString(call.result, listOf("name"))
        val slug = ToolJson.firstString(call.result, listOf("slug"))
        val path = ToolJson.firstString(call.result, listOf("primary_path"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
            } else {
                append("✅ ${actionLabel(call, res)}")
                if (name.isNotEmpty()) append(": $name")
                if (slug.isNotEmpty()) append("\n   🏷️ $slug")
                if (path.isNotEmpty()) append("\n   📍 $path")
            }
        }
    }
}
