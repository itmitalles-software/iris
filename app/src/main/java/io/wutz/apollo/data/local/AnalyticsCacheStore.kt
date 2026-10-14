package io.wutz.apollo.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import io.wutz.apollo.data.model.AnalyticsResponse
import io.wutz.apollo.data.model.ModelsAnalyticsResponse
import io.wutz.apollo.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Disk-backed cache for analytics responses (issue #537 follow-up).
 *
 * The `/api/analytics/usage` endpoint is expensive on the backend (it runs the
 * insights engine over the full message history), so a cold load can take tens of
 * seconds. The ViewModel already keeps an in-memory cache, but that dies when the
 * app is killed. Persisting the last successful response to disk means even a cold
 * app launch can render *something* instantly and refresh in the background —
 * the user never stares at a spinner for a query we already answered recently.
 *
 * Keyed by trailing window (`days`), requested profile AND the current
 * [DataScope] (connection profile, server URL, server-side profile), so neither
 * a profile switch nor a different server ever shows another's usage and cost.
 */
@Serializable
private data class CachedWindow(
    val usageJson: String = "",
    val modelsJson: String = "",
)

@Serializable
private data class AnalyticsCacheState(
    // Map key = analyticsCacheKey(): DataScope-qualified so servers and profiles stay isolated.
    val windows: Map<String, CachedWindow> = emptyMap(),
)

private object AnalyticsCacheSerializer : Serializer<AnalyticsCacheState> {
    override val defaultValue: AnalyticsCacheState = AnalyticsCacheState()

    override suspend fun readFrom(input: InputStream): AnalyticsCacheState =
        try {
            // Use the app's wire Json (ignoreUnknownKeys=true) so a backend
            // adding a field can't break our on-disk cache decode.
            OkHttpProvider.json.decodeFromString(
                AnalyticsCacheState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: AnalyticsCacheState,
        output: OutputStream,
    ) {
        output.write(
            OkHttpProvider.json
                .encodeToString(AnalyticsCacheState.serializer(), t)
                .toByteArray(),
        )
    }
}

private val Context.analyticsCacheDataStore: DataStore<AnalyticsCacheState> by dataStore(
    fileName = "analytics_cache.json",
    serializer = AnalyticsCacheSerializer,
)

/**
 * Disk cache key for one analytics window, or null without a data scope (no
 * server configured): then nothing is cached rather than shared across servers.
 */
internal fun analyticsCacheKey(
    scope: DataScope?,
    days: Int,
    profile: String?,
): String? = scope?.persistentKey("analytics:$days:${profile ?: "default"}")

class AnalyticsCacheStore(
    private val context: Context,
    private val scopeProvider: () -> DataScope? = { runCatching { AuthManager.currentDataScope() }.getOrNull() },
) {
    /** The data scope a request is issued under; pass it back to [load] and [save]. */
    fun currentScope(): DataScope? = scopeProvider()

    /** Load a previously cached window of [scope], or null if absent/corrupt. */
    suspend fun load(
        days: Int,
        profile: String?,
        scope: DataScope? = currentScope(),
    ): Pair<AnalyticsResponse, ModelsAnalyticsResponse?>? =
        withContext(Dispatchers.IO) {
            try {
                val key = analyticsCacheKey(scope, days, profile) ?: return@withContext null
                val win =
                    context.analyticsCacheDataStore.data
                        .first()
                        .windows[key] ?: return@withContext null
                val usage = OkHttpProvider.json.decodeFromString<AnalyticsResponse>(win.usageJson)
                val models =
                    win.modelsJson
                        .takeIf { it.isNotBlank() }
                        ?.let { OkHttpProvider.json.decodeFromString<ModelsAnalyticsResponse>(it) }
                usage to models
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Persist a successful window response fetched under [scope]. Skipped when
     * the scope changed while the (slow) request was in flight, so a response
     * is never filed under another server or profile. Best-effort; never throws.
     */
    suspend fun save(
        days: Int,
        profile: String?,
        usage: AnalyticsResponse,
        models: ModelsAnalyticsResponse?,
        scope: DataScope? = currentScope(),
    ) = withContext(Dispatchers.IO) {
        try {
            if (scope != currentScope()) return@withContext
            val key = analyticsCacheKey(scope, days, profile) ?: return@withContext
            context.analyticsCacheDataStore.updateData { state ->
                val win =
                    CachedWindow(
                        usageJson = OkHttpProvider.json.encodeToString(usage),
                        modelsJson = models?.let { OkHttpProvider.json.encodeToString(it) } ?: "",
                    )
                // Drop entries from the old unscoped "$days:$profile" format: they
                // cannot be attributed to a server and must never be served again.
                val scoped = state.windows.filterKeys { it.startsWith(SCOPED_KEY_PREFIX) }
                state.copy(windows = scoped + (key to win))
            }
        } catch (e: Exception) {
            // Fail-safe: a cache miss is preferable to a crash on launch.
        }
    }

    private companion object {
        /** Every [DataScope.persistentKey] starts with its length-prefixed connection id. */
        const val SCOPED_KEY_PREFIX = "c"
    }
}
