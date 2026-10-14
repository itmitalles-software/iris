package io.wutz.apollo.data.ws

import android.util.Log
import androidx.annotation.VisibleForTesting
import io.wutz.apollo.BuildConfig
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.CookieManager
import io.wutz.apollo.data.remote.DashboardSessionTokenRefresher
import io.wutz.apollo.data.remote.NetworkMonitor
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.PasswordLoginClient
import io.wutz.apollo.data.remote.WsTicketResult
import io.wutz.apollo.data.remote.await
import io.wutz.apollo.data.session.ActiveSessionHolder
import io.wutz.apollo.data.ws.contract.PromptSubmitParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SessionCorrectionParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.utf8Size
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A minted side-socket credential, or a failure flagged [retryable] when it is transient. */
internal data class WsCredentialResult(
    val credential: String?,
    val retryable: Boolean,
)

/**
 * Socket lifecycle, authentication, outbound queueing, and network transport management.
 *
 * Extracted from [HermesWsClient] (issue #1376).
 */
internal class WsTransport(
    private val scope: CoroutineScope,
    private val outboundLock: Any,
    private val reconnectPolicy: ReconnectPolicy,
    private val connectionHealth: ConnectionHealth,
    private val replayTracker: ReplayTracker,
    private val rpcChannel: RpcChannel,
    private val onRawMessage: (String) -> Unit,
) {
    companion object {
        private const val TAG = "HermesWsClient"
        private const val MAX_OUTBOUND_MESSAGE_BYTES = 16 * 1024 * 1024
        private const val OUTBOUND_DRAIN_TIMEOUT_MS = 60_000L
        private const val HTTP_UNAUTHORIZED = 401
    }

    private val connectionGeneration = AtomicInteger(0)
    private val connected = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val acceptQueuedMessages = AtomicBoolean(true)
    private val appInForeground = AtomicBoolean(true)
    private val externalActivityConnectionLease = AtomicBoolean(false)
    private val backgroundConnectionLease = AtomicBoolean(false)
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private val passwordLoginClient = PasswordLoginClient()
    private val queuedMessagesById = ConcurrentHashMap<String, String>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closingSocket: WebSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var outboundDrainJob: Job? = null

    /** In-flight handshakes from [openSocket]; [disconnect] cancels them. */
    private val openSocketJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    private val networkCollectorStarted = AtomicBoolean(false)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    @Volatile
    var pendingReply: Boolean = false
        internal set

    private val pendingPromptSubmits = ConcurrentHashMap.newKeySet<String>()

    val isConnected: Boolean get() = connected.get()

    fun setAppForeground(foreground: Boolean) {
        appInForeground.set(foreground)
        if (!foreground) {
            disconnectIfIdleInBackground()
            return
        }
        connectionHealth.probeLivenessOnWake()
    }

    fun acquireExternalActivityConnectionLease() {
        externalActivityConnectionLease.set(true)
    }

    fun releaseExternalActivityConnectionLease() {
        externalActivityConnectionLease.set(false)
        disconnectIfIdleInBackground()
    }

    fun acquireBackgroundConnectionLease() {
        backgroundConnectionLease.set(true)
    }

    fun releaseBackgroundConnectionLease() {
        backgroundConnectionLease.set(false)
        disconnectIfIdleInBackground()
    }

    internal fun hasBackgroundConnectionLease(): Boolean = backgroundConnectionLease.get()

    fun disconnectIfIdleInBackground() {
        synchronized(outboundLock) {
            if (!appInForeground.get() &&
                !externalActivityConnectionLease.get() &&
                !backgroundConnectionLease.get() &&
                !pendingReply &&
                !rpcChannel.hasPendingCalls() &&
                messageQueue.isEmpty()
            ) {
                disconnect()
            }
        }
    }

    fun connect() {
        if (networkCollectorStarted.compareAndSet(false, true)) {
            scope.launch {
                NetworkMonitor.networkChanges.collect { networkAvailable ->
                    reconnectForNetworkChange(networkAvailable)
                }
            }
            scope.launch {
                NetworkMonitor.transportChanges.collect {
                    connectionHealth.probeLivenessOnTransportChange(
                        onFailureAction = { cancelSocket() },
                    )
                }
            }
        }
        var socketToCancel: WebSocket? = null
        val shouldOpen =
            synchronized(outboundLock) {
                acceptQueuedMessages.set(true)
                if (_connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                    connectionGeneration.incrementAndGet()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    outboundDrainJob?.cancel()
                    outboundDrainJob = null
                    socketToCancel = webSocket
                    webSocket = null
                    closingSocket = null
                    connected.set(false)
                    connectionHealth.stop()
                }
                if (connected.get()) {
                    Log.d(TAG, "Already connected — skipping")
                    return@synchronized false
                }
                if (_connectionStatus.value == ConnectionStatus.CONNECTING ||
                    _connectionStatus.value == ConnectionStatus.RECONNECTING
                ) {
                    Log.d(TAG, "Connection already in flight (${_connectionStatus.value}) — skipping")
                    return@synchronized false
                }
                // An explicit connect follows successful authentication and is
                // therefore allowed to leave the terminal AUTH_EXPIRED state.
                intentionalClose.set(false)
                _connectionStatus.value = ConnectionStatus.CONNECTING
                reconnectPolicy.reset()
                true
            }
        if (socketToCancel != null) {
            rpcChannel.rejectAllPending()
            socketToCancel.cancel()
        }
        if (shouldOpen) openSocket()
    }

    fun reconnectForNetworkChange(
        networkAvailable: Boolean = NetworkMonitor.isConnected.value,
        openReplacement: () -> Unit = ::openSocket,
    ) {
        val socketToCancel: WebSocket?
        val shouldOpen: Boolean
        synchronized(outboundLock) {
            if (intentionalClose.get() || _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED) {
                return
            }
            Log.d(TAG, "Default network changed — reconnecting WebSocket")
            reconnectPolicy.reset()
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            connectionGeneration.incrementAndGet()
            socketToCancel = webSocket
            webSocket = null
            closingSocket = null
            connected.set(false)
            connectionHealth.stop()
            shouldOpen = networkAvailable && AuthManager.isAutoReconnect()
            _connectionStatus.value =
                when {
                    !networkAvailable -> ConnectionStatus.NO_NETWORK
                    shouldOpen -> ConnectionStatus.RECONNECTING
                    else -> ConnectionStatus.DISCONNECTED
                }
            rpcChannel.rejectAllPending()
        }
        socketToCancel?.cancel()
        if (shouldOpen) openReplacement()
    }

    suspend fun refreshWsTicketIfNeeded(generation: Int): Boolean {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                // serverStore not initialized yet (transient early call); treat
                // as loopback so a stale-token refresh still runs. Log so a real
                // misconfiguration isn't silently swallowed.
                Log.w(TAG, "serverStore uninitialized during WS handshake; assuming non-gated")
                false
            }
        if (!isGated) {
            // The loopback dashboard token is regenerated on every server
            // restart. Refresh it before each WebSocket handshake so automatic
            // reconnect does not get stuck in AUTH_EXPIRED with a stale token.
            val token =
                runCatching {
                    DashboardSessionTokenRefresher.refreshAsync()
                }.getOrNull()
            synchronized(outboundLock) {
                if (isCurrentGeneration(generation) && token != null) {
                    runCatching { AuthManager.setToken(token) }
                }
            }
            return true
        }

        val ticketResult = requestWsTicket()
        if (!ticketResult.ticket.isNullOrBlank()) {
            synchronized(outboundLock) {
                if (!isCurrentGeneration(generation)) return false
                AuthManager.setToken(ticketResult.ticket)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "WS ticket refreshed")
            return true
        }
        val status =
            when {
                ticketResult.isTransient -> ConnectionStatus.RECONNECTING
                ticketResult.httpCode == 401 || ticketResult.httpCode == 403 -> ConnectionStatus.AUTH_EXPIRED
                else -> ConnectionStatus.DISCONNECTED
            }
        return handleWsTicketRefreshFailure(status, generation)
    }

    suspend fun mintWsTicket(): String? = mintWsCredential().credential

    /**
     * Fresh credential for a side socket, plus whether a failure is worth retrying:
     * network errors, timeouts, 408/429 and 5xx are transient; a rejected session
     * (401/403) or a missing token is not.
     */
    suspend fun mintWsCredential(): WsCredentialResult {
        val isGated =
            try {
                AuthManager.serverStore.getLatestState().wsAuthParam == "ticket"
            } catch (_: IllegalStateException) {
                false
            }
        if (!isGated) {
            DashboardSessionTokenRefresher.refreshAsync()
            return WsCredentialResult(AuthManager.getToken(), retryable = false)
        }
        val result = requestWsTicket()
        return WsCredentialResult(result.ticket, retryable = result.isTransient)
    }

    private data class TicketRequestResult(
        val ticket: String?,
        val httpCode: Int?,
        val exception: Boolean = false,
    ) {
        /** A network failure or a status that says "try again later". */
        val isTransient: Boolean
            get() = exception || httpCode == 408 || httpCode == 429 || (httpCode != null && httpCode >= 500)
    }

    private suspend fun requestWsTicket(): TicketRequestResult {
        try {
            val endpoint = AuthManager.endpointForBuild()
            if (CookieManager.isInitialized()) {
                withContext(Dispatchers.IO) {
                    CookieManager.useStore(CookieManager.cookieJar.currentServer())
                }
            }
            return when (val result = passwordLoginClient.mintTicket(endpoint)) {
                is WsTicketResult.Success -> {
                    TicketRequestResult(result.ticket, null)
                }

                is WsTicketResult.HttpError -> {
                    Log.w(TAG, "WS ticket mint failed: HTTP ${result.code}")
                    TicketRequestResult(null, result.code)
                }

                WsTicketResult.InvalidTicket -> {
                    Log.w(TAG, "WS ticket mint failed: unparseable ticket response")
                    TicketRequestResult(null, null)
                }

                is WsTicketResult.ConnectionFailed -> {
                    Log.w(TAG, "WS ticket mint failed: ${result.cause.javaClass.simpleName}", result.cause)
                    TicketRequestResult(null, null, exception = true)
                }
            }
        } catch (e: Exception) {
            // Include the stack: the exception class alone (e.g.
            // NetworkOnMainThreadException) can't show WHICH caller on what
            // thread tripped the mint (main-scope kanban events connect vs
            // the WS reconnect path).
            Log.w(TAG, "WS ticket mint failed: ${e.javaClass.simpleName}", e)
            return TicketRequestResult(null, null, exception = true)
        }
    }

    private fun handleWsTicketRefreshFailure(
        status: ConnectionStatus,
        generation: Int,
    ): Boolean {
        synchronized(outboundLock) {
            if (!isCurrentGeneration(generation)) return false
            _connectionStatus.value = status
            if (status == ConnectionStatus.RECONNECTING) scheduleReconnect()
        }
        return false
    }

    fun disconnect(clearPendingMessages: Boolean = false) {
        ActiveSessionHolder.clear()
        synchronized(outboundLock) {
            intentionalClose.set(true)
            acceptQueuedMessages.set(!clearPendingMessages)
            connectionGeneration.incrementAndGet()
            reconnectJob?.cancel()
            reconnectJob = null
            outboundDrainJob?.cancel()
            outboundDrainJob = null
            openSocketJobs.forEach { it.cancel() }
            connectionHealth.cancelForegroundProbe()
            connectionHealth.stop()
            webSocket?.close(1000, "Client closed")
            webSocket = null
            closingSocket = null
            rpcChannel.clearCapabilityRequests()
            if (clearPendingMessages) {
                backgroundConnectionLease.set(false)
                messageQueue.clear()
                queuedMessagesById.clear()
                pendingPromptSubmits.clear()
                pendingReply = false
                replayTracker.clear()
            }
            connected.set(false)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    fun cancelSocket() {
        synchronized(outboundLock) {
            if (connected.get()) webSocket?.cancel()
        }
    }

    fun sendDirectFrame(frameJson: String): Boolean =
        synchronized(outboundLock) {
            val ws = webSocket
            if (ws == null || !connected.get()) false else ws.send(frameJson)
        }

    fun send(
        request: JsonRpcRequest,
        onSent: ((String) -> Unit)? = null,
        queueIfDisconnected: Boolean = true,
    ): String {
        val id = request.id
        val method = request.method
        val json = rpcChannel.encodeRequest(request)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, formatSafeOutgoingFrameLog(id, method, request.params.keys, json.length, queued = false))
        }
        var reconnect = false
        synchronized(outboundLock) {
            if (method == WsMethods.PROMPT_SUBMIT) {
                pendingPromptSubmits.add(id)
                pendingReply = true
            }
            val ws = webSocket
            if (ws != null && connected.get()) {
                // Never replay send(true): the server may have executed it even if its response is lost.
                if (ws.send(json)) {
                    onSent?.invoke(id)
                } else {
                    if (webSocket !== ws || !acceptQueuedMessages.get()) return@synchronized
                    if (queueIfDisconnected && isRetryableMessage(json)) {
                        onSent?.invoke(id)
                        Log.w(TAG, "WS rejected outgoing message — queuing for reconnect")
                        queueMessage(id, json)
                        recoverRejectedSocket(ws)
                    } else {
                        Log.w(TAG, "WS rejected oversized outgoing message — not retrying")
                    }
                }
            } else if (acceptQueuedMessages.get() && queueIfDisconnected) {
                if (isRetryableMessage(json)) {
                    onSent?.invoke(id)
                    Log.d(TAG, "WS disconnected — queuing message")
                    queueMessage(id, json)
                    reconnect = true
                } else {
                    Log.w(TAG, "WS disconnected with oversized outgoing message — not queueing")
                }
            }
        }
        if (reconnect) connect()
        return id
    }

    fun removeQueuedMessage(id: String) {
        synchronized(outboundLock) {
            queuedMessagesById.remove(id)?.let(messageQueue::remove)
        }
    }

    fun onRpcResult(id: String) {
        synchronized(outboundLock) {
            pendingPromptSubmits.remove(id)
        }
    }

    fun onRpcError(id: String) {
        synchronized(outboundLock) {
            if (pendingPromptSubmits.remove(id) && pendingPromptSubmits.isEmpty()) {
                pendingReply = false
                disconnectIfIdleInBackground()
            }
        }
    }

    fun observeEvent(event: WsEvent) {
        when (event) {
            is WsEvent.MessageStart,
            is WsEvent.MessageToken,
            is WsEvent.ThinkingDelta,
            is WsEvent.ReasoningDelta,
            is WsEvent.ToolStart,
            -> {
                pendingReply = true
            }

            is WsEvent.MessageComplete -> {
                pendingReply = false
                disconnectIfIdleInBackground()
            }

            else -> {}
        }
    }

    private fun isRetryableMessage(json: String): Boolean {
        if (json.length > MAX_OUTBOUND_MESSAGE_BYTES) return false
        if (json.length <= MAX_OUTBOUND_MESSAGE_BYTES / 4) return true
        return json.utf8Size() <= MAX_OUTBOUND_MESSAGE_BYTES.toLong()
    }

    private fun queueMessage(
        id: String,
        json: String,
    ) {
        if (queuedMessagesById.putIfAbsent(id, json) == null) messageQueue.add(json)
    }

    private fun markQueuedMessageSent(json: String) {
        queuedMessagesById.entries.removeIf { it.value == json }
    }

    private fun recoverRejectedSocket(ws: WebSocket) {
        connected.set(false)
        if (closingSocket === ws) return
        _connectionStatus.value = ConnectionStatus.RECONNECTING
        if (ws.queueSize() == 0L) {
            ws.cancel()
            scheduleReconnect()
            return
        }
        if (intentionalClose.get()) return
        outboundDrainJob?.cancel()
        outboundDrainJob =
            scope.launch {
                delay(OUTBOUND_DRAIN_TIMEOUT_MS)
                synchronized(outboundLock) {
                    if (!connected.get() && !intentionalClose.get() && webSocket === ws) {
                        ws.cancel()
                        outboundDrainJob = null
                        scheduleReconnect()
                    }
                }
            }
    }

    fun sendMessage(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
        queued: Boolean = false,
    ): String =
        send(
            rpcChannel.createRequest(
                method = RpcMethods.PROMPT_SUBMIT.name,
                params =
                    PromptSubmitParams(
                        sessionId = sessionId,
                        text = text,
                        queued = queued.takeIf { it },
                    ).let { rpcChannel.encodeParams(RpcMethods.PROMPT_SUBMIT, it) },
            ),
            onSent = onSent,
        )

    fun sendRedirect(
        sessionId: String,
        text: String,
        onSent: ((String) -> Unit)? = null,
    ): String =
        send(
            rpcChannel.createRequest(
                method = RpcMethods.SESSION_REDIRECT.name,
                params =
                    SessionCorrectionParams(
                        sessionId = sessionId,
                        text = text,
                    ).let { rpcChannel.encodeParams(RpcMethods.SESSION_REDIRECT, it) },
            ),
            onSent = onSent,
        )

    internal fun openSocket() {
        val generation =
            synchronized(outboundLock) {
                if (intentionalClose.get()) return
                connectionGeneration.incrementAndGet()
            }
        val job =
            scope.launch(Dispatchers.IO) {
                if (!isCurrentGeneration(generation)) return@launch
                if (!refreshWsTicketIfNeeded(generation)) {
                    Log.w(TAG, "Aborting openSocket: WS ticket refresh failed")
                    return@launch
                }
                if (!isCurrentGeneration(generation)) return@launch
                val url = AuthManager.wsUrl()
                val safeUrl = url.replace(Regex("token=[^&]+"), "token=REDACTED")
                if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to $safeUrl")

                val request = Request.Builder().url(url).build()
                val newSocket = OkHttpProvider.websocket.newWebSocket(request, WsListenerImpl(generation))
                synchronized(outboundLock) {
                    if (connectionGeneration.get() == generation && !intentionalClose.get()) {
                        webSocket = newSocket
                    } else {
                        newSocket.cancel()
                    }
                }
            }
        openSocketJobs.add(job)
        job.invokeOnCompletion { openSocketJobs.remove(job) }
    }

    @VisibleForTesting
    internal suspend fun awaitOpenSocketForTest() {
        // Iterate the concurrent set directly: toList() can throw NoSuchElementException when a job
        // completes (and removes itself) between its size check and the read.
        for (job in openSocketJobs) job.join()
    }

    private fun isCurrentGeneration(generation: Int): Boolean =
        connectionGeneration.get() == generation && !intentionalClose.get()

    private fun scheduleReconnect() {
        synchronized(outboundLock) {
            if (intentionalClose.get() ||
                _connectionStatus.value == ConnectionStatus.AUTH_EXPIRED ||
                reconnectJob?.isActive == true
            ) {
                return
            }
            if (!AuthManager.isAutoReconnect()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Auto-reconnect disabled")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                return
            }
            if (!NetworkMonitor.isConnected.value) {
                Log.d(TAG, "No network available — delaying reconnect scheduling")
                _connectionStatus.value = ConnectionStatus.NO_NETWORK
                return
            }
            val reconnectDelay = reconnectPolicy.nextDelayMs()
            reconnectPolicy.advance()
            if (BuildConfig.DEBUG) Log.d(TAG, "Reconnecting in ${reconnectDelay}ms …")

            reconnectJob =
                scope.launch {
                    delay(reconnectDelay)
                    val owner = currentCoroutineContext()[Job]
                    val shouldOpen =
                        synchronized(outboundLock) {
                            if (reconnectJob !== owner) {
                                false
                            } else {
                                reconnectJob = null
                                !intentionalClose.get() &&
                                    !connected.get() &&
                                    _connectionStatus.value != ConnectionStatus.AUTH_EXPIRED
                            }
                        }
                    if (shouldOpen) openSocket()
                }
        }
    }

    private fun isTerminalAuthClose(
        code: Int,
        reason: String,
    ): Boolean =
        code == 4401 || code == 4403 ||
            reason.contains("unauthorized", ignoreCase = true) ||
            reason.startsWith("auth:", ignoreCase = true)

    private inner class WsListenerImpl(
        private val generation: Int,
    ) : WebSocketListener() {
        private fun isCurrent(): Boolean = isCurrentGeneration(generation)

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(1000, "Superseded")
                    return
                }
                this@WsTransport.webSocket = webSocket
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                Log.i(TAG, "WebSocket opened")
                connected.set(true)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                reconnectPolicy.reset()
                connectionHealth.start()

                while (true) {
                    val msg = messageQueue.peek() ?: break
                    if (!isRetryableMessage(msg)) {
                        Log.w(TAG, "Dropping oversized queued message")
                        messageQueue.poll()
                        markQueuedMessageSent(msg)
                        continue
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, formatSafeQueuedFrameLog(msg))
                    if (!webSocket.send(msg)) {
                        recoverRejectedSocket(webSocket)
                        break
                    }
                    messageQueue.poll()
                    markQueuedMessageSent(msg)
                }
                replayTracker.triggerReplay()
            }
            disconnectIfIdleInBackground()
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (!isCurrent() || this@WsTransport.webSocket !== webSocket) return
            if (BuildConfig.DEBUG) Log.d(TAG, formatSafeIncomingFrameLog(text))
            connectionHealth.onInboundFrame()
            onRawMessage(text)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) {
                    webSocket.close(code, reason)
                    return
                }
                closingSocket = webSocket
                // Do NOT log [reason] — it may carry server-side context.
                Log.d(TAG, "WebSocket closing: $code")
                if (isTerminalAuthClose(code, reason)) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                }
                webSocket.close(code, reason)
            }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (this@WsTransport.webSocket === webSocket) this@WsTransport.webSocket = null
                // Do NOT log [reason] — it may carry server-side context. The
                // reason is still inspected internally to detect auth failures.
                Log.i(TAG, "WebSocket closed: $code")
                connected.set(false)
                ActiveSessionHolder.clear()
                connectionHealth.stop()
                rpcChannel.clearCapabilityRequests()
                if (isTerminalAuthClose(code, reason)) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            synchronized(outboundLock) {
                if (!isCurrent()) return
                connectionGeneration.incrementAndGet()
                closingSocket = null
                outboundDrainJob?.cancel()
                outboundDrainJob = null
                if (this@WsTransport.webSocket === webSocket) this@WsTransport.webSocket = null
                // Log the exception class only — [Throwable.message] can leak URLs
                // or headers.
                Log.e(TAG, "WebSocket failure: ${t.javaClass.simpleName}", t)
                connected.set(false)
                ActiveSessionHolder.clear()
                connectionHealth.stop()
                rpcChannel.clearCapabilityRequests()
                // A rejected handshake carries its HTTP response. Never match digits in the
                // exception message: it holds hosts, IPs and ports (e.g. 10.0.4.401, :4401).
                // Auth close codes (4401/4403) arrive through onClosing/onClosed.
                if (response?.code == HTTP_UNAUTHORIZED) {
                    _connectionStatus.value = ConnectionStatus.AUTH_EXPIRED
                } else if (_connectionStatus.value != ConnectionStatus.AUTH_EXPIRED) {
                    _connectionStatus.value = ConnectionStatus.RECONNECTING
                    scheduleReconnect()
                }
            }
        }
    }

    // ── Test seams (issue #1376 extraction support) ──────────────────────

    @VisibleForTesting
    internal val connectedForTest: AtomicBoolean
        get() = connected

    @VisibleForTesting
    internal val intentionalCloseForTest: AtomicBoolean
        get() = intentionalClose

    @VisibleForTesting
    internal val connectionGenerationForTest: AtomicInteger
        get() = connectionGeneration

    @VisibleForTesting
    internal val messageQueueForTest: ConcurrentLinkedQueue<String>
        get() = messageQueue

    @VisibleForTesting
    internal val outboundLockForTest: Any
        get() = outboundLock

    @VisibleForTesting
    internal val acceptQueuedMessagesForTest: AtomicBoolean
        get() = acceptQueuedMessages

    @VisibleForTesting
    internal val connectionStatusForTest: MutableStateFlow<ConnectionStatus>
        get() = _connectionStatus

    @VisibleForTesting
    internal var webSocketForTest: WebSocket?
        get() = webSocket
        set(value) {
            webSocket = value
        }

    @VisibleForTesting
    internal var currentBackoffForTest: Long
        get() = synchronized(outboundLock) { reconnectPolicy.currentBackoff }
        set(value) {
            synchronized(outboundLock) {
                reconnectPolicy.currentBackoff = value
            }
        }

    @VisibleForTesting
    internal var reconnectJobForTest: Job?
        get() = reconnectJob
        set(value) {
            reconnectJob = value
        }

    @VisibleForTesting
    internal var outboundDrainJobForTest: Job?
        get() = outboundDrainJob
        set(value) {
            outboundDrainJob = value
        }

    @VisibleForTesting
    internal fun setReconnectBackoffForTest(initialMillis: Long) {
        synchronized(outboundLock) {
            reconnectPolicy.setBackoffForTest(initialMillis)
        }
    }

    @VisibleForTesting
    internal fun scheduleReconnectForTest() {
        scheduleReconnect()
    }

    @VisibleForTesting
    internal fun createListenerForTest(generation: Int): WebSocketListener = WsListenerImpl(generation)

    @VisibleForTesting
    internal fun setConnectionStatusForTest(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    @VisibleForTesting
    internal fun formatSafeOutgoingFrameLog(
        id: String,
        method: String,
        paramsKeys: Collection<String>?,
        byteLength: Int,
        queued: Boolean = false,
    ): String {
        val prefix = if (queued) "→ (queued)" else "→"
        val keys = paramsKeys?.joinToString(",", prefix = "[", postfix = "]") ?: "[]"
        return "$prefix id=$id method=$method paramsKeys=$keys bytes=$byteLength"
    }

    @VisibleForTesting
    internal fun formatSafeQueuedFrameLog(msg: String): String =
        try {
            val req = OkHttpProvider.json.decodeFromString<JsonRpcRequest>(msg)
            formatSafeOutgoingFrameLog(req.id, req.method, req.params.keys, msg.length, queued = true)
        } catch (_: Throwable) {
            "→ (queued) frame bytes=${msg.length}"
        }

    @VisibleForTesting
    internal fun formatSafeIncomingFrameLog(text: String): String =
        try {
            val element = OkHttpProvider.json.parseToJsonElement(text)
            if (element is JsonObject) {
                val id = (element["id"] as? JsonPrimitive)?.content
                val method = (element["method"] as? JsonPrimitive)?.content
                val hasResult = element.containsKey("result")
                val resultKeys =
                    (element["result"] as? JsonObject)?.keys?.joinToString(
                        ",",
                        prefix = "[",
                        postfix = "]",
                    )
                val errorObj = element["error"] as? JsonObject
                val errorCode = (errorObj?.get("code") as? JsonPrimitive)?.content
                val sb = StringBuilder("← ")
                if (id != null) sb.append("id=").append(id).append(" ")
                if (method != null) sb.append("method=").append(method).append(" ")
                if (hasResult) sb.append("result=").append(resultKeys ?: "present").append(" ")
                if (errorCode != null) sb.append("errorCode=").append(errorCode).append(" ")
                sb.append("bytes=").append(text.length).toString()
            } else {
                "← non-object frame bytes=${text.length}"
            }
        } catch (_: Throwable) {
            "← frame bytes=${text.length}"
        }
}
