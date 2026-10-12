package io.wutz.apollo.ui.chat.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Hardware-keyboard send shortcut for the chat composer: Ctrl+Enter (or
 * Meta+Enter on keyboards that map Cmd). Plain Enter keeps inserting a
 * newline, so multi-line prompts still work from a physical keyboard.
 */
internal fun isComposerSendShortcut(
    keyDown: Boolean,
    isEnter: Boolean,
    ctrlOrMeta: Boolean,
): Boolean = keyDown && isEnter && ctrlOrMeta

internal fun KeyEvent.isComposerSendShortcut(): Boolean =
    isComposerSendShortcut(
        keyDown = type == KeyEventType.KeyDown,
        isEnter = key == Key.Enter || key == Key.NumPadEnter,
        ctrlOrMeta = isCtrlPressed || isMetaPressed,
    )
