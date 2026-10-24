package com.vara.mediatracker.data.prefs

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("media_tracker_prefs", Context.MODE_PRIVATE)

    /**
     * Credentials live in a file of their own so they can be excluded from Android backup.
     *
     * The sync key is a write credential for the Supabase library: anyone holding it can rewrite
     * every title on every device. It used to sit in [prefs] next to the theme setting, which is
     * also the file Android auto-backs-up to the user's Google account — so the key left the device
     * in a backup nobody had to unlock. Splitting it out means the backup rules can name exactly
     * one file to leave behind, while the library and the settings still restore normally.
     *
     * Losing the keys on a restore is the correct outcome: the alternative is a key in a cloud
     * backup, and re-entering four fields is what the Mac asks for after a clean install too.
     */
    private val secrets: SharedPreferences =
        context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    init {
        migrateSecretsOutOfBackedUpPrefs()
    }

    /**
     * Moves credentials written by an older build into [secrets] and deletes them from the backed-up
     * file. Committed rather than applied: a getter called on the next line has to see the moved
     * value, and `apply()` is asynchronous. One-time and a handful of strings, so the commit is free.
     */
    private fun migrateSecretsOutOfBackedUpPrefs() {
        val legacyKeys = listOf("supabase_url", "supabase_anon_key", "sync_key", "tmdb_api_key")
        val editor = secrets.edit()
        var found = false
        legacyKeys.forEach { key ->
            if (secrets.getString(key, "").isNullOrEmpty()) {
                val old = prefs.getString(key, null)
                if (!old.isNullOrEmpty()) {
                    editor.putString(key, old)
                    found = true
                }
            }
        }
        if (found) editor.commit()
        if (legacyKeys.any { prefs.contains(it) }) {
            prefs.edit().apply { legacyKeys.forEach { remove(it) } }.commit()
        }
    }

    var supabaseUrl: String
        get() = secrets.getString("supabase_url", "") ?: ""
        set(value) = secrets.edit().putString("supabase_url", value.trim()).apply()

    var supabaseAnonKey: String
        get() = secrets.getString("supabase_anon_key", "") ?: ""
        set(value) = secrets.edit().putString("supabase_anon_key", value.trim()).apply()

    var syncKey: String
        get() = secrets.getString("sync_key", "") ?: ""
        set(value) = secrets.edit().putString("sync_key", value.trim()).apply()

    var tmdbApiKey: String
        get() = secrets.getString("tmdb_api_key", "") ?: ""
        set(value) = secrets.edit().putString("tmdb_api_key", value.trim()).apply()

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
     * Weekly digest — the Mac's keys and its defaults too: off unless asked for, Sunday, 19:00.
     * Weekdays are stored 1..7 Sunday-first, which is the Mac's convention, not java.time's.
     */
    var digestEnabled: Boolean
        get() = prefs.getBoolean("weekly_digest_enabled", false)
        set(value) = prefs.edit().putBoolean("weekly_digest_enabled", value).apply()

    var digestWeekday: Int
        get() = prefs.getInt("weekly_digest_weekday", 1)
        set(value) = prefs.edit().putInt("weekly_digest_weekday", value).apply()

    var digestTime: Int
        get() = prefs.getInt("weekly_digest_time", 19 * 3600)
        set(value) = prefs.edit().putInt("weekly_digest_time", value).apply()

    /**
     * Set once the one-time poster-colour pull has completed *successfully*.
     *
     * The bulk colour backfill preserved each row's `updated_at`, so the watermarked pull can never
     * see those colours — this flag is what makes the one explicit fetch happen, once, instead of on
     * every launch. Only set when the fetch actually reached the cloud, so a launch without network
     * tries again rather than giving up permanently.
     */
    var themeColorPullDone: Boolean
        get() = prefs.getBoolean("theme_color_pull_done", false)
        set(value) = prefs.edit().putBoolean("theme_color_pull_done", value).apply()

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
        secrets.edit()
            .putString("supabase_url", supabaseUrl.trim())
            .putString("supabase_anon_key", supabaseAnonKey.trim())
            .putString("sync_key", syncKey.trim())
            .putString("tmdb_api_key", tmdbApiKey.trim())
            .apply()
    }

}

/** The one preferences file the backup rules exclude — see the class KDoc. */
private const val SECRETS_FILE = "media_tracker_secrets"

private const val LEGACY_KEY_RELEASE_ALERTS = "release_alerts"
