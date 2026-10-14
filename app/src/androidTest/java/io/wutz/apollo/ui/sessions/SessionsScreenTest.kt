package io.wutz.apollo.ui.sessions

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import io.wutz.apollo.R
import io.wutz.apollo.data.model.SessionInfo
import io.wutz.apollo.data.model.SessionSearchResult
import io.wutz.apollo.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The full History screen rendered from fixed [SessionsUiState] values (no ViewModel, no server):
 * list, search, select mode, bulk-delete confirmation and the empty state, each at a phone and a
 * tablet window size. The tablet run also checks that the content column stays capped and centered.
 */
@RunWith(Parameterized::class)
@MediumTest
class SessionsScreenTest(
    private val windowName: String,
    // Plain dp values: JUnit's Parameterized runner cannot pass the inline DpSize class to the constructor.
    private val widthDp: Int,
    private val heightDp: Int,
) {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val alpha = SessionInfo(id = "s1", title = "Alpha session", message_count = 3, preview = "first")
    private val beta = SessionInfo(id = "s2", title = "Beta session", message_count = 5, preview = "second")

    private fun setScreen(
        state: SessionsUiState,
        actions: SessionsScreenActions = SessionsScreenActions(),
    ) {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                HermesControlTheme {
                    SessionsScreenContent(state = state, actions = actions, onOpenDrawer = {})
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun listShowsSessionsAndOpensTheTappedOne() {
        var opened: String? = null
        setScreen(
            state = SessionsUiState(sessions = listOf(alpha, beta), total = 2),
            actions = SessionsScreenActions(onOpenSession = { opened = it }),
        )

        composeTestRule.onNodeWithTag("session_card_s1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_card_s2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_card_s2").performClick()

        assertEquals("s2", opened)
    }

    @Test
    fun contentColumnIsCappedAndCenteredOnWideWindows() {
        setScreen(state = SessionsUiState(sessions = listOf(alpha), total = 1))

        val card = composeTestRule.onNodeWithTag("session_card_s1").getUnclippedBoundsInRoot()
        // ForcedSize lays the content out at the forced size even when the device window is smaller,
        // so the forced window, not the device root, is the frame of reference.
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val windowRight = root.left + widthDp.dp
        val rootWidth = widthDp.dp
        val cardWidth = card.right - card.left
        assertTrue("card must stay within the window", card.left >= root.left && card.right <= windowRight)
        assertTrue("card width $cardWidth must not exceed the 840 dp content column", cardWidth <= 840.dp)
        if (rootWidth > 840.dp) {
            val leftMargin = card.left - root.left
            val rightMargin = windowRight - card.right
            assertTrue(
                "content column must be centered (left $leftMargin, right $rightMargin)",
                (leftMargin - rightMargin).value in -2f..2f, // ForcedSize rescales density; allow rounding
            )
        }
    }

    @Test
    fun searchShowsResultsHeaderAndForwardsTypedQuery() {
        val typed = mutableListOf<String>()
        setScreen(
            state =
                SessionsUiState(
                    sessions = listOf(alpha, beta),
                    searchQuery = "deploy",
                    searchResults = listOf(SessionSearchResult(session_id = "s3", title = "Deploy notes")),
                ),
            actions = SessionsScreenActions(onSearchQueryChange = { typed += it }),
        )

        val header = resources.getQuantityString(R.plurals.sessions_search_results_header, 1, 1, "deploy")
        composeTestRule.onNodeWithText(header).assertIsDisplayed()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("x")
        assertTrue(
            "typing must reach the ViewModel callback, got $typed",
            typed.lastOrNull()?.let { "x" in it && "deploy" in it } == true,
        )
    }

    @Test
    fun selectModeShowsTheBulkBarOnScreenAndRequestsDelete() {
        var deleteRequests = 0
        setScreen(
            state =
                SessionsUiState(
                    sessions = listOf(alpha, beta),
                    total = 2,
                    isSelecting = true,
                    selectedIds = setOf("s1"),
                ),
            actions = SessionsScreenActions(onRequestBulkDelete = { deleteRequests++ }),
        )

        val deleteLabel = resources.getString(R.string.sessions_action_delete_n, 1)
        val deleteButton = composeTestRule.onNodeWithText(deleteLabel)
        deleteButton.assertIsDisplayed()
        // The bar must not be pushed off a narrow screen (the Kanban bar once was).
        val bounds = deleteButton.getUnclippedBoundsInRoot()
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "delete button must be fully inside the window",
            bounds.right <= root.left + widthDp.dp && bounds.bottom <= root.top + heightDp.dp,
        )

        deleteButton.performClick()
        assertEquals(1, deleteRequests)
    }

    @Test
    fun bulkDeleteConfirmationConfirms() {
        var confirmed = 0
        setScreen(
            state =
                SessionsUiState(
                    sessions = listOf(alpha, beta),
                    total = 2,
                    selectedIds = setOf("s1", "s2"),
                    showBulkDeleteConfirm = true,
                ),
            actions = SessionsScreenActions(onConfirmBulkDelete = { confirmed++ }),
        )

        composeTestRule.onNodeWithText(resources.getString(R.string.sessions_bulk_delete_title)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(resources.getQuantityString(R.plurals.sessions_bulk_delete_message, 2, 2))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(resources.getString(R.string.action_delete)).performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun emptyHistoryOffersToStartAChat() {
        var startChat = 0
        setScreen(
            state = SessionsUiState(),
            actions = SessionsScreenActions(onStartChat = { startChat++ }),
        )

        composeTestRule.onNodeWithText(resources.getString(R.string.history_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("empty_state_action").performClick()

        assertEquals(1, startChat)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun windows(): List<Array<Any>> =
            listOf(
                arrayOf("phone", 360, 780),
                arrayOf("tablet", 1280, 800),
            )
    }
}
