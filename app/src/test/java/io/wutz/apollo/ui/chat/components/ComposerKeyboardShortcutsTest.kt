package io.wutz.apollo.ui.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerKeyboardShortcutsTest {
    @Test
    fun `ctrl or meta plus enter on key down sends`() {
        assertTrue(isComposerSendShortcut(keyDown = true, isEnter = true, ctrlOrMeta = true))
    }

    @Test
    fun `plain enter, key up and other keys do not send`() {
        assertFalse(isComposerSendShortcut(keyDown = true, isEnter = true, ctrlOrMeta = false))
        assertFalse(isComposerSendShortcut(keyDown = false, isEnter = true, ctrlOrMeta = true))
        assertFalse(isComposerSendShortcut(keyDown = true, isEnter = false, ctrlOrMeta = true))
    }
}
