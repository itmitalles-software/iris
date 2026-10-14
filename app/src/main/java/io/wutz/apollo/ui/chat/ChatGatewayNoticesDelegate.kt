package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Severity of a gateway notice, from the `notification.show` `level` field. */
enum class GatewayNoticeLevel {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    ;

    companion object {
        fun fromWire(level: String): GatewayNoticeLevel =
            when (level.lowercase()) {
                "warn", "warning" -> WARNING
                "error" -> ERROR
                "success" -> SUCCESS
                else -> INFO
            }
    }
}

/** One out-of-band notice the chat shows above the transcript. */
data class GatewayNoticeUi(
    val key: String,
    val text: String,
    val level: GatewayNoticeLevel,
)

/**
 * Holds the gateway's keyed out-of-band notices (`notification.show` / `notification.clear`), for
 * example "Still starting the agent …" while a slow agent build delays a prompt, or "Could not
 * switch model …" after a queued model switch failed.
 *
 * A notice replaces the one with the same key. `ttl` notices expire on their own (the gateway's
 * `ttl_ms`, or [defaultTtlMs] when it sends none); `sticky` and `agent` notices stay until the
 * gateway clears them, the user dismisses them or the chat switches sessions. Only the newest
 * notice is shown.
 */
class ChatGatewayNoticesDelegate(
    private val scope: CoroutineScope,
    private val isCurrentSession: (String?) -> Boolean,
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
) {
    private val notices = LinkedHashMap<String, GatewayNoticeUi>()
    private val expiryJobs = HashMap<String, Job>()
    private var anonymousCounter = 0L

    private val _notice = MutableStateFlow<GatewayNoticeUi?>(null)

    /** The newest notice that is still active, or null. */
    val notice: StateFlow<GatewayNoticeUi?> = _notice.asStateFlow()

    fun onShow(event: WsEvent.NotificationShow) {
        if (!isCurrentSession(event.sessionId)) return
        val key = event.key ?: "notice-${++anonymousCounter}"
        expiryJobs.remove(key)?.cancel()
        // Re-insert so a replaced notice counts as the newest one.
        notices.remove(key)
        notices[key] = GatewayNoticeUi(key = key, text = event.text, level = GatewayNoticeLevel.fromWire(event.level))
        val ttl = event.ttlMs ?: if (event.kind == "ttl") defaultTtlMs else null
        if (ttl != null) {
            expiryJobs[key] =
                scope.launch {
                    delay(ttl)
                    expiryJobs.remove(key)
                    remove(key)
                }
        }
        publish()
    }

    fun onClear(event: WsEvent.NotificationClear) {
        if (!isCurrentSession(event.sessionId)) return
        expiryJobs.remove(event.key)?.cancel()
        remove(event.key)
    }

    /** The user closed the notice [key]. */
    fun dismiss(key: String) {
        expiryJobs.remove(key)?.cancel()
        remove(key)
    }

    /** A session switch: notices belong to the session that was left. */
    fun reset() {
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        notices.clear()
        publish()
    }

    private fun remove(key: String) {
        if (notices.remove(key) != null) publish()
    }

    private fun publish() {
        _notice.value = notices.values.lastOrNull()
    }

    companion object {
        /** Fallback lifetime of a `ttl` notice without `ttl_ms`. */
        const val DEFAULT_TTL_MS = 8_000L
    }
}
