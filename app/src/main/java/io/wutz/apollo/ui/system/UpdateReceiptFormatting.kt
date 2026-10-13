package io.wutz.apollo.ui.system

import io.wutz.apollo.R
import io.wutz.apollo.data.model.UpdateReceipt
import io.wutz.apollo.data.model.UpdateReceiptResponse
import io.wutz.apollo.data.model.UpdateReceiptSummary
import io.wutz.apollo.ui.common.UiText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Render a compact, popup-friendly summary of an update receipt (issue #958).
 *
 * Returns an empty list when there is nothing to show (no receipt, old
 * backend, or any malformed payload) so callers can skip appending entirely.
 *
 * Pure + [internal] so it is unit-testable without mocking the API layer. Lines are [UiText] so the
 * labels are localized; backend values (outcome, versions, fleet states, steps, reasons) stay raw.
 */
internal fun formatUpdateReceiptLines(receipt: UpdateReceiptResponse?): List<UiText> {
    if (receipt == null) return emptyList()
    val summary: UpdateReceiptSummary? = receipt.summary
    val full: UpdateReceipt? = receipt.receipt
    if (summary == null && full == null) return emptyList()

    val lines = mutableListOf<UiText>()
    lines.add(UiText.res(R.string.system_msg_receipt_title))

    val outcome = summary?.outcome ?: full?.outcome
    if (outcome != null) lines.add(UiText.res(R.string.system_msg_receipt_outcome, outcome))

    val pre = full?.preUpdate
    val post = full?.postUpdate
    if (pre != null || post != null) {
        val from = pre?.version ?: pre?.sha?.take(8) ?: "?"
        val to = post?.version ?: post?.sha?.take(8) ?: "?"
        lines.add(UiText.res(R.string.system_msg_receipt_version, from, to))
    } else if (summary?.postVersion != null) {
        lines.add(UiText.res(R.string.system_msg_receipt_version_to, summary.postVersion))
    }

    val fleet = full?.fleet
    if (!fleet.isNullOrEmpty()) {
        val states = fleet.map { "${it.profile ?: "?"}: ${it.state ?: "?"}" }
        lines.add(UiText.res(R.string.system_msg_receipt_fleet, states.joinToString(", ")))
    } else if (summary != null && !summary.fleetStates.isNullOrEmpty()) {
        lines.add(UiText.res(R.string.system_msg_receipt_fleet, summary.fleetStates.joinToString(", ")))
    }

    // A committed update is `success` even while post-commit steps are owed; report them next to the outcome.
    val followups = (full?.followups ?: summary?.followups).orEmpty().filter { it.step != null }
    followups.forEach { followup ->
        val reason = followup.reason?.takeIf { it.isNotBlank() }
        lines.add(
            if (reason != null) {
                UiText.res(R.string.system_msg_receipt_followup_reason, followup.step.orEmpty(), reason)
            } else {
                UiText.res(R.string.system_msg_receipt_followup, followup.step.orEmpty())
            },
        )
    }

    (full?.userAction ?: summary?.userAction)?.let { lines.add(formatUserAction(it)) }

    return lines
}

/** `user_action` is backend-owned; show its `step`/`reason` when present without assuming a wider schema. */
private fun formatUserAction(action: JsonObject): UiText {
    fun text(key: String) = (action[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(text("step"), text("reason")).joinToString(" — ")
    return if (detail.isEmpty()) {
        UiText.res(R.string.system_msg_receipt_user_action)
    } else {
        UiText.res(R.string.system_msg_receipt_user_action_detail, detail)
    }
}
