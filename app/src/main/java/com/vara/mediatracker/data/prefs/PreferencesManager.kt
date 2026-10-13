package com.vara.mediatracker.data.prefs

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("media_tracker_prefs", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "") ?: ""
        set(value) = prefs.edit().putString("supabase_url", value.trim()).apply()

    var supabaseAnonKey: String
        get() = prefs.getString("supabase_anon_key", "") ?: ""
        set(value) = prefs.edit().putString("supabase_anon_key", value.trim()).apply()

    var syncKey: String
        get() = prefs.getString("sync_key", "") ?: ""
        set(value) = prefs.edit().putString("sync_key", value.trim()).apply()

    var tmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "") ?: ""
        set(value) = prefs.edit().putString("tmdb_api_key", value.trim()).apply()

    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_sync_timestamp", value).apply()

    /**
     * Set once the one-shot episode key reconciliation has completed successfully.
     * The reconciler is idempotent, so a stale `false` only costs a cheap no-op scan.
     */
    var episodeKeyMigrationDone: Boolean
        get() = prefs.getBoolean("episode_key_migration_v3_done", false)
        set(value) = prefs.edit().putBoolean("episode_key_migration_v3_done", value).apply()

    /**
     * Caches the resolved network or production company for a media item so it's
     * immediately available everywhere without making repetitive TMDB calls.
     */
    fun getCachedNetwork(mediaId: String): String? {
        return prefs.getString("network_$mediaId", null)
    }

    fun setCachedNetwork(mediaId: String, networkName: String) {
        prefs.edit().putString("network_$mediaId", networkName).apply()
    }

    /**
     * Persists every credential in a single atomic edit. Called on a debounce rather
     * than per keystroke, so four keys commit together instead of racing each other.
     */
    fun saveCredentials(
        supabaseUrl: String,
        supabaseAnonKey: String,
        syncKey: String,
        tmdbApiKey: String
    ) {
        prefs.edit()
            .putString("supabase_url", supabaseUrl.trim())
            .putString("supabase_anon_key", supabaseAnonKey.trim())
            .putString("sync_key", syncKey.trim())
            .putString("tmdb_api_key", tmdbApiKey.trim())
            .apply()
    }
}