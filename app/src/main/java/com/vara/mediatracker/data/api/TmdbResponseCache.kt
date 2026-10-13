package com.vara.mediatracker.data.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived in-memory cache for TMDB detail / credit / season responses.
 *
 * Opening a show's detail re-fetches its details, credits, and every season in one burst,
 * so a title you look at repeatedly pays that cost each time. Caching the parsed
 * responses for a few hours removes the repeat traffic while still picking up edits
 * eventually. Deliberately small and process-local — no persistence, no eviction policy
 * beyond the TTL.
 */
object TmdbResponseCache {
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private data class Entry(val value: Any, val at: Long)

    private val store = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val entry = store[key] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            store.remove(key)
            return null
        }
        return entry.value as? T
    }

    fun put(key: String, value: Any) {
        store[key] = Entry(value, System.currentTimeMillis())
    }

    fun detailsKey(id: String) = "details:$id"
    fun creditsKey(id: String) = "credits:$id"
    fun seasonKey(id: String, season: Int) = "season:$id:$season"
}
