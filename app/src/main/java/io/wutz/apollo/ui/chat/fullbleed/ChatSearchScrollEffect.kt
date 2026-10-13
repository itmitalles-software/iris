package io.wutz.apollo.ui.chat.fullbleed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.ChatSearchState
import io.wutz.apollo.ui.chat.components.ChatScrollController
import io.wutz.apollo.ui.chat.searchTextFor
import io.wutz.apollo.ui.chat.tool.rememberToolText

/** Resolve a search hit against emitted lazy rows, including hoisted reasoning and tool rows. */
@Composable
internal fun ChatSearchScrollEffect(
    searchState: ChatSearchState,
    messages: List<ChatMessage>,
    renderedFirstId: String?,
    turns: List<ChatTurn>,
    scrollController: ChatScrollController,
) {
    val toolText = rememberToolText()
    LaunchedEffect(
        searchState.isActive,
        searchState.currentIndex,
        searchState.matchIndices,
        searchState.matchOffsets,
        searchState.matchTargets,
        searchState.currentMatchId,
        messages,
        renderedFirstId,
    ) {
        val index = searchState.currentIndex
        if (!searchState.isActive || index !in searchState.matchIndices.indices) return@LaunchedEffect
        val messageIndex = searchState.matchIndices[index]
        val message = messages.getOrNull(messageIndex) ?: return@LaunchedEffect
        // Do not scroll to a different row while a presentation transition is being rescanned.
        if (message.id != searchState.currentMatchId) return@LaunchedEffect
        val target = searchState.matchTargets.getOrNull(index) ?: return@LaunchedEffect
        val match =
            io.wutz.apollo.ui.chat.SearchMatch(
                messageIndex = messageIndex,
                contentOffset = searchState.matchOffsets.getOrElse(index) { 0 },
                target = target,
            )
        // A hit in a staged history prefix is retried when the prefix enters layout.
        val lazyIndex = searchMatchToLazyIndex(turns, messages, match) ?: return@LaunchedEffect
        scrollController.scrollToSearchMatch(
            lazyIndex,
            match.contentOffset,
            searchTextFor(message, target, toolText).length,
        )
    }
}
