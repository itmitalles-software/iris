package io.wutz.apollo.notification

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.wutz.apollo.R
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.contract.ApprovalRespondParams
import io.wutz.apollo.data.ws.contract.RpcMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The pending approval a background notification announces. A JSON-RPC server request
 * ([serverRequestId]) is answered with a response frame; a legacy `approval.request` event is
 * answered with `approval.respond` for [requestId] in [sessionId].
 */
internal data class ApprovalNotificationTarget(
    val serverRequestId: String?,
    val requestId: String?,
    val sessionId: String?,
) {
    val isAnswerable: Boolean get() = serverRequestId != null || (requestId != null && sessionId != null)

    /** The id the chat screen knows the approval card by. */
    val cardId: String? get() = serverRequestId ?: requestId
}

/** Result of answering an approval from its notification. */
internal enum class ApprovalNotificationOutcome {
    ANSWERED,

    /** The device is locked (API < 31, where the system does not force an unlock first). */
    LOCKED,

    /** No live gateway connection: the user has to open the app. */
    DISCONNECTED,

    /** The gateway no longer waits for this approval (timed out, answered elsewhere). */
    EXPIRED,
    FAILED,
}

/** Approvals answered from a notification; the chat screen drops their cards. */
internal object NotificationApprovalAnswers {
    private val _answered =
        MutableSharedFlow<String>(replay = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val answered: SharedFlow<String> = _answered.asSharedFlow()

    fun publish(cardId: String) {
        _answered.tryEmit(cardId)
    }
}

/** The choices a notification offers: approve this one command once, or deny it. */
internal object ApprovalChoice {
    const val ONCE = "once"
    const val DENY = "deny"
}

/**
 * Answers an approval from its notification through the same paths the chat card uses. It never
 * approves while the device is locked and never claims success for a request the gateway already
 * withdrew.
 */
internal class ApprovalNotificationResponder(
    private val isConnected: () -> Boolean,
    private val isDeviceLocked: () -> Boolean,
    private val isRequestWithdrawn: (String) -> Boolean,
    private val respondToServerRequest: (String, JsonElement) -> Boolean,
    private val respondToLegacyApproval: suspend (ApprovalRespondParams) -> JsonElement,
) {
    suspend fun respond(
        target: ApprovalNotificationTarget,
        choice: String,
    ): ApprovalNotificationOutcome {
        if (!target.isAnswerable || (choice != ApprovalChoice.ONCE && choice != ApprovalChoice.DENY)) {
            return ApprovalNotificationOutcome.FAILED
        }
        if (isDeviceLocked()) return ApprovalNotificationOutcome.LOCKED
        if (!isConnected()) return ApprovalNotificationOutcome.DISCONNECTED
        val serverRequestId = target.serverRequestId
        if (serverRequestId != null) {
            if (isRequestWithdrawn(serverRequestId)) return ApprovalNotificationOutcome.EXPIRED
            val sent =
                respondToServerRequest(
                    serverRequestId,
                    buildJsonObject {
                        put("choice", choice)
                        put("all", false)
                    },
                )
            return if (sent) ApprovalNotificationOutcome.ANSWERED else ApprovalNotificationOutcome.DISCONNECTED
        }
        return try {
            val result =
                respondToLegacyApproval(
                    ApprovalRespondParams(
                        sessionId = checkNotNull(target.sessionId),
                        choice = choice,
                        all = false,
                        requestId = target.requestId,
                    ),
                )
            val resolved = (result as? JsonObject)?.get("resolved")?.jsonPrimitive?.int ?: 0
            if (resolved > 0) ApprovalNotificationOutcome.ANSWERED else ApprovalNotificationOutcome.EXPIRED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ApprovalNotificationOutcome.FAILED
        }
    }
}

/**
 * Receives the "Approve once" / "Deny" notification actions. On Android 12+ the actions require
 * the device to be unlocked before they fire (`setAuthenticationRequired`); older versions are
 * guarded by a keyguard check here. When the app is not connected, the notification asks the
 * user to open Iris instead (Android 12+ forbids starting the app from a notification action).
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_ANSWER_APPROVAL) return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val target =
            ApprovalNotificationTarget(
                serverRequestId = intent.getStringExtra(EXTRA_SERVER_REQUEST_ID),
                requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
            )
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = responder(appContext).respond(target, choice)
                Log.i(TAG, "Approval answered from notification: $outcome")
                if (outcome == ApprovalNotificationOutcome.ANSWERED) {
                    target.cardId?.let(NotificationApprovalAnswers::publish)
                    cancelIfShowing(appContext, target)
                } else {
                    postOutcome(appContext, target, outcome)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun responder(context: Context) =
        ApprovalNotificationResponder(
            isConnected = { HermesWsClient.isConnected },
            isDeviceLocked = {
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
            },
            isRequestWithdrawn = { id ->
                ChatNotificationService.activeServiceInstance?.isServerRequestWithdrawn(id) == true
            },
            respondToServerRequest = { id, result -> HermesWsClient.respondToServerRequest(id, result) },
            respondToLegacyApproval = { params ->
                HermesWsClient.call(RpcMethods.APPROVAL_RESPOND, params, timeoutMs = LEGACY_TIMEOUT_MS)
            },
        )

    private fun cancelIfShowing(
        context: Context,
        target: ApprovalNotificationTarget,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val posted =
            runCatching { manager.activeNotifications }
                .getOrNull()
                ?.firstOrNull { it.id == ChatNotificationService.PENDING_NOTIFICATION_ID && it.tag == null }
                ?: return
        // A newer prompt may have replaced this notification; only withdraw our own.
        if (posted.notification.extras.getString(EXTRA_APPROVAL_CARD_ID) == target.cardId) {
            manager.cancel(ChatNotificationService.PENDING_NOTIFICATION_ID)
        }
    }

    private fun postOutcome(
        context: Context,
        target: ApprovalNotificationTarget,
        outcome: ApprovalNotificationOutcome,
    ) {
        val text =
            context.getString(
                when (outcome) {
                    ApprovalNotificationOutcome.LOCKED -> R.string.notif_approval_unlock_first
                    ApprovalNotificationOutcome.EXPIRED -> R.string.notif_approval_expired
                    else -> R.string.notif_approval_open_app
                },
            )
        val builder =
            NotificationCompat
                .Builder(context, ChatNotificationService.CHAT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(ChatNotificationService.buildContentIntent(context, null))
                .addExtras(
                    android.os.Bundle().apply {
                        putString(ReplyNotificationTracker.EXTRA_NOTIF_KIND, ReplyNotificationTracker.KIND_ACTION)
                        target.cardId?.let { putString(EXTRA_APPROVAL_CARD_ID, it) }
                        target.serverRequestId?.let { putString(EXTRA_SERVER_REQUEST_ID, it) }
                    },
                )
        if (outcome == ApprovalNotificationOutcome.LOCKED) {
            approvalActions(context, target).forEach(builder::addAction)
        }
        ReplyNotificationTracker.postActionNotification(context, builder.build())
    }

    internal companion object {
        private const val TAG = "ApprovalActionReceiver"
        const val ACTION_ANSWER_APPROVAL = "io.wutz.apollo.ACTION_ANSWER_APPROVAL"
        const val EXTRA_CHOICE = "apollo_approval_choice"
        const val EXTRA_REQUEST_ID = "apollo_approval_request_id"
        const val EXTRA_SESSION_ID = "apollo_approval_session_id"

        /** Notification extra naming the approval card a notification belongs to. */
        const val EXTRA_APPROVAL_CARD_ID = "apollo_approval_card_id"
        private const val LEGACY_TIMEOUT_MS = 10_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** "Approve once" and "Deny" actions for [target]; empty when it cannot be answered. */
        fun approvalActions(
            context: Context,
            target: ApprovalNotificationTarget,
        ): List<NotificationCompat.Action> {
            if (!target.isAnswerable) return emptyList()
            return listOf(
                ApprovalChoice.ONCE to R.string.notif_action_approve_once,
                ApprovalChoice.DENY to R.string.notif_action_deny,
            ).map { (choice, label) ->
                val intent =
                    Intent(context, ApprovalActionReceiver::class.java)
                        .setAction(ACTION_ANSWER_APPROVAL)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_CHOICE, choice)
                        .putExtra(EXTRA_SERVER_REQUEST_ID, target.serverRequestId)
                        .putExtra(EXTRA_REQUEST_ID, target.requestId)
                        .putExtra(EXTRA_SESSION_ID, target.sessionId)
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        "${target.cardId}|$choice".hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                NotificationCompat.Action
                    .Builder(R.drawable.ic_notification, context.getString(label), pendingIntent)
                    // Android 12+: the lock screen cannot answer; the user unlocks first.
                    .setAuthenticationRequired(true)
                    .build()
            }
        }
    }
}
