package io.wutz.apollo.ui.chat.tool.render

import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.tool.ToolCall
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.ToolRenderer
import io.wutz.apollo.ui.chat.tool.ToolText

/** `image_generate`: generated-image URL with the prompt in the detail. */
internal object ImageGenerateRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val imageUrl =
            ToolJson
                .firstString(call.result, listOf("image"))
                .ifEmpty { ToolJson.firstString(call.args, listOf("image_url")) }
        val modality = ToolJson.firstString(call.result, listOf("modality"))

        return listOf(imageUrl.take(60), modality.takeIf { it.isNotEmpty() }?.let { "($it)" })
            .filterNotNull()
            .joinToString(" ")
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val imageUrl = ToolJson.firstString(call.result, listOf("image"))
        val prompt = ToolJson.firstString(call.args, listOf("prompt"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
                if (imageUrl.isNotEmpty()) append("\n🔗 $imageUrl")
            } else if (imageUrl.isNotEmpty()) {
                append("🖼️ ")
                if (prompt.isNotEmpty()) append("$prompt\n")
                append("\n🔗 $imageUrl")
            } else {
                append(res.get(R.string.tool_msg_generated))
            }
        }
    }
}

/** `vision_analyze`: the analyzed image URL and the model's description. */
internal object VisionAnalyzeRenderer : ToolRenderer {
    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String = ToolJson.firstString(call.args, listOf("image_url")).take(60)

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val error = ToolJson.firstString(call.result, listOf("error"))
        val description = ToolJson.firstString(call.result, listOf("description"))
        val content = ToolJson.firstString(call.result, listOf("content"))

        return when {
            error.isNotEmpty() -> "❌ $error"
            description.isNotEmpty() -> description
            content.isNotEmpty() -> content
            else -> res.get(R.string.tool_msg_no_description)
        }
    }
}

/** `text_to_speech`: audio synthesis. Success payload carries file_path(s), provider, chunk_count. */
internal object TextToSpeechRenderer : ToolRenderer {
    override fun pendingTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_generating_speech)

    override fun doneTitle(
        call: ToolCall,
        res: ToolText,
    ): String = res.get(R.string.tool_title_generated_speech)

    override fun subtitle(
        call: ToolCall,
        res: ToolText,
    ): String {
        val provider = ToolJson.firstString(call.result, listOf("provider"))
        val chunks = ToolJson.intValue(call.result?.get("chunk_count"))
        val parts =
            listOfNotNull(
                provider.takeIf { it.isNotEmpty() },
                chunks?.takeIf { it > 1 }?.let { res.plural(R.plurals.tool_msg_chunk_count, it) },
            )
        return parts
            .joinToString(" · ")
            .ifEmpty { ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("text")), 120) }
    }

    override fun detail(
        call: ToolCall,
        res: ToolText,
    ): String {
        val text = ToolJson.compactPreview(ToolJson.firstString(call.args, listOf("text")), 400)
        val path = ToolJson.firstString(call.result, listOf("file_path", "output_path"))
        return listOfNotNull(
            text.takeIf { it.isNotEmpty() }?.let { "“$it”" },
            path.takeIf { it.isNotEmpty() }?.let { res.get(R.string.tool_msg_saved_to, it) },
        ).joinToString("\n\n")
    }
}
