package io.wutz.apollo.ui.common

import io.wutz.apollo.R
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.httpStatusMessage

/**
 * The user-visible text for a failed request, in the app's language.
 *
 * [NetworkError.message] stays English for logs. Text that comes from the server (an HTTP error
 * `detail` or any non-generic HTTP message) or from the platform (an I/O exception message) is
 * shown verbatim.
 */
fun NetworkError.toUiText(): UiText =
    when (this) {
        is NetworkError.AuthExpired -> {
            UiText.res(R.string.network_error_auth_expired)
        }

        is NetworkError.Http -> {
            // A message other than the generic status text came from the server (or a repository
            // that read it from the response), so it is shown verbatim like a detail.
            val serverText = detail ?: message.takeIf { it != httpStatusMessage(code) }
            serverText?.takeIf { it.isNotBlank() }?.let(UiText::raw) ?: httpStatusText(code)
        }

        is NetworkError.Connection -> {
            cause.message
                ?.takeIf { it.isNotBlank() }
                ?.let { UiText.res(R.string.network_error_connection_detail, it) }
                ?: UiText.res(R.string.network_error_connection)
        }

        is NetworkError.Unknown -> {
            UiText.res(
                R.string.network_error_unexpected,
                cause.localizedMessage?.takeIf { it.isNotBlank() } ?: message,
            )
        }
    }

private fun httpStatusText(code: Int): UiText =
    when (code) {
        400 -> UiText.res(R.string.network_error_http_400)
        403 -> UiText.res(R.string.network_error_http_403)
        404 -> UiText.res(R.string.network_error_http_404)
        405 -> UiText.res(R.string.network_error_http_405)
        408 -> UiText.res(R.string.network_error_http_408)
        409 -> UiText.res(R.string.network_error_http_409)
        429 -> UiText.res(R.string.network_error_http_429)
        in 500..599 -> UiText.res(R.string.network_error_http_server, code)
        else -> UiText.res(R.string.network_error_http_other, code)
    }
