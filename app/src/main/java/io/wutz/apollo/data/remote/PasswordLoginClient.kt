package io.wutz.apollo.data.remote

import io.wutz.apollo.data.ws.WsTicketResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Outcome of `POST /auth/password-login`. */
sealed interface PasswordLoginResult {
    /** The server accepted the credentials; its session cookie is now in the shared cookie jar. */
    data object Success : PasswordLoginResult

    /** The server answered with a non-2xx status (401: wrong credentials, 403: forbidden). */
    data class HttpError(
        val code: Int,
    ) : PasswordLoginResult

    /** The request did not get an HTTP answer (DNS, TLS, timeout, connection refused). */
    data class ConnectionFailed(
        val cause: IOException,
    ) : PasswordLoginResult
}

/** Outcome of `POST /api/auth/ws-ticket`. */
sealed interface WsTicketResult {
    data class Success(
        val ticket: String,
    ) : WsTicketResult

    /** The server answered with a non-2xx status (401/403: the session is gone). */
    data class HttpError(
        val code: Int,
    ) : WsTicketResult

    /** 2xx, but the body carried no usable ticket. */
    data object InvalidTicket : WsTicketResult

    /** The request did not get an HTTP answer. */
    data class ConnectionFailed(
        val cause: IOException,
    ) : WsTicketResult
}

/**
 * The single implementation of the gated-mode password login and of WebSocket ticket minting,
 * shared by the login screen, the in-chat re-login dialog and the WebSocket transport.
 *
 * Both calls use the shared [OkHttpProvider.probe] client: it carries the persistent cookie jar,
 * so the session cookie set by the login reaches the ticket request. A separate client would
 * not share the jar and the ticket request would fail.
 */
class PasswordLoginClient(
    private val clientProvider: () -> OkHttpClient = { OkHttpProvider.probe },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun login(
        endpoint: ServerEndpoint,
        username: String,
        password: String,
        provider: String = "basic",
    ): PasswordLoginResult {
        val request =
            Request
                .Builder()
                .url(endpoint.resolve("auth/password-login").toString())
                .post(AuthPayloads.passwordLogin(username, password, provider).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return try {
            withContext(ioDispatcher) {
                clientProvider().newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        PasswordLoginResult.Success
                    } else {
                        PasswordLoginResult.HttpError(response.code)
                    }
                }
            }
        } catch (e: IOException) {
            PasswordLoginResult.ConnectionFailed(e)
        }
    }

    /** Mints a WebSocket ticket from the session cookie that is already in the shared jar. */
    suspend fun mintTicket(endpoint: ServerEndpoint): WsTicketResult {
        val request =
            Request
                .Builder()
                .url(endpoint.resolve("api/auth/ws-ticket").toString())
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return try {
            withContext(ioDispatcher) {
                clientProvider().newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        WsTicketResult.HttpError(response.code)
                    } else {
                        parseWsTicket(response.body.string())
                            ?.let { WsTicketResult.Success(it) }
                            ?: WsTicketResult.InvalidTicket
                    }
                }
            }
        } catch (e: IOException) {
            WsTicketResult.ConnectionFailed(e)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Extracts the ticket from a `/api/auth/ws-ticket` body with a real JSON decoder. The ticket
         * is base64url and the body may escape characters, so a `"ticket":"([^"]+)"` regex is not
         * enough. Returns null for a missing, blank or unparseable ticket.
         */
        fun parseWsTicket(body: String): String? =
            runCatching { OkHttpProvider.json.decodeFromString<WsTicketResponse>(body) }
                .getOrNull()
                ?.ticket
                ?.takeIf { it.isNotBlank() }
    }
}
