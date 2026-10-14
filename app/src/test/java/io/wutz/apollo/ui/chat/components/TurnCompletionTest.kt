package io.wutz.apollo.ui.chat.components

import io.wutz.apollo.ui.chat.ApprovalInfo
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnCompletionTest {
    private val reply = ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "done")

    @Test
    fun busyToIdleWithANewReplyAnnouncesOnce() {
        assertEquals(TurnCompletion.REPLIED, turnCompletion(true, false, false, reply, null))
        assertNull(turnCompletion(true, false, false, reply, "a1"))
    }

    @Test
    fun noEdgeNoAnnouncement() {
        assertNull(turnCompletion(false, false, false, reply, null))
        assertNull(turnCompletion(true, true, false, reply, null))
    }

    @Test
    fun aPausedTurnIsNotAReply() {
        val approval =
            ChatMessage(
                id = "s1",
                role = MessageRole.SYSTEM,
                content = "Approval required",
                approvalInfo = ApprovalInfo(command = "rm -rf build", description = null, patternKeys = null),
            )
        assertNull(turnCompletion(true, false, false, approval, null))
    }

    @Test
    fun failureIsAnnounced() {
        assertEquals(TurnCompletion.FAILED, turnCompletion(true, false, true, null, null))
    }
}
