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

/** Notification extra naming the server request a background notification announces. */
internal const val EXTRA_SERVER_REQUEST_ID = "apollo_server_request_id"

/**
 * Server requests the notification service has seen. The gateway re-sends still-open requests
 * (`open_requests`, `replayed = true`) on every resume, so only a request's first sighting alerts;
 * a request the gateway withdrew is no longer open and must not be announced late.
 */
internal class ServerRequestSightings(
    private val capacity: Int = 64,
) {
    /** Request id → still open. Oldest ids are forgotten beyond [capacity]. */
    private val requests = LinkedHashMap<String, Boolean>()

    /** Records [id]; true on its first sighting, false for a replay of a known request. */
    @Synchronized
    fun onRequest(id: String): Boolean {
        if (requests.containsKey(id)) return false
        remember(id, open = true)
        return true
    }

    @Synchronized
    fun onCancelled(id: String) = remember(id, open = false)

    @Synchronized
    fun isOpen(id: String): Boolean = requests[id] == true

    /** True only for a request seen and then cancelled; unknown ids are not withdrawn. */
    @Synchronized
    fun isWithdrawn(id: String): Boolean = requests[id] == false

    private fun remember(
        id: String,
        open: Boolean,
    ) {
        requests[id] = open
        while (requests.size > capacity) requests.remove(requests.keys.first())
    }
}
