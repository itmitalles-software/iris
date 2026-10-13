package io.wutz.apollo.ui.chat

import java.util.UUID

/** Session-scoped presentation, never a send receipt or a persisted transcript row. */
data class ReplyFailure(
    val details: String,
    val id: String = UUID.randomUUID().toString(),
)

/** Stable identity for the one transient failed-reply projection in a session lifecycle. */
data class ReplyFailureProjection(
    val messageId: String?,
    val failureId: String,
    val completionId: String? = null,
)

/**
 * English placeholder for a failure without error text. It stays English inside [ReplyFailure.details]
 * (copied into bug reports and matched here); [localizeReplyFailureDetails] translates it for display.
 */
internal const val UNKNOWN_GATEWAY_ERROR = "Unknown gateway error"

/** Display form of failure details: the [UNKNOWN_GATEWAY_ERROR] line is translated, everything else stays raw. */
internal fun localizeReplyFailureDetails(
    details: String,
    unknownGatewayError: String,
): String = details.split("\n").joinToString("\n") { if (it == UNKNOWN_GATEWAY_ERROR) unknownGatewayError else it }

/** Only a terminal turn failure is a failed reply; RPC/transport errors are separate. */
internal fun replyFailureFromPayload(
    payload: Map<String, Any?>?,
    fallback: String,
): ReplyFailure? {
    if (payload?.get("status") != "error") return null
    val surface = payload["error_surface"] as? Map<*, *>
    val details =
        buildList {
            for (key in listOf("layer", "code", "provider", "model")) {
                (surface?.get(key) as? String)?.takeIf { it.isNotBlank() }?.let { add("$key: $it") }
            }
            val error = (payload["error"] as? String)?.takeIf { it.isNotBlank() }
            add(error ?: if (payload["partial"] == true) UNKNOWN_GATEWAY_ERROR else fallback)
        }.joinToString("\n")
    return ReplyFailure(sanitizeReplyErrorDetails(details))
}

/** Best-effort scrubbing before display/copy/share; never includes a transcript or request payload. */
internal fun sanitizeReplyErrorDetails(raw: String?): String {
    if (raw.isNullOrBlank()) return UNKNOWN_GATEWAY_ERROR
    var safe = raw.take(8_192)
    safe = PRIVATE_KEY.replace(safe, "[REDACTED]")
    safe = HEADER.replace(safe) { "${it.groupValues[1]}[REDACTED]" }
    safe = URL.replace(safe, "[REDACTED URL]")
    safe = SECRET_ASSIGNMENT.replace(safe) { "${it.groupValues[1]}[REDACTED]" }
    safe = BEARER.replace(safe, "Bearer [REDACTED]")
    safe = PROVIDER_KEY.replace(safe, "[REDACTED]")
    return safe.take(8_192)
}

private val PRIVATE_KEY = Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)")
private val HEADER = Regex("(?im)^([ \\t]*(?:authorization|proxy-authorization|set-cookie|cookie)\\s*:\\s*)[^\\r\\n]*")
private val URL = Regex("(?i)(?:https?|wss?)://[^\\s<>\\\"']+")
private val SECRET_ASSIGNMENT =
    Regex(
        "(?i)((?<![\\w-])[\\\"']?[\\w-]{0,128}" +
            "(?:token|secret|password|passwd|api[_-]?key|authorization|cookie)[\\w-]{0,128}" +
            "[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"?|'[^']*'?|[^\\r\\n,;}]+)",
    )
private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
private val PROVIDER_KEY = Regex("\\b(?:sk-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)\\b")
