package io.wutz.apollo.data.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/sessions/import` result (hermes_state_portability.import_sessions).
 * Existing session ids are skipped, not overwritten.
 */
@Serializable
data class SessionImportResponse(
    val ok: Boolean = false,
    val imported: Int = 0,
    val skipped: Int = 0,
    val detached: Int = 0,
    val importedIds: List<String> = emptyList(),
    val skippedIds: List<String> = emptyList(),
)
