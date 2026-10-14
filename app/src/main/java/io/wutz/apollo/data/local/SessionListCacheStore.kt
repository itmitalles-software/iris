package io.wutz.apollo.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import io.wutz.apollo.data.model.SessionListResponse
import io.wutz.apollo.data.remote.OkHttpProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight disk cache for the initial page of sessions.
 * Persists the first page JSON to SharedPreferences so the History screen
 * can render instantly even after app kill / process death.
 *
 * Writes (JSON encoding plus the preferences edit) run off the main thread, one
 * at a time in call order, so a later [remove] or [clear] is never overtaken by
 * an earlier [put]. [get] stays synchronous: it runs once per process, on the
 * first History load after start.
 */
object SessionListCacheStore {
    private const val PREFS_NAME = "hermes_sessions_cache"
    private const val KEY_PREFIX = "sessions_page_"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val writeScope = CoroutineScope(SupervisorJob())

    @Volatile
    private var writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun get(key: String): SessionListResponse? {
        val p = prefs ?: return null
        val raw = p.getString(KEY_PREFIX + key, null) ?: return null
        return try {
            OkHttpProvider.json.decodeFromString<SessionListResponse>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun put(
        key: String,
        data: SessionListResponse,
    ) {
        val p = prefs ?: return
        write {
            try {
                val json = OkHttpProvider.json.encodeToString(SessionListResponse.serializer(), data)
                p.edit().putString(KEY_PREFIX + key, json).apply()
            } catch (_: Exception) {
                // Ignore serialization issues on cache save
            }
        }
    }

    fun remove(key: String) {
        val p = prefs ?: return
        write { p.edit().remove(KEY_PREFIX + key).apply() }
    }

    fun clear() {
        val p = prefs ?: return
        write { p.edit().clear().apply() }
    }

    private fun write(block: () -> Unit) {
        writeScope.launch(writeDispatcher) { block() }
    }

    /** Swap the preferences; writes then run inline on [dispatcher] (unconfined by default). */
    @VisibleForTesting
    internal fun resetForTesting(
        testPrefs: SharedPreferences? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        synchronized(this) {
            prefs = testPrefs
            writeDispatcher = dispatcher
        }
    }
}
