package io.wutz.apollo.ui.system

import io.wutz.apollo.R
import io.wutz.apollo.data.model.UpdateReceipt
import io.wutz.apollo.data.model.UpdateReceiptFleetEntry
import io.wutz.apollo.data.model.UpdateReceiptFollowup
import io.wutz.apollo.data.model.UpdateReceiptResponse
import io.wutz.apollo.data.model.UpdateReceiptSummary
import io.wutz.apollo.data.model.UpdateReceiptVersion
import io.wutz.apollo.ui.common.UiText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [formatUpdateReceiptLines] (issue #958). Mockk cannot
 * self-attach its agent in this sandbox, so these avoid the API layer entirely
 * and exercise the formatting directly.
 */
class UpdateReceiptFormattingTest {
    @Test
    fun `null receipt yields empty list`() {
        assertTrue(formatUpdateReceiptLines(null).isEmpty())
    }

    @Test
    fun `empty receipt (no summary, no record) yields empty list`() {
        assertTrue(formatUpdateReceiptLines(UpdateReceiptResponse()).isEmpty())
    }

    @Test
    fun `full receipt renders outcome version and fleet`() {
        val receipt =
            UpdateReceiptResponse(
                receipt =
                    UpdateReceipt(
                        outcome = "success",
                        preUpdate = UpdateReceiptVersion(version = "0.20.4", sha = "a".repeat(40)),
                        postUpdate = UpdateReceiptVersion(version = "0.20.5", sha = "b".repeat(40)),
                        fleet =
                            listOf(
                                UpdateReceiptFleetEntry(profile = "default", state = "current"),
                                UpdateReceiptFleetEntry(profile = "work", state = "stale"),
                            ),
                    ),
                summary =
                    UpdateReceiptSummary(
                        outcome = "success",
                        postVersion = "0.20.5",
                        fleetStates = listOf("current", "stale"),
                    ),
            )

        val lines = formatUpdateReceiptLines(receipt)
        assertEquals(
            listOf(
                title,
                UiText.res(R.string.system_msg_receipt_outcome, "success"),
                UiText.res(R.string.system_msg_receipt_version, "0.20.4", "0.20.5"),
                UiText.res(R.string.system_msg_receipt_fleet, "default: current, work: stale"),
            ),
            lines,
        )
    }

    @Test
    fun `summary-only receipt falls back to postVersion and fleetStates`() {
        val receipt =
            UpdateReceiptResponse(
                summary =
                    UpdateReceiptSummary(
                        outcome = "partial",
                        postVersion = "0.21.0",
                        fleetStates = listOf("current"),
                    ),
            )

        val lines = formatUpdateReceiptLines(receipt)
        assertEquals(
            listOf(
                title,
                UiText.res(R.string.system_msg_receipt_outcome, "partial"),
                UiText.res(R.string.system_msg_receipt_version_to, "0.21.0"),
                UiText.res(R.string.system_msg_receipt_fleet, "current"),
            ),
            lines,
        )
    }

    @Test
    fun `version falls back to short sha when version is missing`() {
        val receipt =
            UpdateReceiptResponse(
                receipt =
                    UpdateReceipt(
                        outcome = "success",
                        preUpdate = UpdateReceiptVersion(sha = "abcdef1234567890deadbeef"),
                        postUpdate = UpdateReceiptVersion(sha = "11112222"),
                    ),
            )

        val lines = formatUpdateReceiptLines(receipt)
        assertEquals(
            listOf(
                title,
                UiText.res(R.string.system_msg_receipt_outcome, "success"),
                UiText.res(R.string.system_msg_receipt_version, "abcdef12", "11112222"),
            ),
            lines,
        )
    }

    @Test
    fun `committed success still lists owed follow-ups and user action`() {
        val receipt =
            UpdateReceiptResponse(
                summary =
                    UpdateReceiptSummary(
                        outcome = "success",
                        postVersion = "0.21.7",
                        followups = listOf(UpdateReceiptFollowup(step = "restart-gateway", reason = "unit busy")),
                        userAction = json.decodeFromString("""{"step":"stash","reason":"restore conflicted","x":1}"""),
                    ),
            )

        assertEquals(
            listOf(
                title,
                UiText.res(R.string.system_msg_receipt_outcome, "success"),
                UiText.res(R.string.system_msg_receipt_version_to, "0.21.7"),
                UiText.res(R.string.system_msg_receipt_followup_reason, "restart-gateway", "unit busy"),
                UiText.res(R.string.system_msg_receipt_user_action_detail, "stash — restore conflicted"),
            ),
            formatUpdateReceiptLines(receipt),
        )
    }

    @Test
    fun `empty followups and unreadable user action add nothing or a bare notice`() {
        val plain = UpdateReceiptResponse(summary = UpdateReceiptSummary(outcome = "success", followups = emptyList()))
        assertEquals(
            listOf(title, UiText.res(R.string.system_msg_receipt_outcome, "success")),
            formatUpdateReceiptLines(plain),
        )

        val opaque = UpdateReceiptResponse(summary = UpdateReceiptSummary(userAction = json.decodeFromString("{}")))
        assertEquals(
            listOf(title, UiText.res(R.string.system_msg_receipt_user_action)),
            formatUpdateReceiptLines(opaque),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `wire payload decodes followups and user_action in both receipt shapes`() {
        val wire =
            Json {
                ignoreUnknownKeys = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
        val decoded =
            wire.decodeFromString<UpdateReceiptResponse>(
                """{"receipt":{"outcome":"partial","followups":[{"step":"s","reason":"r","at":"t"}],
                "user_action":{"step":"a","extra":true}},
                "summary":{"outcome":"partial","followups":[{"step":"s","reason":"r"}],"user_action":null}}""",
            )
        assertEquals(
            "s",
            decoded.receipt!!
                .followups!!
                .single()
                .step,
        )
        assertEquals(true, decoded.receipt!!.userAction != null)
        assertEquals(
            "r",
            decoded.summary!!
                .followups!!
                .single()
                .reason,
        )

        val old = wire.decodeFromString<UpdateReceiptResponse>("""{"summary":{"outcome":"success"}}""")
        assertEquals(null, old.summary!!.followups)
    }

    @Test
    fun `follow-up without a reason omits the separator`() {
        val receipt =
            UpdateReceiptResponse(
                summary =
                    UpdateReceiptSummary(
                        followups = listOf(UpdateReceiptFollowup(step = "migrate", reason = " ")),
                    ),
            )
        assertEquals(
            listOf(title, UiText.res(R.string.system_msg_receipt_followup, "migrate")),
            formatUpdateReceiptLines(receipt),
        )
    }

    private val title = UiText.res(R.string.system_msg_receipt_title)

    private val json = Json
}
