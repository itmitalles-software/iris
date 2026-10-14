package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.ModelInfoResponse
import io.wutz.apollo.data.model.parseContextBreakdown
import io.wutz.apollo.data.model.parseUsageSnapshot
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.ws.contract.HermesRpcCaller
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SessionIdParams
import io.wutz.apollo.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the context meter: live occupancy and window (`session.context_breakdown`, `session.usage`, REST
 * model info) plus the cumulative detail-sheet counters. A model generation and a request sequence make sure a
 * fetch started for an earlier session or model never writes into the current one (issues #817, #1103).
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatContextUsageDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val sessionGeneration: () -> Long,
    private val runtimeSessionId: () -> String?,
    private val isModelSwitchPending: () -> Boolean,
    private val applyModelInfo: (ModelInfoResponse) -> Unit,
    private val rpc: TypedRpcCaller = HermesRpcCaller,
) {
    /** Last confirmed model label pushed by SessionInfo or SessionResume from backend (issue #1103). */
    var lastConfirmedSessionModel: String? = null

    /** Model generation counter to prevent stale context writes across switches (issue #1103). */
    private var modelGeneration: Long = 0L

    /** Request sequence counter for fetchContextUsage calls. */
    private var contextFetchSequence: Long = 0L

    /** Tracks the latest context-usage poll or refetch job. */
    private var contextUsageJob: Job? = null

    /** True while a context fetch is still running. */
    val isFetchActive: Boolean get() = contextUsageJob?.isActive == true

    /** The confirmed model changed: cancel the running fetch and reject late results of earlier ones. */
    fun invalidate() {
        modelGeneration++
        contextUsageJob?.cancel()
        contextUsageJob = null
    }

    /** Session switch or reset: forget the confirmed model as well. */
    fun reset() {
        invalidate()
        lastConfirmedSessionModel = null
    }

    /** A model switch was sent: drop the old window and every in-flight fetch for the old model. */
    fun onModelSwitchInitiated() {
        invalidate()
        uiState.update { it.copy(fullContextTokens = null) }
    }

    private fun isCurrentContextFetch(
        sessionId: String,
        targetSessionGeneration: Long,
        targetModelGeneration: Long,
        targetRequestSequence: Long,
    ): Boolean =
        targetSessionGeneration == sessionGeneration() &&
            targetModelGeneration == modelGeneration &&
            targetRequestSequence == contextFetchSequence &&
            uiState.value.currentSessionId == sessionId

    fun isMatchingModel(
        currentModel: String?,
        info: io.wutz.apollo.data.model.ModelInfoResponse?,
    ): Boolean {
        if (currentModel.isNullOrBlank() || info == null || info.model.isNullOrBlank()) {
            return false
        }
        val restModel = info.model
        val restProvider = info.provider
        return if (currentModel.contains('/')) {
            val curProvider = currentModel.substringBefore('/')
            val curModel = currentModel.substringAfter('/')
            if (!restProvider.isNullOrBlank()) {
                curProvider.equals(restProvider, ignoreCase = true) &&
                    curModel.equals(restModel, ignoreCase = true)
            } else {
                curModel.equals(restModel, ignoreCase = true)
            }
        } else {
            currentModel.equals(restModel, ignoreCase = true)
        }
    }

    fun isMatchingRpcModel(
        currentModel: String?,
        rpcModel: String?,
    ): Boolean {
        if (currentModel.isNullOrBlank() || rpcModel.isNullOrBlank()) return false
        if (currentModel.equals(rpcModel, ignoreCase = true)) return true
        return currentModel.contains('/') &&
            currentModel.substringAfter('/').equals(rpcModel, ignoreCase = true)
    }

    /**
     * Refresh the context meter: used / full tokens for the current session.
     *
     * The numerator comes from the `session.context_breakdown` WS RPC — the
     * same RPC the Hermes desktop app's status-bar meter uses. It reports the
     * live agent's actual prompt occupancy (compressor `last_prompt_tokens`,
     * falling back to an estimate of the live system prompt + tools +
     * history), so it DROPS after context compression. The previous numerator,
     * `GET /api/sessions/{id}` `input_tokens`, is a cumulative lifetime
     * counter that never resets on compression (issue #756).
     *
     * The denominator comes from the RPC's `context_max` (the compressor's
     * real context window) when present, else `GET /api/model/info`
     * `effective_context_length`. The REST session-detail call is kept only to
     * feed the detail sheet's cumulative token accounting.
     *
     * Both calls are independent and best-effort: a failure on one must not
     * wipe the other's already-shown value, and neither blocks the chat. The
     * two fetches are launched separately so a slow/erroring one can't starve
     * the other. Polled from [syncCurrentSession] via the 30s loop and re-fired
     * on model switch (the denominator changes).
     */
    fun fetchContextUsage(skipRestFallback: Boolean = false) {
        val sessionId = uiState.value.currentSessionId ?: return
        val profile = AuthManager.activeProfileId.value
        val targetSessionGeneration = sessionGeneration()
        val targetModelGeneration = modelGeneration
        val targetRequestSequence = ++contextFetchSequence
        val isSwitchPending = isModelSwitchPending()

        contextUsageJob?.cancel()
        contextUsageJob =
            scope.launch(ioDispatcher) {
                // Denominator fallback: full context window (cheap, public, rarely
                // changes). The RPC's context_max below overrides it when present.
                // Kept as a local (not a state write) so both sources resolve
                // before ONE atomic update below (issue #817 — two independent
                // writes let a stale pre-swap value override a fresh one mid-swap).
                val fullResult =
                    safeApiCall { ApiClient.hermesApi.getModelInfo() }
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                val restFull =
                    if (fullResult is NetworkResult.Success && !isSwitchPending) {
                        val info = fullResult.data
                        val currentModel = uiState.value.currentSessionModel
                        // Issue #1103: only use profile REST model/info as fallback if it
                        // matches the current session model identity. If the session was switched to
                        // another model (e.g. Solar, Gemini), the profile-level model/info describes
                        // a different model and must never poison the session's context window.
                        if (isMatchingModel(currentModel, info)) {
                            applyModelInfo(info)
                            info.effective_context_length
                                ?: info.auto_context_length
                                ?: info.config_context_length
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                // Numerator: live context occupancy from the gateway's live agent,
                // via the same RPC the desktop meter uses. `context_used` is the
                // real current prompt size (drops after compression); `context_max`
                // is the compressor's actual window. Any failure keeps the last
                // known values — never blank the meter over a transient RPC error.
                //
                // These RPCs resolve the session against the gateway's LIVE runtime
                // registry (_sess_nowait) — the storage session id 4001s "session
                // not found" until session.resume has registered it. ChatScreen's
                // sync effect fires this immediately on session switch, before the
                // resume result lands, so skip the RPCs until resume confirms the
                // runtime id. The REST parts below stay live (they key on the
                // storage id).
                var rpcUsed: Long? = null
                var rpcMax: Long? = null
                val rpcSessionId = runtimeSessionId()
                if (rpcSessionId != null) {
                    try {
                        val result =
                            rpc.call(
                                RpcMethods.SESSION_CONTEXT_BREAKDOWN,
                                SessionIdParams(rpcSessionId),
                            )
                        coroutineContext.ensureActive()
                        val ctx = parseContextBreakdown(result)
                        if (ctx != null) {
                            val currentModel = uiState.value.currentSessionModel
                            val modelMatches =
                                if (ctx.model != null) {
                                    isMatchingRpcModel(currentModel, ctx.model)
                                } else {
                                    !isSwitchPending
                                }
                            if (modelMatches) {
                                rpcUsed = ctx.contextUsed?.takeIf { it > 0L }
                                rpcMax = ctx.contextMax?.takeIf { it > 0L }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best-effort: RPC error/timeout/disconnect — keep last values.
                    }
                    if (!isCurrentContextFetch(
                            sessionId,
                            targetSessionGeneration,
                            targetModelGeneration,
                            targetRequestSequence,
                        )
                    ) {
                        return@launch
                    }
                    // Compression count: how many times this session has been compacted
                    // (session.usage → compressions). Feeds the "compressed ×N" badge —
                    // the same usage snapshot the desktop status bar reads.
                    if (!isSwitchPending) {
                        try {
                            val usage =
                                rpc.call(
                                    RpcMethods.SESSION_USAGE,
                                    SessionIdParams(rpcSessionId),
                                )
                            coroutineContext.ensureActive()
                            val snapshot = parseUsageSnapshot(usage)
                            if (snapshot != null) {
                                uiState.update { current ->
                                    if (!isCurrentContextFetch(
                                            sessionId,
                                            targetSessionGeneration,
                                            targetModelGeneration,
                                            targetRequestSequence,
                                        )
                                    ) {
                                        current
                                    } else {
                                        applyUsageSnapshot(current, snapshot)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Best-effort: keep the last known badge value.
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                // Issue #817 & #1103: single atomic denominator write. The RPC's live
                // context_max wins, REST model/info is the fallback, and a stale
                // value from a pre-swap fetch can never overwrite a fresh one —
                // the meter always shows ONE coherent window.
                uiState.update { current ->
                    if (!isCurrentContextFetch(
                            sessionId,
                            targetSessionGeneration,
                            targetModelGeneration,
                            targetRequestSequence,
                        )
                    ) {
                        current
                    } else {
                        val fallbackFull =
                            if (skipRestFallback) current.fullContextTokens else restFull ?: current.fullContextTokens
                        current.copy(
                            usedContextTokens = rpcUsed ?: current.usedContextTokens,
                            fullContextTokens = rpcMax ?: fallbackFull,
                        )
                    }
                }
                // Detail-sheet accounting (cumulative REST counters, informational).
                coroutineContext.ensureActive()
                if (!isCurrentContextFetch(
                        sessionId,
                        targetSessionGeneration,
                        targetModelGeneration,
                        targetRequestSequence,
                    )
                ) {
                    return@launch
                }
                val usedResult =
                    safeApiCall { ApiClient.hermesApi.getSessionDetail(sessionId, profile) }
                coroutineContext.ensureActive()
                if (usedResult is NetworkResult.Success) {
                    val d = usedResult.data
                    val used = d.input_tokens
                    if (used != null) {
                        uiState.update { current ->
                            if (!isCurrentContextFetch(
                                    sessionId,
                                    targetSessionGeneration,
                                    targetModelGeneration,
                                    targetRequestSequence,
                                )
                            ) {
                                current
                            } else {
                                current.copy(
                                    contextBreakdown =
                                        ContextBreakdown(
                                            inputTokens = used,
                                            outputTokens = d.output_tokens ?: 0L,
                                            cacheReadTokens = d.cache_read_tokens ?: 0L,
                                            cacheWriteTokens = d.cache_write_tokens ?: 0L,
                                            reasoningTokens = d.reasoning_tokens ?: 0L,
                                            messageCount = d.message_count ?: 0,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
    }
}
