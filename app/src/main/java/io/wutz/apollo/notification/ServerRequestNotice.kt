package io.wutz.apollo.notification

/** What a background notification says about a pending server request. */
internal sealed interface ServerRequestNotice {
    /** Show this server-provided text (approval description/command, secret prompt). */
    data class Text(
        val text: String,
    ) : ServerRequestNotice

    data object ClarificationNeeded : ServerRequestNotice

    data object InputNeeded : ServerRequestNotice
}

/**
 * Maps a JSON-RPC server request (`WsEvent.ServerRequest`, sent because the client advertises
 * `server_requests`) to its background notification. Approvals, clarifications and the other
 * prompts arrive this way rather than as legacy `*.request` events, so the notification service
 * must handle both shapes. Returns null for methods that need no notification.
 */
internal fun serverRequestNotice(
    method: String,
    params: Map<String, Any?>,
): ServerRequestNotice? {
    fun param(key: String) = (params[key] as? String)?.takeIf { it.isNotBlank() }
    return when (method) {
        "approval" -> {
            (param("description") ?: param("command"))
                ?.let { ServerRequestNotice.Text(it.take(100)) }
                ?: ServerRequestNotice.InputNeeded
        }

        "clarify" -> {
            ServerRequestNotice.ClarificationNeeded
        }

        "secret" -> {
            (param("prompt") ?: param("env_var"))
                ?.let { ServerRequestNotice.Text(it.take(100)) }
                ?: ServerRequestNotice.InputNeeded
        }

        "sudo", "vault.code", "vault.save_login", "vault.unlock_prompt" -> {
            ServerRequestNotice.InputNeeded
        }

        else -> {
            null
        }
    }
}
