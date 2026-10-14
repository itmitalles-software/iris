package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.model.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAttachmentsSendOriginalTest {
    @Test
    fun togglesOnlyTheChosenAttachment() {
        val photo = Attachment(uri = "content://1", name = "1.jpg", mimeType = "image/jpeg")
        val other = photo.copy(uri = "content://2", name = "2.jpg")
        val state = MutableStateFlow(ChatUiState(pendingAttachments = listOf(photo, other)))
        val delegate = ChatAttachmentsDelegate(state)

        delegate.setSendOriginal(1, true)
        assertEquals(listOf(false, true), state.value.pendingAttachments.map { it.sendOriginal })

        delegate.setSendOriginal(1, false)
        delegate.setSendOriginal(5, true)
        assertEquals(listOf(false, false), state.value.pendingAttachments.map { it.sendOriginal })
    }
}
