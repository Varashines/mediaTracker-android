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
     * Incremental pull watermark: the newest server `updated_at` the last sync saw.
     *
     * Server time, not the device clock (a fast clock would skip rows). Every table filters on
     * `updated_at > this`, so an unchanged library pulls nothing — a full pull is ~8 MB and the
     * background sync runs every 30 minutes.
     */
    var syncWatermark: String?
        get() = prefs.getString("sync_watermark", null)
        set(value) = prefs.edit().putString("sync_watermark", value).apply()

    /**
     * ISO-3166 region the Discovery providers were last enriched for. When the device
     * region changes, providers are cleared and re-fetched so they stay region-correct.
     */
    var lastEnrichRegion: String
        get() = prefs.getString("last_enrich_region", "") ?: ""
        set(value) = prefs.edit().putString("last_enrich_region", value).apply()

    /**
     * Manual provider region override (ISO-3166, e.g. "IN"). Empty means "auto", which
     * uses the SIM country then the locale region.
     */
    var providerRegion: String
        get() = prefs.getString("provider_region", "") ?: ""
        set(value) = prefs.edit().putString("provider_region", value.trim().uppercase()).apply()

    /**
     * Theme Preset (0: Mac, 1: Trippy, 2: Chill, 3: Epic, 4: Emotional, 5: Sunset)
     * Matches the macOS AppThemeCoordinator presets.
     */
    var themePreset: Int
        get() = prefs.getInt("theme_preset", 0)
        set(value) = prefs.edit().putInt("theme_preset", value).apply()

    /**
     * Theme Mode:
     * 0: System / Auto
     * 1: Dark
     * 2: Light
     */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 1) // default to dark
        set(value) = prefs.edit().putInt("theme_mode", value).apply()

    /**
     * Set once the one-shot episode key reconciliation has completed successfully.
     * The reconciler is idempotent, so a stale `false` only costs a cheap no-op scan.
     */
    var episodeKeyMigrationDone: Boolean
        get() = prefs.getBoolean("episode_key_migration_v3_done", false)
        set(value) = prefs.edit().putBoolean("episode_key_migration_v3_done", value).apply()

    /**
     * When true, the detail view's NETWORK / PRODUCTION pill shows the network/studio
     * logo instead of its name.
     */
    var detailNetworkLogo: Boolean
        get() = prefs.getBoolean("detail_network_logo", false)
        set(value) = prefs.edit().putBoolean("detail_network_logo", value).apply()

    /**
     * Set once legacy taste spellings ("Loved"/"Liked"/"Disliked") have been rewritten to the
     * canonical "Love"/"Like"/"Dislike" the macOS enum understands. Idempotent if it re-runs.
     */
    var tasteCanonicalizationDone: Boolean
        get() = prefs.getBoolean("taste_canonicalization_done", false)
        set(value) = prefs.edit().putBoolean("taste_canonicalization_done", value).apply()

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

    fun getCachedRuntime(mediaId: String): Int? {
        val rt = prefs.getInt("runtime_$mediaId", -1)
        return if (rt > 0) rt else null
    }

    fun setCachedRuntime(mediaId: String, runtimeMinutes: Int) {
        prefs.edit().putInt("runtime_$mediaId", runtimeMinutes).apply()
    }

    fun getCachedGenres(mediaId: String): String? {
        return prefs.getString("genres_$mediaId", null)
    }

    fun setCachedGenres(mediaId: String, genres: String) {
        prefs.edit().putString("genres_$mediaId", genres).apply()
    }

    fun getCachedVoteAverage(mediaId: String): Float? {
        val vote = prefs.getFloat("vote_$mediaId", -1f)
        return if (vote > 0f) vote else null
    }

    fun setCachedVoteAverage(mediaId: String, vote: Float) {
        prefs.edit().putFloat("vote_$mediaId", vote).apply()
    }

    /**
     * Notifications are global, matching the Mac: a master switch, one channel per media type,
     * and the time of day a release is announced. There is deliberately no per-title opt-in —
     * the Mac has none either, and a per-release set grew without bound while duplicating
     * information the app already holds.
     *
     * An unset master or channel reads as enabled. `getBoolean` returns the default for a key
     * that was never written, which is the same trap the Mac's `isChannelEnabled` guards
     * against: a user who has never opened the screen is not a user who turned it off.
     */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    var notificationsMovies: Boolean
        get() = prefs.getBoolean("notifications_movies", true)
        set(value) = prefs.edit().putBoolean("notifications_movies", value).apply()

    var notificationsTv: Boolean
        get() = prefs.getBoolean("notifications_tv", true)
        set(value) = prefs.edit().putBoolean("notifications_tv", value).apply()

    /**
     * Removes the per-title alert set left behind by the previous model. Nothing reads it now;
     * it is deleted rather than left to sit in prefs forever. Idempotent — removing an absent
     * key is a no-op.
     */
    fun clearLegacyReleaseAlerts() {
        if (prefs.contains(LEGACY_KEY_RELEASE_ALERTS)) {
            prefs.edit().remove(LEGACY_KEY_RELEASE_ALERTS).apply()
        }
    }

    /** Delivery time as seconds past local midnight. 09:00, as on the Mac. */
    var notificationsTime: Int
        get() = prefs.getInt("notifications_time", 9 * 3600)
        set(value) = prefs.edit().putInt("notifications_time", value).apply()

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

private const val LEGACY_KEY_RELEASE_ALERTS = "release_alerts"
