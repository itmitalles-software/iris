package io.wutz.apollo.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    // Single connection pool shared by ALL clients (REST, WS, probes)
    // 5 idle connections, 30s keep-alive — tuned for single-server LAN
    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /**
     * Shared [okhttp3.CookieJar] used by every client (REST, WS, probe) so a
     * Set-Cookie from one request is reusable by the others (issue #470).
     *
     * Resolved lazily (per client build) so it is only touched after
     * [CookieManager.initialize] has run at app startup — NOT at
     * [OkHttpProvider] object-init time, which would otherwise throw for unit
     * tests / early access before the app context exists.
     */
    private fun resolveCookieJar(): okhttp3.CookieJar = CookieManager.cookieJar

    // Base client: connection pool + sensible defaults.
    // Lazily built so the shared CookieJar is only resolved at first use
    // (after CookieManager.initialize), not during object construction.
    // Note: retryOnConnectionFailure(true) lets OkHttp recover from low-level
    // connection/route failures (route timeouts, IPv4/IPv6 fallback), separate
    // from safeApiCall's app-level retries (backoff + 5xx/429/timeouts, safe
    // methods only; see NonReplayableFailureInterceptor).
    val base: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .let(ClientCertificates::configure)
            .cookieJar(resolveCookieJar())
            // Outermost application interceptor, so it also sees failures of
            // the interceptors that derived clients add (auth, profile).
            .addInterceptor(NonReplayableFailureInterceptor)
            .addNetworkInterceptor(ServerHeadersInterceptor())
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // WebSocket-optimized variant (infinite read timeout, ping interval)
    val websocket: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(ServerHeadersInterceptor())
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Variant for third-party hosts (GitHub releases): no cookies, so the
    // dashboard session can never be sent to another host.
    val external: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    /** Set once at app start by [initReleaseMetadataCache]; null in unit tests. */
    @Volatile
    private var releaseMetadataCache: Cache? = null

    /**
     * Give the GitHub releases API client a small disk cache. OkHttp then
     * revalidates stored release metadata with `If-None-Match`; an unchanged release is
     * answered with 304, which carries no body and does not count against GitHub's
     * unauthenticated rate limit. Call before the first update check.
     */
    fun initReleaseMetadataCache(cacheDir: File) {
        if (releaseMetadataCache == null) {
            releaseMetadataCache = Cache(File(cacheDir, "github-releases"), RELEASE_METADATA_CACHE_BYTES)
        }
    }

    // [external] with the release metadata cache, for GitHub API calls only: APK
    // downloads keep using [external] so the cache never holds an APK.
    val githubApi: OkHttpClient by lazy {
        external
            .newBuilder()
            .apply { releaseMetadataCache?.let(::cache) }
            .build()
    }

    private const val RELEASE_METADATA_CACHE_BYTES = 4L * 1024 * 1024

    // Short-timeout variant for probes and ticket minting
    val probe: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
}
