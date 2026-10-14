package io.wutz.apollo.ui.authlogin

import io.wutz.apollo.R
import io.wutz.apollo.data.remote.PasswordLoginResult
import io.wutz.apollo.data.remote.WsTicketResult
import io.wutz.apollo.ui.common.UiText

/** The message the login screen and the re-login dialog show for a failed login, or null on success. */
internal fun PasswordLoginResult.failureText(): UiText? =
    when (this) {
        PasswordLoginResult.Success -> {
            null
        }

        is PasswordLoginResult.HttpError -> {
            when (code) {
                401 -> UiText.res(R.string.auth_error_invalid_credentials)
                403 -> UiText.res(R.string.connect_error_403)
                else -> UiText.res(R.string.connect_error_http_code, code)
            }
        }

        is PasswordLoginResult.ConnectionFailed -> {
            UiText.res(R.string.connect_error_connection_failed, cause.message ?: "")
        }
    }

/** The message for a failed WebSocket ticket request, or null when a ticket was minted. */
internal fun WsTicketResult.failureText(): UiText? =
    when (this) {
        is WsTicketResult.Success -> {
            null
        }

        is WsTicketResult.HttpError -> {
            UiText.res(R.string.auth_error_ws_ticket_http, code)
        }

        WsTicketResult.InvalidTicket -> {
            UiText.res(R.string.auth_login_msg_ws_ticket_failed)
        }

        is WsTicketResult.ConnectionFailed -> {
            UiText.res(R.string.connect_error_connection_failed, cause.message ?: "")
        }
    }
