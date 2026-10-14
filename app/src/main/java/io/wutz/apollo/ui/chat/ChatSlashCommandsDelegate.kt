package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.data.local.SlashUsageStore
import io.wutz.apollo.data.model.reasoningSupport
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.ws.CommandBlocklist
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.contract.CommandDispatchParams
import io.wutz.apollo.data.ws.contract.ConfigGetParams
import io.wutz.apollo.data.ws.contract.ConfigSetParams
import io.wutz.apollo.data.ws.contract.PromptBtwParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SessionBranchWholeParams
import io.wutz.apollo.data.ws.contract.SlashExecParams
import io.wutz.apollo.data.ws.toAny
import io.wutz.apollo.ui.common.ActionProgressController
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val REASONING_EFFORT_LEVELS =
    setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

private data class ReasoningSlashArgs(
    val value: String,
    val scopeName: String?,
)

/** Mirrors Desktop's reasoningSlashParams(): flags may appear in any order. */
private fun parseReasoningSlashArgs(arg: String): ReasoningSlashArgs? {
    var scopeName: String? = null
    val values = mutableListOf<String>()

    arg
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .forEach { part ->
            when (part.lowercase()) {
                "--global", "-g", "global" -> scopeName = "global"
                "--session", "-s", "session" -> scopeName = "session"
                else -> values += part
            }
        }

    if (values.isEmpty()) return null
    return ReasoningSlashArgs(values.joinToString(" "), scopeName)
}

