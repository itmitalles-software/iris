package io.wutz.apollo.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.wutz.apollo.data.model.PinnedModel
import io.wutz.apollo.theme.ThemePreference
import io.wutz.apollo.theme.ThemePreset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ServerStoreMigration] runs at every start (AuthManager) and moves the pre-DataStore encrypted
 * preferences into [ServerStoreState] exactly once. The legacy JSON uses the app-wide snake_case naming.
 */
class ServerStoreMigrationTest {
    private val context = mockk<Context>(relaxed = true)
    private val prefs = MapSharedPreferences()
    private var prefsAvailable = true
    private val migration = ServerStoreMigration(context)

    @Before
    @Suppress("DEPRECATION") // Mocks the deprecated security-crypto API.
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        mockkStatic(MasterKeys::class)
        every { MasterKeys.getOrCreate(any()) } returns "test-master-key"
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                "hermes_secure_prefs",
                "test-master-key",
                any(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } answers { if (prefsAvailable) prefs else throw IllegalStateException("keystore unavailable") }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `nothing to migrate without legacy keys`() =
        runBlocking {
            prefs.edit().putString("unrelated", "x").apply()
            assertFalse(migration.shouldMigrate(ServerStoreState()))
        }

    @Test
    fun `unreadable legacy preferences are skipped instead of crashing the start`() =
        runBlocking {
            prefsAvailable = false
            val current = ServerStoreState()

            assertFalse(migration.shouldMigrate(current))
            assertSame(current, migration.migrate(current))
            migration.cleanUp()
        }

    @Test
    fun `legacy preferences move into the store state`() =
        runBlocking {
            prefs
                .edit()
                .putString("host", "10.0.0.5")
                .putInt("port", 8080)
                .putBoolean("auto_reconnect", false)
                .putString("theme_preference", ThemePreference.LIGHT.name)
                .putBoolean("use_dynamic_colors", true)
                .putString(
                    "connection_profiles",
                    """[{"id":"p1","name":"Home","host":"192.168.1.2","port":9119},""" +
                        """{"id":"p2","name":"Work","base_url":"https://work.example:9119/"}]""",
                ).putString("selected_profile_id", "p2")
                .putString("pinned_models", """[{"provider_slug":"openai","model_name":"gpt-4o"}]""")
                .putString("ws_auth_param", "ticket")
                .putBoolean("typing_effect_enabled", false)
                .putInt("typing_effect_delay_ms", 12)
                .apply()

            assertTrue(migration.shouldMigrate(ServerStoreState()))
            val migrated = migration.migrate(ServerStoreState())

            assertEquals("10.0.0.5", migrated.host)
            assertEquals(8080, migrated.port)
            assertEquals("http://10.0.0.5:8080/", migrated.baseUrl)
            assertFalse(migrated.autoReconnect)
            assertEquals(ThemePreference.LIGHT, migrated.themePreference)
            assertTrue(migrated.useDynamicColors)
            assertEquals(ThemePreset.APOLLO, migrated.themePreset)
            assertEquals(listOf("p1", "p2"), migrated.connectionProfiles.map { it.id })
            // A legacy host/port profile gets a base URL; an existing one is kept.
            assertEquals("http://192.168.1.2:9119/", migrated.connectionProfiles[0].baseUrl)
            assertEquals("https://work.example:9119/", migrated.connectionProfiles[1].baseUrl)
            assertEquals("p2", migrated.selectedProfileId)
            assertEquals(listOf(PinnedModel("openai", "gpt-4o")), migrated.pinnedModels)
            assertEquals("ticket", migrated.wsAuthParam)
            assertFalse(migrated.typingEffectEnabled)
            assertEquals(12, migrated.typingEffectDelayMs)
        }

    @Test
    fun `corrupt or unknown legacy values fall back to defaults`() =
        runBlocking {
            prefs
                .edit()
                .putString("theme_preference", "NEON")
                .putString("theme_preset", "NOT_A_PRESET")
                .putString("connection_profiles", "{not json")
                .putString("pinned_models", "[oops")
                .putString("selected_profile_id", "   ")
                .apply()

            val migrated = migration.migrate(ServerStoreState())

            assertEquals("127.0.0.1", migrated.host)
            assertEquals(9119, migrated.port)
            assertEquals("http://127.0.0.1:9119/", migrated.baseUrl)
            assertEquals(ThemePreference.DARK, migrated.themePreference)
            assertEquals(ThemePreset.APOLLO, migrated.themePreset)
            assertTrue(migrated.connectionProfiles.isEmpty())
            assertTrue(migrated.pinnedModels.isEmpty())
            assertNull(migrated.selectedProfileId)
            assertEquals("token", migrated.wsAuthParam)
        }

    @Test
    fun `cleanUp marks the migration done so it never runs again`() =
        runBlocking {
            prefs.edit().putString("host", "10.0.0.5").apply()
            assertTrue(migration.shouldMigrate(ServerStoreState()))

            migration.cleanUp()

            assertFalse(migration.shouldMigrate(ServerStoreState()))
            assertTrue(prefs.getBoolean("migrated_to_datastore", false))
            // The legacy values stay readable; only the marker is added.
            assertEquals("10.0.0.5", prefs.getString("host", null))
        }

    /** Minimal in-memory [SharedPreferences]; the encrypted file cannot be created on the JVM. */
    private class MapSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = values[key] as? Int ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = MapEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class MapEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ) = apply { pending[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { pending[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { pending[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { pending[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { pending[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { pending[key] = value }

            override fun remove(key: String) = apply { removed += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                removed.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
