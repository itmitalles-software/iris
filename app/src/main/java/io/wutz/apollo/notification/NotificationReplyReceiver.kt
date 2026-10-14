package io.wutz.apollo.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.session.ActiveSessionHolder
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.contract.DESKTOP_SESSION_SOURCE
import io.wutz.apollo.data.ws.contract.PromptSubmitParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SessionResumeParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

open class NotificationReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SESSION_ID = "extra_session_id"
        private const val REPLY_TIMEOUT_MS = 5_000L

        /** Overall deadline for persisting and submitting one notification reply. */
        private const val PERSISTENCE_TIMEOUT_MS = 5_000L

        /**
         * Turn-boundary read budget inside the reply deadline. Deliberately tight:
         * the probe sits on the reply path, so a slow or unreachable gateway must
         * cost the user's reply only a sliver of its 5s deadline.
         */
        private const val BOUNDARY_TIMEOUT_MS = 250L
    }

    // Reusable scope for async reply processing — avoids creating a new
    // unmanaged CoroutineScope per broadcast fire. (PERF-15)
    private val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Test-friendly wrapper for [BroadcastReceiver.goAsync] which is `final`
     * (Java) and cannot be mocked or overridden directly. Tests override this
     * via anonymous subclass to inject a fake [PendingResult].
     */
    internal open fun goAsyncCompat(): BroadcastReceiver.PendingResult = goAsync()

    /** Overridable so tests can prove the timeout path without waiting 5s. */
    internal open val persistenceTimeoutMs: Long get() = PERSISTENCE_TIMEOUT_MS

    /**
     * Test-friendly wrapper for notification creation. Override in tests to
     * avoid [NotificationCompat.Builder.build()] calling Android framework
     * methods that throw "not mocked" in unit tests.
     */
    internal open fun buildReplyNotification(context: Context): Notification =
        NotificationCompat
            .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_replied))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addExtras(
                android.os.Bundle().apply {
                    putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, ReplyNotificationTracker.KIND_REPLIED)
                },
            ).build()

    /**
     * Replaces the inline-reply spinner when a reply was NOT sent: shows the
     * typed text so it is not lost and offers the reply action again (the
     * previous ONE_SHOT PendingIntent is consumed). Overridable for tests.
     */
    internal open fun buildReplyFailedNotification(
        context: Context,
        replyText: String?,
        sessionId: String?,
    ): Notification {
        val text =
            if (replyText.isNullOrBlank()) {
                context.getString(R.string.notif_reply_failed)
            } else {
                context.getString(R.string.notif_reply_failed_with_text, replyText)
            }
        val builder =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(ChatNotificationService.buildContentIntent(context, sessionId))
                .addExtras(
                    android.os.Bundle().apply {
                        putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, ReplyNotificationTracker.KIND_ACTION)
                    },
                )
        if (!sessionId.isNullOrBlank()) {
            builder.addAction(ChatNotificationService.buildInlineReplyAction(context, sessionId))
        }
        return builder.build()
    }

    private fun postReplyFailed(
        context: Context,
        replyText: String?,
        sessionId: String?,
    ) {
        try {
            ReplyNotificationTracker.postActionNotification(
                context,
                buildReplyFailedNotification(context, replyText, sessionId),
            )
        } catch (e: Exception) {
            android.util.Log.e("NotificationReply", "Failed to post reply failure notification", e)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        if (replyText.isNullOrBlank() || sessionId.isNullOrBlank()) {
            // The system keeps showing the reply spinner until the notification
            // is re-posted, so even an unusable reply must replace it.
            postReplyFailed(context, replyText, sessionId)
            return
        }
        val pendingResult = goAsyncCompat()
        replyScope.launch {
            // Once prompt.submit succeeded the reply is on the server: a later
            // local failure must not tell the user it was not sent.
            var submitted = false
            try {
                withTimeout(persistenceTimeoutMs) {
                    withContext(Dispatchers.IO) {
                        val db =
                            io.wutz.apollo.data.local.HermesDatabase
                                .get(context)
                        val dao = db.chatMessageDao()
                        if (!dao.sessionExists(sessionId)) {
                            android.util.Log.w(
                                "NotificationReply",
                                "Ignoring reply for unknown session: $sessionId",
                            )
                            // No retry action: replying to a deleted session would fail again.
                            postReplyFailed(context, replyText, sessionId = null)
                            return@withContext
                        }

                        // Mobile-originated follow-up turn: arm its durable
                        // boundary BEFORE prompt.submit. Budgeted tighter
                        // than the chat composer's — this receiver has its
                        // own 5s deadline and a failed capture must only
                        // make the reply uncorrelatable, never lose it.
                        captureTurnBoundary(
                            scopeId = correlationScopeId(),
                            sessionId = sessionId,
                            timeoutMs = BOUNDARY_TIMEOUT_MS,
                        )

                        val runtimeSessionId =
                            ActiveSessionHolder.resolveRuntimeSessionId(sessionId)
                                ?: resumeSession(sessionId)
                        HermesWsClient.call(
                            method = RpcMethods.PROMPT_SUBMIT,
                            params = PromptSubmitParams(sessionId = runtimeSessionId, text = replyText),
                            timeoutMs = REPLY_TIMEOUT_MS,
                        )
                        submitted = true

                        val entity =
                            io.wutz.apollo.data.local.ChatMessageEntity(
                                id =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                sessionId = sessionId,
                                role = "USER",
                                content = replyText,
                                timestamp = System.currentTimeMillis(),
                            )
                        dao.upsert(entity)

                        val repliedNotification = buildReplyNotification(context)
                        ReplyNotificationTracker.postRepliedNotification(context, repliedNotification)

                        // The follow-up turn is now pending — keep the
                        // foreground service alive if it retired after the
                        // previous reply completed, so the next
                        // MessageComplete still notifies while backgrounded
                        // (issue #794). No-op when the app is foreground.
                        if (!NotificationHelper.isAppInForeground()) {
                            NotificationHelper.start(context)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationReply", "Failed to process reply", e)
                if (submitted) {
                    runCatching {
                        ReplyNotificationTracker.postRepliedNotification(context, buildReplyNotification(context))
                    }
                } else {
                    postReplyFailed(context, replyText, sessionId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun resumeSession(storedSessionId: String): String {
        val profile = AuthManager.activeProfileId.value?.takeIf { it.isNotBlank() }
        val params =
            SessionResumeParams(
                sessionId = storedSessionId,
                source = DESKTOP_SESSION_SOURCE,
                omitMessages = true,
                profile = profile,
            )
        val result =
            HermesWsClient.call(
                method = RpcMethods.SESSION_RESUME,
                params = params,
                timeoutMs = REPLY_TIMEOUT_MS,
            )
        val runtimeSessionId =
            result.sessionId?.takeIf { it.isNotBlank() }
                ?: error("Resume returned no runtime session id")
        ActiveSessionHolder.set(runtimeSessionId, storedSessionId)
        return runtimeSessionId
    }
}
