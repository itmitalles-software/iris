package io.wutz.apollo.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkResultTest {
    @Test
    fun testMapHttpError_knownCodes() {
        val codesAndExpectedMessages =
            mapOf(
                400 to "Bad Request (HTTP 400): The server could not understand the request.",
                403 to "Forbidden (HTTP 403): You do not have permission to access this resource.",
                404 to "Not Found (HTTP 404): The requested resource could not be found.",
                405 to "Method Not Allowed (HTTP 405).",
                408 to "Request Timeout (HTTP 408): The server timed out waiting for the request.",
                409 to "Conflict (HTTP 409): The request conflicts with current server state.",
                429 to "Too Many Requests (HTTP 429): Rate limit exceeded.",
                500 to "Server Error (HTTP 500): The server encountered an error.",
                599 to "Server Error (HTTP 599): The server encountered an error.",
            )

        for ((code, expectedMessage) in codesAndExpectedMessages) {
            val error = mapHttpError(code)
            assertTrue(error is NetworkError.Http)
            error as NetworkError.Http
            assertEquals(code, error.code)
            assertEquals(expectedMessage, error.message)
        }
    }

    @Test
    fun testMapHttpError_authExpired() {
        val error = mapHttpError(401)
        assertTrue(error is NetworkError.AuthExpired)
        assertEquals("Authentication token has expired. Please log in again.", error.message)
    }

    @Test
    fun testMapHttpError_unknownCode() {
        val error = mapHttpError(418)
        assertTrue(error is NetworkError.Http)
        error as NetworkError.Http
        assertEquals(418, error.code)
        assertEquals("HTTP Error 418: Unexpected server response.", error.message)
    }

    @Test
    fun testIsRetryable() {
        assertTrue(!isRetryable(java.net.UnknownHostException("Host not found")))
        assertTrue(!isRetryable(javax.net.ssl.SSLException("SSL failed")))
        assertTrue(isRetryable(java.net.ConnectException("Connection refused")))
        assertTrue(isRetryable(java.net.SocketTimeoutException("Timeout")))
        assertTrue(isRetryable(IOException("Generic IO error")))
    }

    @Test
    fun testJitteredBackoff() {
        val baseMs = 1000L
        for (i in 0..100) {
            val backoff = jitteredBackoff(baseMs)
            assertTrue(backoff in 750..1250)
        }
    }

    @Test
    fun testSafeApiCall_successWithBody() =
        runBlocking {
            val result =
                safeApiCall {
                    Response.success("Success")
                }
            assertEquals(NetworkResult.Success::class, result::class)
            assertEquals("Success", (result as NetworkResult.Success).data)
        }

    @Test
    fun testSafeApiCall_successWithNullBody() =
        runBlocking {
            val result =
                safeApiCall<String?> {
                    Response.success(null)
                }
            if (result is NetworkResult.Failure) {
                println("FAILURE: " + result.error)
            }
            assertEquals(NetworkResult.Success::class, result::class)
            assertEquals(null, (result as NetworkResult.Success).data)
        }

    @Test
    fun testSafeApiCall_httpFailure_nonRetryable() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    Response.error(404, "Not Found".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(404, (error as NetworkError.Http).code)
            assertEquals(1, callCount) // Should not retry for 4xx errors
        }

    @Test
    fun testSafeApiCall_httpFailure_retryable_eventuallySucceeds() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    if (callCount == 1) {
                        Response.error(500, "Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                    } else {
                        Response.success("Success on retry")
                    }
                }
            assertEquals(NetworkResult.Success::class, result::class)
            assertEquals("Success on retry", (result as NetworkResult.Success).data)
            assertEquals(2, callCount)
        }

    @Test
    fun testSafeApiCall_httpFailure_retryable_exceedsMaxRetries() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    Response.error(503, "Service Unavailable".toResponseBody("text/plain".toMediaTypeOrNull()))
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(503, (error as NetworkError.Http).code)
            assertEquals(3, callCount) // 1 initial + 2 retries
        }

    @Test
    fun testSafeApiCall_ioException_eventuallySucceeds() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    if (callCount == 1) {
                        throw IOException("Network error")
                    } else {
                        Response.success("Success on retry")
                    }
                }
            assertEquals(NetworkResult.Success::class, result::class)
            assertEquals("Success on retry", (result as NetworkResult.Success).data)
            assertEquals(2, callCount)
        }

    @Test
    fun testSafeApiCall_ioException_exceedsMaxRetries() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    throw IOException("Network timeout")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Connection)
            val connectionError = error as NetworkError.Connection
            assertTrue(connectionError.message.contains("Network connection failure"))
            assertTrue(connectionError.message.contains("Network timeout"))
            assertEquals(3, callCount) // 1 initial + 2 retries
        }

    @Test
    fun testSafeApiCall_otherException() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String> {
                    callCount++
                    throw RuntimeException("Unexpected error")
                }
            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Unknown)
            val unknownError = error as NetworkError.Unknown
            assertTrue(unknownError.message.contains("An unexpected error occurred"))
            assertTrue(unknownError.message.contains("Unexpected error"))
            assertEquals(1, callCount) // Should not retry for unknown exceptions
        }

    private fun errorFor(
        method: String,
        code: Int,
    ): Response<String> {
        val body = if (method == "GET") null else "{}".toRequestBody()
        val raw =
            okhttp3.Response
                .Builder()
                .request(
                    Request
                        .Builder()
                        .url("http://localhost/")
                        .method(method, body)
                        .build(),
                ).protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("error")
                .build()
        return Response.error("error".toResponseBody("text/plain".toMediaTypeOrNull()), raw)
    }

    @Test
    fun testSafeApiCall_5xxForNonIdempotentPost_isNotRetried() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    errorFor("POST", 503)
                }
            assertEquals(503, ((result as NetworkResult.Failure).error as NetworkError.Http).code)
            assertEquals(1, callCount)
        }

    @Test
    fun testSafeApiCall_429ForPatch_isNotRetried() =
        runBlocking {
            var callCount = 0
            safeApiCall<String>(retries = 2) {
                callCount++
                errorFor("PATCH", 429)
            }
            assertEquals(1, callCount)
        }

    @Test
    fun testSafeApiCall_markedIoFailure_isNotRetried() =
        runBlocking {
            var callCount = 0
            val result =
                safeApiCall<String>(retries = 2) {
                    callCount++
                    throw java.net.SocketTimeoutException("timeout").apply {
                        addSuppressed(NonReplayableRequestMarker("POST"))
                    }
                }
            assertTrue((result as NetworkResult.Failure).error is NetworkError.Connection)
            assertEquals(1, callCount)
        }

    @Test
    fun testIsNonReplayableFailure_followsTheCauseChain() {
        val marked = IOException("read timed out").apply { addSuppressed(NonReplayableRequestMarker("POST")) }
        assertTrue(isNonReplayableFailure(marked))
        assertTrue(isNonReplayableFailure(IOException("copy", marked)))
        assertFalse(isNonReplayableFailure(IOException("plain")))
    }

    @Test
    fun testInterceptor_marksTimedOutPostButNotGet() {
        val server = MockWebServer()
        server.start()
        try {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(NonReplayableFailureInterceptor)
                    .readTimeout(200, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build()

            fun failureOf(request: Request): IOException {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                return try {
                    client.newCall(request).execute().close()
                    throw AssertionError("expected a timeout")
                } catch (e: IOException) {
                    e
                }
            }

            val post =
                failureOf(
                    Request
                        .Builder()
                        .url(server.url("/api/tasks"))
                        .post("{}".toRequestBody())
                        .build(),
                )
            val get = failureOf(Request.Builder().url(server.url("/api/tasks")).build())

            assertTrue(isNonReplayableFailure(post))
            assertFalse(isNonReplayableFailure(get))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testInterceptor_leavesConnectFailuresRetryableForPost() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/api/tasks")
        server.shutdown()
        val client = OkHttpClient.Builder().addInterceptor(NonReplayableFailureInterceptor).build()

        val failure =
            try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .post("{}".toRequestBody())
                            .build(),
                    ).execute()
                    .close()
                throw AssertionError("expected a connect failure")
            } catch (e: IOException) {
                e
            }

        assertTrue(failure is java.net.ConnectException)
        assertFalse(isNonReplayableFailure(failure))
    }

    @Test
    fun testIsReplayableMethod_onlySafeMethods() {
        listOf("GET", "head", "OPTIONS").forEach { assertTrue(it, isReplayableMethod(it)) }
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { assertFalse(it, isReplayableMethod(it)) }
    }
}