/**
 * Runs slash commands typed into the composer: classifies them with [SlashCommandDispatcher], handles the
 * client-side ones (/new, /branch, /model, /reasoning, /btw, /update, /undo, /compress, /history) and sends the
 * rest through `slash.exec` with the `command.dispatch` fallback. /queue stays with the send pipeline and is
 * reached through [handleQueueCommand].
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatSlashCommandsDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val slashUsageStore: SlashUsageStore,
    private val actionProgress: ActionProgressController,
    private val runtimeSessionId: () -> String?,
    private val sessionGeneration: () -> Long,
    private val appendLocalTranscriptEvent: (message: ChatMessage, persist: Boolean) -> ChatMessage,
    private val addAssistantMessage: (String) -> Unit,
    private val localized: (UiText) -> String,
    private val interruptSession: () -> Unit,
    private val stopSessionAndProcesses: () -> Unit,
    private val createNewSession: () -> Unit,
    private val handleModelSwitch: (command: String) -> Unit,
    private val setReasoningLevel: (level: String, scopeName: String, enforceCapabilities: Boolean) -> Unit,
    private val compressSession: (focusTopic: String) -> Unit,
    private val handleQueueCommand: (command: String, userMessage: ChatMessage) -> Unit,
    private val handleDispatchResult: (result: Any?) -> Unit,
    private val trackBranchWhole: (id: String, generation: Long, params: SessionBranchWholeParams) -> Unit,
) {
    private val wsClient = HermesWsClient
    private val slashDispatcher = SlashCommandDispatcher()

    private fun addAssistantMessage(text: UiText) = addAssistantMessage(localized(text))

    fun handleSlashCommand(command: String) {
        // Classify FIRST (pure logic) — /queue's optimistic bubble must show
        // the queued TEXT (prefix stripped, see QueuePrompt.displayContent) so
        // it matches the server echo and the transcript sync dedupes instead
        // of rendering a duplicate below its answer.
        val result = slashDispatcher.dispatch(command)

        if (result is SlashResult.SideQuestion) {
            handleSideQuestionCommand(result.question)
            return
        }

        val displayContent =
            if (result is SlashResult.QueuePrompt) result.displayContent else command
        val userMsg =
            appendLocalTranscriptEvent(
                ChatMessage(
                    role = MessageRole.USER,
                    content = displayContent,
                    tokenCount = TokenEstimator.estimate(displayContent).takeIf { it > 0 },
                    // Follow-up to #1253: stripped /queue text is an unconfirmed prompt,
                    // not a permanently-local command or an ambiguous legacy cache row.
                    messageProvenance =
                        if (result is SlashResult.QueuePrompt && displayContent != command) {
                            MessageProvenance.LOCAL_PENDING
                        } else {
                            MessageProvenance.UNKNOWN
                        },
                ),
                // Fix #1437 follow-up: an empty /queue is local feedback, not a staged prompt.
                // Only stripped, nonempty queue prompts defer persistence to the send pipeline.
                result !is SlashResult.QueuePrompt || displayContent == command,
            )

        if (result is SlashResult.Undo) {
            handleUndoCommand(result.count)
            return
        }

        // Block desktop/CLI-only + TUI-only commands that don't function on
        // mobile (issue #576, deliverable #3). These are also hidden from the
        // suggestion menu, but a user can still type one — intercept it here
        // (before any RPC fires) with a clear message instead of a doomed call.
        if (CommandBlocklist.contains(command)) {
            addAssistantMessage(
                UiText.res(R.string.chat_msg_command_not_supported_mobile, command.split(" ", limit = 2)[0]),
            )
            return
        }

        // Track per-command usage for the slash-autocomplete ranking (issue
        // #865). Counted here, AFTER the blocklist guard, so commands that
        // can never dispatch don't climb the ranking. Best-effort — a store
        // failure must never block the dispatch itself.
        val commandName = command.split(" ", limit = 2)[0].lowercase()
        scope.launch {
            slashUsageStore.recordUse(commandName)
        }

        when (result) {
            is SlashResult.Interrupt -> {
                interruptSession()
            }

            is SlashResult.Stop -> {
                stopSessionAndProcesses()
            }

            is SlashResult.NewSession -> {
                val currentTitle = uiState.value.chatTitle
                if (currentTitle.equals("Bot Chat", ignoreCase = true)) {
                    // Bot Chat is a canonical forever-conversation — compact instead of creating an orphan
                    val sessionId = uiState.value.currentSessionId
                    if (!sessionId.isNullOrBlank()) {
                        addAssistantMessage(UiText.res(R.string.chat_msg_bot_chat_compacting))
                        dispatchViaRpc("/compact")
                    } else {
                        createNewSession()
                    }
                } else {
                    createNewSession()
                }
            }

            is SlashResult.SessionBranch -> {
                branchSession(command)
            }

            is SlashResult.ModelSwitch -> {
                handleModelSwitch(command)
            }

            is SlashResult.ReasoningSwitch -> {
                handleReasoningSlashCommand(result.level)
            }

            is SlashResult.Update -> {
                openUpdateConfirm()
            }

            is SlashResult.OpenHistory -> {
                // Client-side: open the session history tab so the user can
                // pick a past session to resume (issue #864) — no gateway
                // round-trip (the backend slash worker can't answer /resume).
                uiState.update { it.copy(openHistoryRequested = true) }
            }

            is SlashResult.QueuePrompt -> {
                handleQueueCommand(command, userMsg)
            }

            is SlashResult.SideQuestion -> {
                handleSideQuestionCommand(result.question)
            }

            is SlashResult.Undo -> {
                handleUndoCommand(result.count)
            }

            is SlashResult.Compress -> {
                compressSession(result.focusTopic)
            }

            is SlashResult.RpcDispatch -> {
                dispatchViaRpc(command)
            }
        }
    }

    private fun handleReasoningSlashCommand(arg: String) {
        val sessionId = runtimeSessionId()
        if (sessionId.isNullOrBlank()) {
            addAssistantMessage(UiText.res(R.string.chat_msg_reasoning_requires_session))
            return
        }

        val parsed = parseReasoningSlashArgs(arg)

        // Bare `/reasoning` is a status query, matching Desktop/TUI semantics.
        if (parsed == null) {
            scope.launch(ioDispatcher) {
                try {
                    val result =
                        wsClient.call(
                            RpcMethods.CONFIG_GET,
                            ConfigGetParams(key = "reasoning", sessionId = sessionId),
                        )
                    val map = rpcResultMap(result) ?: error("Invalid reasoning config.get response")
                    val value = (map["value"] as? String)?.takeIf { it.isNotBlank() } ?: "unknown"
                    val display = (map["display"] as? String)?.takeIf { it.isNotBlank() } ?: "unknown"
                    addAssistantMessage("reasoning: $value · display $display")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addAssistantMessage(
                        UiText.res(R.string.chat_msg_reasoning_status_failed, e.message ?: e.toString()),
                    )
                }
            }
            return
        }

        val value = parsed.value.trim().lowercase()

        // Effort changes use the acknowledged delegate so the composer shows
        // pending state and prompt submission remains gated until config.set
        // succeeds. A global preference may target a future model, so do not
        // reject it based on only the current model's capabilities.
        if (value in REASONING_EFFORT_LEVELS) {
            if (parsed.scopeName != "global") {
                val caps = uiState.value.currentModelCapabilities
                if (caps?.reasoningSupport == false) {
                    addAssistantMessage(UiText.res(R.string.chat_msg_reasoning_not_supported))
                    return
                }
                if (value == "none" && caps?.can_disable_reasoning == false) {
                    addAssistantMessage(UiText.res(R.string.chat_msg_reasoning_always_on))
                    return
                }
            }
            setReasoningLevel(
                value,
                parsed.scopeName ?: "session",
                parsed.scopeName != "global",
            )
            return
        }

        // Display controls (`show`/`hide`/`full`/`clamp` and backend aliases)
        // and future config.set reasoning values go through the canonical
        // backend parser instead of a client-maintained allowlist. Invalid
        // values therefore surface the real 4002 error instead of being eaten
        // locally. Scope flags are forwarded exactly like Desktop.
        scope.launch(ioDispatcher) {
            try {
                val params =
                    ConfigSetParams(
                        key = "reasoning",
                        value = parsed.value,
                        sessionId = sessionId,
                        scope = parsed.scopeName,
                    )
                val result = wsClient.call(RpcMethods.CONFIG_SET, params)
                val map = rpcResultMap(result) ?: error("Invalid reasoning config.set response")
                val responseKey = map["key"] as? String
                val responseValue = map["value"] as? String
                if (responseKey != "reasoning" || responseValue.isNullOrBlank()) {
                    error("Invalid reasoning config.set response")
                }
                addAssistantMessage("reasoning: $responseValue")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addAssistantMessage(UiText.res(R.string.chat_msg_reasoning_change_failed, e.message ?: e.toString()))
            }
        }
    }

    // ── Side Questions via /btw (issue #1015) ─────────────────────────────

    fun dismissBtw() {
        uiState.update { it.copy(btwState = null) }
    }

    fun submitSideQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank()) {
            addAssistantMessage(UiText.res(R.string.chat_msg_btw_usage))
            return
        }
        val sessionId = runtimeSessionId() ?: uiState.value.currentSessionId
        if (sessionId.isNullOrBlank()) {
            addAssistantMessage(UiText.res(R.string.chat_msg_btw_no_session))
            return
        }

        uiState.update {
            it.copy(
                btwState =
                    BtwUiState(
                        question = trimmed,
                        isLoading = true,
                    ),
            )
        }

        scope.launch(ioDispatcher) {
            try {
                val taskId =
                    wsClient
                        .call(
                            RpcMethods.PROMPT_BTW,
                            PromptBtwParams(sessionId = sessionId, text = trimmed),
                        ).taskId
                if (!taskId.isNullOrBlank()) {
                    uiState.update { state ->
                        state.btwState?.let { current ->
                            state.copy(btwState = current.copy(taskId = taskId))
                        } ?: state
                    }
                }
            } catch (e: Exception) {
                uiState.update { state ->
                    state.btwState?.let { current ->
                        state.copy(
                            btwState =
                                current.copy(
                                    isLoading = false,
                                    error = e.message ?: localized(UiText.res(R.string.chat_msg_btw_dispatch_failed)),
                                ),
                        )
                    } ?: state
                }
            }
        }
    }

    private fun handleSideQuestionCommand(question: String) {
        scope.launch {
            slashUsageStore.recordUse("btw")
        }
        submitSideQuestion(question)
    }

    // ── Update from chat (issue #862) ────────────────────────────────────
    // `/update` can't travel via the slash worker (the backend handler is
    // interactive + session-exiting → guaranteed 45s timeout). Intercept it
    // client-side: confirm, then trigger the same REST action the System
    // screen uses and track it in the shared ActionProgressDialog.

    fun openUpdateConfirm() {
        uiState.update { it.copy(updateConfirmOpen = true) }
    }

    fun closeUpdateConfirm() {
        uiState.update { it.copy(updateConfirmOpen = false) }
    }

    /**
     * Run the backend update: `POST /api/hermes/update` returns immediately
     * (`{ok, name}`) while `hermes update` runs in the background, so
     * [actionProgress] polls its status log until it exits and the popup shows
     * the live tail + final state.
     */
    fun applyUpdate() {
        actionProgress.open()
        scope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.updateHermes() }
            when (result) {
                is NetworkResult.Success -> {
                    val name = result.data.name
                    if (name != null) {
                        actionProgress.markStarted(name)
                    } else {
                        actionProgress.fail(localized(UiText.res(R.string.chat_msg_update_no_action_name)))
                    }
                }

                is NetworkResult.Failure -> {
                    actionProgress.fail(
                        localized(UiText.res(R.string.chat_msg_update_start_failed, result.error.message)),
                    )
                }
            }
        }
    }

    /**
     * Fork the active conversation via the lightweight session.branch_whole WS RPC (issue #1289).
     * Falls back to session.branch if the gateway answers -32601 (unknown method).
     * The optional arg becomes the new branch's title.
     */
    private fun branchSession(command: String) {
        val sessionId = runtimeSessionId()
        if (sessionId == null) {
            addAssistantMessage(UiText.res(R.string.chat_msg_no_active_session))
            return
        }
        val arg = command.split(" ", limit = 2).getOrElse(1) { "" }.trim()
        val params = SessionBranchWholeParams(sessionId = sessionId, name = arg.takeIf { it.isNotBlank() })
        val generation = sessionGeneration()
        scope.launch(ioDispatcher) {
            wsClient.send(
                RpcMethods.SESSION_BRANCH_WHOLE,
                params,
                onSent = { id ->
                    trackBranchWhole(id, generation, params)
                },
            )
        }
    }

    private fun dispatchViaRpc(command: String) {
        val sessionId = runtimeSessionId()
        if (sessionId == null) {
            addAssistantMessage(UiText.res(R.string.chat_msg_no_active_session))
            return
        }
        val parts = command.split(" ", limit = 2)
        val name = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        scope.launch(ioDispatcher) {
            try {
                // Desktop parity: slash.exec owns generic commands. Handle errors here,
                // not again through the shared RpcError banner (/usage regression).
                val result =
                    wsClient.call(
                        RpcMethods.SLASH_EXEC,
                        SlashExecParams(sessionId = sessionId, command = command.removePrefix("/")),
                        suppressErrorEvent = true,
                    )
                val map = result.toAny() as? Map<*, *>
                if (map?.get("type") is String) {
                    handleDispatchResult(map)
                } else {
                    val output =
                        (map?.get("output") as? String).orEmpty().ifBlank {
                            localized(UiText.res(R.string.chat_msg_command_no_output, name))
                        }
                    val warning = (map?.get("warning") as? String).orEmpty()
                    addAssistantMessage(if (warning.isBlank()) output else "warning: $warning\n$output")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    val result =
                        wsClient.call(
                            RpcMethods.COMMAND_DISPATCH,
                            CommandDispatchParams(name = name, arg = arg, sessionId = sessionId),
                            suppressErrorEvent = true,
                        )
                    val map = result.toAny() as? Map<*, *>
                    if (map?.get("type") is String) {
                        handleDispatchResult(map)
                    } else {
                        addAssistantMessage("/$name: invalid response: command.dispatch")
                    }
                } catch (fallback: CancellationException) {
                    throw fallback
                } catch (fallback: Exception) {
                    val registryMiss =
                        Regex("not a quick/plugin/(?:bundle/)?skill command", RegexOption.IGNORE_CASE)
                            .containsMatchIn(fallback.message.orEmpty())
                    val failure = if (registryMiss) e else fallback
                    addAssistantMessage("/$name: ${failure.message}")
                }
            }
        }
    }

    private fun handleUndoCommand(count: String) {
        val sessionId = runtimeSessionId()
        if (sessionId == null) {
            addAssistantMessage(UiText.res(R.string.chat_msg_undo_no_session))
            return
        }
        scope.launch {
            slashUsageStore.recordUse("/undo")
        }
        scope.launch(ioDispatcher) {
            try {
                val result =
                    wsClient.call(
                        RpcMethods.COMMAND_DISPATCH,
                        CommandDispatchParams(name = "undo", arg = count, sessionId = sessionId),
                    )
                handleDispatchResult(result.toAny())
            } catch (e: HermesWsClient.HermesRpcException) {
                addAssistantMessage(e.message ?: localized(UiText.res(R.string.chat_msg_undo_failed)))
            } catch (e: Exception) {
                addAssistantMessage(e.message ?: localized(UiText.res(R.string.chat_msg_undo_failed)))
            }
        }
    }
}
