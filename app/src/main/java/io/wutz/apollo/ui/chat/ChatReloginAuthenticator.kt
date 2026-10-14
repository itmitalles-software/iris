package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.PasswordLoginClient
import io.wutz.apollo.data.remote.ServerEndpoint
import io.wutz.apollo.data.remote.WsTicketResult
import io.wutz.apollo.ui.authlogin.failureText
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-chat re-login after the connection reached AUTH_EXPIRED: the same password login and
 * ticket minting the login screen uses ([PasswordLoginClient]), with the same localized errors.
 */
internal class ChatReloginAuthenticator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: PasswordLoginClient = PasswordLoginClient(ioDispatcher = ioDispatcher),
    private val endpoint: () -> ServerEndpoint = AuthManager::endpointForBuild,
) {
    /** Signs in again and installs a fresh WebSocket ticket. Returns null on success, else the message to show. */
    suspend fun relogin(
        username: String,
        password: String,
    ): UiText? {
        val target =
            try {
                endpoint()
            } catch (e: IllegalArgumentException) {
                return UiText.res(R.string.connect_error_connection_failed, e.message ?: "")
            }
        client.login(target, username, password).failureText()?.let { return it }
        val ticket = client.mintTicket(target)
        if (ticket !is WsTicketResult.Success) return ticket.failureText()
        withContext(ioDispatcher) {
            AuthManager.setWsAuthParam("ticket")
            AuthManager.setToken(ticket.ticket)
        }
        return null
    }
}
