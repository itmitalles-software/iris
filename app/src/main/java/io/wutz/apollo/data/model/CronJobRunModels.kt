package io.wutz.apollo.data.model

import kotlinx.serialization.Serializable

/**
 * One execution of a cron job from `GET /api/cron/jobs/{id}/runs`
 * (hermes_cli/web_routers/cron.py). Agent runs are ordinary sessions
 * (`source = "cron"`, id `cron_<job>_<timestamp>`); script-only fires have no
 * session and come from the job's output documents (`source = "cron_output"`,
 * id `cron_output:...`). Field names are snake_case on the wire.
 */
@Serializable
data class CronJobRun(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val source: String? = null,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val lastActive: Double? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val messageCount: Int? = null,
    val model: String? = null,
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val isActive: Boolean? = null,
) {
    /** Only agent runs have a session that can be opened in the chat. */
    val hasSession: Boolean get() = source == SOURCE_SESSION && id.isNotBlank()

    companion object {
        const val SOURCE_SESSION = "cron"
    }
}

@Serializable
data class CronJobRunsResponse(
    val runs: List<CronJobRun> = emptyList(),
    val limit: Int? = null,
)
