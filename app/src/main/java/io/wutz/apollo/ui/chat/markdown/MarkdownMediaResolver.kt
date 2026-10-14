package io.wutz.apollo.ui.chat.markdown

import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.GatewayFileClient
import java.net.URI

object MarkdownMediaResolver {
    /**
     * Host of an absolute http(s) image URL that does not point at the configured server, or
     * null when the image may load automatically (server-relative, data:image, same origin).
     * Auto-loading third-party URLs from agent output would leak data in the query string and
     * the device IP (prompt-injection exfiltration), so the UI asks for a tap first.
     */
    fun externalImageHost(uri: String): String? =
        externalImageHost(uri, runCatching { AuthManager.getBaseUrl() }.getOrDefault(""))

    internal fun externalImageHost(
        uri: String,
        baseUrl: String,
    ): String? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val image = runCatching { URI(trimmed) }.getOrNull()
        val imageHost = image?.host?.lowercase()
        if (image == null || imageHost.isNullOrBlank()) return trimmed.substringAfter("://").substringBefore('/')
        val server = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val sameOrigin =
            server?.host != null &&
                server.host.equals(imageHost, ignoreCase = true) &&
                server.scheme.equals(image.scheme, ignoreCase = true) &&
                effectivePort(server) == effectivePort(image)
        return if (sameOrigin) null else imageHost
    }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    fun resolveImageUrl(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return uri
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:image/")) {
            return trimmed
        }
        val baseUrl = runCatching { AuthManager.getBaseUrl() }.getOrDefault("")
        val token = runCatching { AuthManager.getToken() }.getOrNull().orEmpty()
        val mediaUrl = GatewayFileClient.buildMediaUrl(baseUrl, token, trimmed)
        if (mediaUrl != null) return mediaUrl

        if (baseUrl.isNotBlank() && (trimmed.startsWith("/api/") || trimmed.startsWith("api/"))) {
            val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            val sep = if (cleanPath.contains("?")) "&" else "?"
            return if (token.isNotBlank() && !cleanPath.contains("token=")) {
                "${baseUrl.trimEnd('/')}$cleanPath${sep}token=$token"
            } else {
                "${baseUrl.trimEnd('/')}$cleanPath"
            }
        }
        return uri
    }
}
