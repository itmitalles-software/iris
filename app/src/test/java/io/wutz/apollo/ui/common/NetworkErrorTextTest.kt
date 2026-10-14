package io.wutz.apollo.ui.common

import io.wutz.apollo.R
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.httpStatusMessage
import io.wutz.apollo.data.remote.mapHttpError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

class NetworkErrorTextTest {
    @Test
    fun `auth expiry maps to the sign-in resource`() {
        assertEquals(UiText.res(R.string.network_error_auth_expired), mapHttpError(401).toUiText())
    }

    @Test
    fun `http status codes map to their own resources`() {
        val expected =
            mapOf(
                400 to UiText.res(R.string.network_error_http_400),
                403 to UiText.res(R.string.network_error_http_403),
                404 to UiText.res(R.string.network_error_http_404),
                405 to UiText.res(R.string.network_error_http_405),
                408 to UiText.res(R.string.network_error_http_408),
                409 to UiText.res(R.string.network_error_http_409),
                429 to UiText.res(R.string.network_error_http_429),
                500 to UiText.res(R.string.network_error_http_server, 500),
                503 to UiText.res(R.string.network_error_http_server, 503),
                418 to UiText.res(R.string.network_error_http_other, 418),
            )
        expected.forEach { (code, text) -> assertEquals("HTTP $code", text, mapHttpError(code).toUiText()) }
    }

    @Test
    fun `a server detail is shown verbatim instead of the status text`() {
        val error = mapHttpError(409, """{"detail":"Server is owned by plugin x"}""")
        assertEquals(UiText.raw("Server is owned by plugin x"), error.toUiText())
    }

    @Test
    fun `an http error with a non-generic message shows it verbatim`() {
        assertEquals(UiText.raw("blocked by parent"), NetworkError.Http(409, "blocked by parent").toUiText())
        assertEquals(
            UiText.res(R.string.network_error_http_server, 502),
            NetworkError.Http(502, httpStatusMessage(502)).toUiText(),
        )
    }

    @Test
    fun `connection failures keep the platform detail as argument`() {
        val error = NetworkError.Connection("Network connection failure: timeout", IOException("timeout"))
        assertEquals(UiText.res(R.string.network_error_connection_detail, "timeout"), error.toUiText())
        assertEquals(
            UiText.res(R.string.network_error_connection),
            NetworkError.Connection("Network connection failure: null", IOException()).toUiText(),
        )
    }

    @Test
    fun `unknown failures show the cause message`() {
        val error = NetworkError.Unknown("An unexpected error occurred: bad json", IllegalStateException("bad json"))
        assertEquals(UiText.res(R.string.network_error_unexpected, "bad json"), error.toUiText())
    }

    @Test
    fun `every network error string exists in english and german with the same placeholders`() {
        val english = strings("values")
        val german = strings("values-de")
        val names = english.keys.filter { it.startsWith("network_error_") || it.startsWith("feedback_action_") }
        assertEquals(13 + 2, names.size)
        names.forEach { name ->
            val de = german[name] ?: error("missing German string $name")
            assertEquals(name, placeholders(english.getValue(name)), placeholders(de))
        }
    }

    private fun strings(dir: String): Map<String, String> {
        val file =
            listOf(File("src/main/res/$dir/strings_feedback.xml"), File("app/src/main/res/$dir/strings_feedback.xml"))
                .first { it.exists() }
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            el.getAttribute("name") to el.textContent
        }
    }

    private fun placeholders(text: String): List<String> = Regex("%\\d\\$[sd]").findAll(text).map { it.value }.toList()
}
