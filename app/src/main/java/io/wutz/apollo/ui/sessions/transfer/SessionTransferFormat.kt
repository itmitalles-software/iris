package io.wutz.apollo.ui.sessions.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Localized words the Markdown transcript needs; resolved by the caller from string resources. */
data class TranscriptLabels(
    val untitled: String,
    val user: String,
    val assistant: String,
    val image: String,
    val truncated: String,
)

/** Why a picked file cannot be imported. */
sealed interface ImportPayloadError {
    data object NotJson : ImportPayloadError

    data object NoSessions : ImportPayloadError
}

/**
 * Formats of Hermes' session export (`GET /api/sessions/{id}/export`: one session object with its
 * `messages`) and import (`POST /api/sessions/import`: `{"sessions": [...], "profile": ...}`).
 */
object SessionTransferFormat {
    /** Share-sheet text stays far below the ~1 MB binder transaction limit. */
    const val MAX_SHARE_CHARS = 100_000

    /** The server refuses larger import bodies (`_SESSION_IMPORT_MAX_BYTES`). */
    const val MAX_IMPORT_BYTES = 25L * 1024 * 1024

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Some editors save JSON with a UTF-8 byte order mark. */
    private val BYTE_ORDER_MARK = Char(0xFEFF)

    /**
     * Renders an export as Markdown: title, start time and model, then the visible user and
     * assistant turns. Tool rows, system prompts, rewound rows, compaction summaries and model-only
     * rows are left out, so the text matches what the chat shows.
     */
    fun toMarkdown(
        export: JsonObject,
        labels: TranscriptLabels,
        zone: ZoneId = ZoneId.systemDefault(),
        dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE,
    ): String {
        val title = export.string("title")?.takeIf { it.isNotBlank() } ?: labels.untitled
        val started =
            export.number("started_at")?.takeIf { it > 0.0 }?.let { seconds ->
                dateFormatter.format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone))
            }
        val meta = listOfNotNull(started, export.string("model")?.takeIf { it.isNotBlank() })
        val out = StringBuilder()
        out.append("# ").append(title.trim()).append('\n')
        if (meta.isNotEmpty()) {
            out
                .append('\n')
                .append('_')
                .append(meta.joinToString(" · "))
                .append("_\n")
        }
        val messages = export["messages"] as? JsonArray ?: JsonArray(emptyList())
        for (element in messages) {
            val message = element as? JsonObject ?: continue
            if (!isVisible(message)) continue
            val speaker =
                when (message.string("role")) {
                    "user" -> labels.user
                    "assistant" -> labels.assistant
                    else -> continue
                }
            val text = contentText(message["content"], labels.image).trim()
            if (text.isEmpty()) continue
            val block = "\n**$speaker:**\n\n$text\n"
            if (out.length + block.length > MAX_SHARE_CHARS) {
                out.append("\n_").append(labels.truncated).append("_\n")
                break
            }
            out.append(block)
        }
        return out.toString()
    }

    private fun isVisible(message: JsonObject): Boolean {
        // Display history = live rows plus compaction-archived ones; never rewound rows.
        val active = message.flag("active") ?: true
        val compacted = message.flag("compacted") ?: false
        if (!active && !compacted) return false
        if (message.flag("_compressed_summary") == true) return false
        val metadata = message["display_metadata"] as? JsonObject
        return metadata?.let { (it["model_only"] as? JsonPrimitive)?.let(::primitiveFlag) } != true
    }

    private fun contentText(
        content: JsonElement?,
        imageLabel: String,
    ): String =
        when (content) {
            null, JsonNull -> {
                ""
            }

            is JsonPrimitive -> {
                content.contentOrNull.orEmpty()
            }

            is JsonArray -> {
                content
                    .mapNotNull { part ->
                        val obj = part as? JsonObject ?: return@mapNotNull (part as? JsonPrimitive)?.contentOrNull
                        when (obj.string("type")) {
                            "text", "input_text", "output_text" -> obj.string("text")
                            "image_url", "image", "input_image" -> "[$imageLabel]"
                            else -> obj.string("text")
                        }
                    }.joinToString("\n\n")
            }

            is JsonObject -> {
                content.string("text").orEmpty()
            }
        }

    /**
     * Turns a picked file into the `sessions` list of an import: a single export object, a list of
     * sessions, an object with a `sessions` list, or JSON Lines with one session per line.
     */
    fun parseImportSessions(text: String): Result<JsonArray> {
        val trimmed = text.trim().removePrefix(BYTE_ORDER_MARK.toString())
        if (trimmed.isEmpty()) return failure(ImportPayloadError.NoSessions)
        val element = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrNull()
        val sessions: List<JsonElement> =
            when {
                element is JsonArray -> {
                    element
                }

                element is JsonObject && element["sessions"] is JsonArray -> {
                    element["sessions"] as JsonArray
                }

                element is JsonObject -> {
                    listOf(element)
                }

                else -> {
                    val lines = trimmed.lines().map(String::trim).filter(String::isNotEmpty)
                    val parsed = lines.map { line -> runCatching { lenientJson.parseToJsonElement(line) }.getOrNull() }
                    if (parsed.isEmpty() || parsed.any { it !is JsonObject }) {
                        return failure(ImportPayloadError.NotJson)
                    }
                    parsed.filterNotNull()
                }
            }
        val valid = sessions.filter { it is JsonObject && !it.string("id").isNullOrBlank() }
        if (valid.isEmpty()) return failure(ImportPayloadError.NoSessions)
        return Result.success(JsonArray(valid))
    }

    /** The import body. The server reads the target profile from the body, not the query. */
    fun importBody(
        sessions: JsonArray,
        profile: String?,
    ): JsonObject =
        buildJsonObject {
            put("sessions", sessions)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", JsonPrimitive(it)) }
        }

    /** A file name for the export: the title reduced to safe characters, else the session id. */
    fun exportFileName(
        title: String?,
        sessionId: String,
    ): String {
        val base =
            title
                ?.trim()
                ?.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
                ?.trim('-', '.')
                ?.take(60)
                ?.takeIf { it.isNotEmpty() }
                ?: sessionId.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(60)
        return "iris-$base.json"
    }

    private fun failure(error: ImportPayloadError): Result<JsonArray> = Result.failure(ImportPayloadException(error))

    private fun JsonElement.string(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let(::primitiveFlag)

    /** SQLite rows carry 0/1 integers; exports from other tools may carry booleans. */
    private fun primitiveFlag(value: JsonPrimitive): Boolean? = value.booleanOrNull ?: value.intOrNull?.let { it != 0 }
}

class ImportPayloadException(
    val error: ImportPayloadError,
) : IllegalArgumentException(error.toString())
