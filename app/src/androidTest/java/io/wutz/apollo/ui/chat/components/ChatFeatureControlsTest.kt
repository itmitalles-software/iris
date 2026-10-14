package io.wutz.apollo.ui.chat.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import io.wutz.apollo.data.model.Attachment
import io.wutz.apollo.data.model.MessageReaction
import io.wutz.apollo.data.ws.CommandCatalog
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.MessageRole
import io.wutz.apollo.ui.chat.QUICK_REACTIONS
import io.wutz.apollo.ui.chat.fullbleed.FullBleedAgentMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reaction picker, the "send original" toggle and the 48dp touch targets of the chat controls. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ChatFeatureControlsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val reply = ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "Done", serverRowId = 7L)

    @Test
    fun reactionPickerSendsTheChosenEmojiAndRetractsTheCurrentOne() {
        val reactions = mutableListOf<String?>()
        composeTestRule.setContent {
            FullBleedAgentMessage(
                message = reply.copy(reactions = listOf(MessageReaction(QUICK_REACTIONS[0], "user"))),
                onReact = { reactions += it },
            )
        }
        composeTestRule.onNodeWithTag("fullbleed_react").assertWidthIsAtLeast(48.dp).performClick()
        composeTestRule.onNodeWithTag("reaction_picker").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("reaction_option")[1].performClick()
        composeTestRule.onNodeWithTag("fullbleed_react").performClick()
        composeTestRule.onAllNodesWithTag("reaction_option")[0].performClick()
        assertEquals(listOf(QUICK_REACTIONS[1], null), reactions)
    }

    @Test
    fun messageActionsHave48dpTargets() {
        composeTestRule.setContent { FullBleedAgentMessage(message = reply, onToggleSpeak = {}) }
        composeTestRule.onNodeWithTag("fullbleed_copy").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("fullbleed_speak").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("fullbleed_react").assertDoesNotExist()
    }

    @Test
    fun sendOriginalToggleOnlyForStillImages() {
        val photo = Attachment(uri = "content://p", name = "p.jpg", mimeType = "image/jpeg", sendOriginal = true)
        var toggles = 0
        composeTestRule.setContent {
            AttachmentChip(attachment = photo, onPreview = {}, onRemove = {}, onToggleSendOriginal = { toggles++ })
        }
        composeTestRule.onNodeWithTag("attachment_send_original").assertIsOn().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun gifHasNoSendOriginalToggle() {
        val gif = Attachment(uri = "content://g", name = "g.gif", mimeType = "image/gif")
        composeTestRule.setContent {
            AttachmentChip(attachment = gif, onPreview = {}, onRemove = {}, onToggleSendOriginal = {})
        }
        composeTestRule.onNodeWithTag("attachment_send_original").assertDoesNotExist()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun composerControlsHave48dpTargets() {
        composeTestRule.setContent {
            ChatInputBar(
                inputState = rememberTextFieldState(),
                onSend = {},
                onMicTap = {},
                isListening = false,
                isAgentTyping = false,
                isConnected = true,
                commandCatalog = CommandCatalog(),
                isSessionReady = true,
            )
        }
        // The 36 dp model pill keeps its drawn height; Compose extends its touch bounds to 48 dp.
        listOf("attachment_button", "mic_button").forEach { tag ->
            composeTestRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
        composeTestRule.onNodeWithTag("attachment_button").assertWidthIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag("mic_button").assertWidthIsAtLeast(48.dp)
    }
}
