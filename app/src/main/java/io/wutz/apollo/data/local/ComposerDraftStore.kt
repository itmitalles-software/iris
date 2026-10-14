package io.wutz.apollo.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Raw storage behind [ComposerDrafts]: key → encoded entry. Blocking; callers run it off the main thread. */
internal interface DraftEntries {
    fun all(): Map<String, String>

    fun put(
        key: String,
        value: String,
    )

    fun remove(keys: Collection<String>)

    fun clear()
}

/**
 * Unsent composer text per conversation. Keeps at most [maxDrafts] drafts (the oldest go first)
 * and at most [maxChars] characters per draft; a blank draft removes the entry.
 */
internal class ComposerDrafts(
    private val entries: DraftEntries,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxDrafts: Int = MAX_DRAFTS,
    private val maxChars: Int = MAX_DRAFT_CHARS,
) {
    fun load(key: String): String? = entries.all()[key]?.let(::decode)?.second

    fun save(
        key: String,
        text: String,
    ) {
        if (text.isBlank()) {
            entries.remove(listOf(key))
            return
        }
        entries.put(key, "${clock()}$SEPARATOR${text.take(maxChars)}")
        val all = entries.all()
        if (all.size > maxDrafts) {
            val oldest =
                all.entries
                    .sortedBy { (_, value) -> decode(value)?.first ?: Long.MIN_VALUE }
                    .take(all.size - maxDrafts)
                    .map { it.key }
            entries.remove(oldest)
        }
    }

    fun remove(key: String) = entries.remove(listOf(key))

    fun clear() = entries.clear()

    private fun decode(value: String): Pair<Long, String>? {
        val separator = value.indexOf(SEPARATOR)
        if (separator <= 0) return null
        val time = value.substring(0, separator).toLongOrNull() ?: return null
        return time to value.substring(separator + 1)
    }

    companion object {
        /** The Hermes desktop keeps the same number of drafts. */
        const val MAX_DRAFTS = 50
        const val MAX_DRAFT_CHARS = 20_000
        private const val SEPARATOR = ':'
    }
}

/** Storage seam for the chat screen; the default writes to [ComposerDraftStore]. */
internal interface ComposerDraftStorage {
    suspend fun load(key: String): String?

    fun save(
        key: String,
        text: String,
    )

    fun remove(key: String)
}

/**
 * Process-wide, encrypted store for composer drafts (an EncryptedSharedPreferences file with its
 * own key set, created lazily off the main thread). Writes run one at a time in call order on
 * an app-lifetime scope, so a draft saved while the chat screen goes away is not lost. When the
 * encrypted file cannot be opened, drafts simply are not persisted.
 */
object ComposerDraftStore : ComposerDraftStorage {
    private const val TAG = "ComposerDraftStore"
    private const val PREFS_FILE = "apollo_composer_drafts"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var drafts: ComposerDrafts? = null

    @Volatile
    private var unavailable = false

    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override suspend fun load(key: String): String? =
        withContext(writeDispatcher) {
            runCatching { open()?.load(key) }.getOrNull()
        }

    override fun save(
        key: String,
        text: String,
    ) {
        writeScope.launch { runCatching { open()?.save(key, text) } }
    }

    override fun remove(key: String) {
        writeScope.launch { runCatching { open()?.remove(key) } }
    }

    /** Logout: drop every draft of every server and profile. */
    fun clear() {
        writeScope.launch { runCatching { open()?.clear() } }
    }

    private fun open(): ComposerDrafts? {
        drafts?.let { return it }
        if (unavailable) return null
        val context = appContext ?: return null
        return try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs =
                EncryptedSharedPreferences.create(
                    PREFS_FILE,
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            ComposerDrafts(SharedPreferencesDraftEntries(prefs)).also { drafts = it }
        } catch (e: Exception) {
            // Never log draft content; the class name is enough to diagnose a keystore problem.
            Log.w(TAG, "Composer drafts are not persisted: ${e.javaClass.simpleName}")
            unavailable = true
            null
        }
    }
}

private class SharedPreferencesDraftEntries(
    private val prefs: SharedPreferences,
) : DraftEntries {
    override fun all(): Map<String, String> =
        prefs.all
            .mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap()

    override fun put(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        prefs.edit().apply { keys.forEach(::remove) }.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
