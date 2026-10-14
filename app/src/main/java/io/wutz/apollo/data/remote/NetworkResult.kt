package io.wutz.apollo.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException

sealed interface NetworkResult<out T> {
    data class Success<out T>(
        val data: T,
    ) : NetworkResult<T>

    data class Failure(
        val error: NetworkError,
    ) : NetworkResult<Nothing>
}

sealed interface NetworkError {
    val message: String

    /** [detail] is the server's own explanation (FastAPI `detail`), when the error body had one. */
    data class Http(
        val code: Int,
        override val message: String,
        val detail: String? = null,
    ) : NetworkError

    data class Connection(
        override val message: String,
        val cause: IOException,
    ) : NetworkError

    data class AuthExpired(
        override val message: String = "Authentication token has expired. Please log in again.",
    ) : NetworkError

    data class Unknown(
        override val message: String,
        val cause: Throwable,
    ) : NetworkError
}

fun mapHttpError(
    code: Int,
    errorBody: String? = null,
): NetworkError {
    if (code == 401) {
        return NetworkError.AuthExpired()
    }
    // Try to parse FastAPI-style error detail from response body
    val detail =
        if (errorBody != null) {
            try {
                val parsed = OkHttpProvider.json.parseToJsonElement(errorBody).jsonObject
                parsed["detail"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    return NetworkError.Http(code, detail ?: httpStatusMessage(code), detail)
}

/**
 * The English status text [mapHttpError] uses when the server sent no detail (for logs; the UI
 * shows the localized equivalent).
 */
fun httpStatusMessage(code: Int): String =
    when (code) {
        400 -> "Bad Request (HTTP 400): The server could not understand the request."
        403 -> "Forbidden (HTTP 403): You do not have permission to access this resource."
        404 -> "Not Found (HTTP 404): The requested resource could not be found."
        405 -> "Method Not Allowed (HTTP 405)."
        408 -> "Request Timeout (HTTP 408): The server timed out waiting for the request."
        409 -> "Conflict (HTTP 409): The request conflicts with current server state."
        429 -> "Too Many Requests (HTTP 429): Rate limit exceeded."
        in 500..599 -> "Server Error (HTTP $code): The server encountered an error."
        else -> "HTTP Error $code: Unexpected server response."
    }

fun isRetryable(e: IOException): Boolean =
    when (e) {
        is java.net.UnknownHostException -> false
        is javax.net.ssl.SSLException -> false
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        else -> true
    }

/**
 * Only safe methods (RFC 9110) are replayed after a failure. A POST, PATCH, PUT or DELETE that
 * timed out or got a 5xx may already have taken effect on the server (task created, comment added,
 * credential at an index removed), so resending it could duplicate or widen the change.
 */
fun isReplayableMethod(method: String): Boolean = method.uppercase() in REPLAYABLE_METHODS

private val REPLAYABLE_METHODS = setOf("GET", "HEAD", "OPTIONS")

/**
 * Attached by [NonReplayableFailureInterceptor] as a suppressed exception to an I/O failure of a
 * request that must not be resent, so [safeApiCall] can tell without changing the exception's type.
 */
class NonReplayableRequestMarker(
    method: String,
) : Exception("$method request may have reached the server; not retried") {
    override fun fillInStackTrace(): Throwable = this
}

/** True when [e] (or an exception it wraps) failed a request that must not be resent. */
fun isNonReplayableFailure(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { failure ->
        failure.suppressed.any { it is NonReplayableRequestMarker }
    }

private const val MAX_CAUSE_DEPTH = 8

/**
 * Marks I/O failures of non-replayable requests (see [isReplayableMethod]) for [safeApiCall].
 * A [java.net.ConnectException] is left unmarked: the connection never opened, so the request
 * never reached the server and a retry is safe for every method.
 */
object NonReplayableFailureInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            if (!isReplayableMethod(request.method) && e !is java.net.ConnectException) {
                e.addSuppressed(NonReplayableRequestMarker(request.method))
            }
            throw e
        }
    }
}

fun jitteredBackoff(baseMs: Long): Long {
    val jitter = (baseMs * 0.25 * (Math.random() * 2 - 1)).toLong()
    return baseMs + jitter
}

/**
 * Run a Retrofit call and map it to [NetworkResult], retrying up to [retries] times with backoff
 * on I/O errors, 5xx and 429. Requests with a non-replayable method (see [isReplayableMethod]) are
 * only retried when the connection was never established.
 */
suspend inline fun <reified T> safeApiCall(
    retries: Int = 2,
    crossinline call: suspend () -> Response<T>,
): NetworkResult<T> {
    var lastException: IOException? = null
    for (attempt in 0..retries) {
        try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                return if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    NetworkResult.Success(null as T)
                }
            } else {
                val code = response.code()
                // Read error body for detail message
                val errorBody =
                    try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                if ((code in 500..599 || code == 429) &&
                    attempt < retries &&
                    isReplayableMethod(response.raw().request.method)
                ) {
                    val backoff = jitteredBackoff(500L * (1 shl attempt))
                    delay(backoff)
                    continue
                }
                return NetworkResult.Failure(mapHttpError(code, errorBody))
            }
        } catch (e: IOException) {
            lastException = e
            if (attempt == retries || !isRetryable(e) || isNonReplayableFailure(e)) {
                return NetworkResult.Failure(
                    NetworkError.Connection(
                        "Network connection failure: ${e.message ?: "Unknown connection error"}",
                        e,
                    ),
                )
            }
            val backoff = jitteredBackoff(500L * (1 shl attempt))
            delay(backoff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return NetworkResult.Failure(
                NetworkError.Unknown(
                    "An unexpected error occurred: ${e.localizedMessage ?: e.message}",
                    e,
                ),
            )
        }
    }
    return NetworkResult.Failure(
        NetworkError.Connection(
            "Network connection failure after $retries attempts: ${lastException?.message}",
            lastException ?: IOException("Unknown connection error"),
        ),
    )
}
