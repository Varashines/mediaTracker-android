package com.vara.mediatracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBWatchProvidersResponse
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Backfills the Discovery Hub facets (genres, language, studios, watch providers) for
 * every title in the library.
 *
 * macOS keeps these in dedicated Network/Genre/Language/Provider entities built by
 * `DiscoverySyncService`; Android stores them as comma-joined columns on the item and
 * aggregates at read time, so the hub needs no separate tables. A row drops out of the
 * query once its facets are set, and unresolvable facets are stored as empty strings so
 * a title is not re-fetched forever.
 */
class MetadataEnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = com.vara.mediatracker.di.AppContainer(applicationContext)
        if (container.preferences.tmdbApiKey.isBlank()) {
            return Result.success(workDataOf(KEY_ITEMS to 0))
        }

        val db = container.database
        val tmdb = container.tmdbClient()
        val region = effectiveRegion(container)

        // Region changed (manual pick or the device moved): drop cached providers so they
        // are re-fetched for the new region instead of showing the old country's services.
        if (container.preferences.lastEnrichRegion != region) {
            db.mediaDao().clearProviders()
            container.preferences.lastEnrichRegion = region
        }

        var processed = 0

        try {
            // Drain the backlog in one run instead of one small batch, so the Discovery
            // Hub fills on the first pass rather than over many foreground openings.
            var iterations = 0
            while (processed < MAX_TOTAL && iterations < MAX_ITERATIONS) {
                iterations++
                val todo = db.mediaDao().getItemsNeedingMetadataEnrichment(BATCH_SIZE)
                if (todo.isEmpty()) break

                val gate = Semaphore(MAX_CONCURRENT)
                val results = coroutineScope {
                    todo.map { item ->
                        async(Dispatchers.IO) {
                            gate.withPermit { enrich(db, tmdb, item, region) }
                        }
                    }.awaitAll()
                }
                val batchProcessed = results.count { it }
                processed += batchProcessed
                // A pass that wrote nothing means every item failed (bad key, offline);
                // stop rather than spin on the same rows.
                if (batchProcessed == 0) break
            }
        } catch (e: Exception) {
            return Result.retry()
        }

        return Result.success(workDataOf(KEY_ITEMS to processed))
    }

    /**
     * The region used to pick watch providers:
     *  1. the manual override from Settings,
     *  2. the SIM country (the best signal for where the user actually is — MIUI keeps
     *     the region separate from the locale, so an Indian phone can report `en-US`),
     *  3. the locale region,
     *  4. US.
     */
    private fun effectiveRegion(container: com.vara.mediatracker.di.AppContainer): String {
        val manual = container.preferences.providerRegion
        if (manual.length == 2) return manual

        val sim = simCountry()
        if (sim.length == 2) return sim

        val country = applicationContext.resources.configuration.locales[0].country
        return if (country.length == 2) country.uppercase(java.util.Locale.US) else "US"
    }

    private fun simCountry(): String = try {
        val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        tm?.simCountryIso?.takeIf { it.length == 2 }?.uppercase(java.util.Locale.US).orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** @return true when the row was written (or was already fully populated). */
    private suspend fun enrich(
        db: AppDatabase,
        tmdb: TMDBClient,
        item: MediaItemEntity,
        region: String
    ): Boolean {
        val tmdbId = MediaIds.tmdbIdOf(item.id)
        if (tmdbId <= 0) return false

        val isMovie = item.type.contains("Movie", ignoreCase = true)
        val details = (if (isMovie) tmdb.getMovieDetails(tmdbId) else tmdb.getTVDetails(tmdbId))
            .getOrNull() ?: return false

        val genres = details.genres?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        val language = details.original_language?.takeIf { it.isNotBlank() }

        // Production companies are the "studio" facet for both movies and shows. TV
        // networks are surfaced separately via the network facet, so they are not
        // duplicated here.
        val studios = details.production_companies?.map { it.name }
            ?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(", ")

        val network = item.network ?: if (isMovie) {
            details.production_companies?.firstOrNull()?.name
        } else {
            details.networks?.firstOrNull()?.name
        }

        val providersResponse = (if (isMovie) {
            tmdb.getMovieWatchProviders(tmdbId)
        } else {
            tmdb.getTVWatchProviders(tmdbId)
        }).getOrNull()
        val providers = providerNames(providersResponse, region).takeIf { it.isNotEmpty() }?.joinToString(", ")

        db.mediaDao().upsertMedia(
            item.copy(
                genres = genres ?: "",
                language = language ?: "",
                studios = studios ?: "",
                // Empty string marks "resolved, none available" so the row drops out of
                // the enrichment query instead of being retried forever.
                providers = providers ?: "",
                network = network ?: item.network
            )
        )
        return true
    }

    /**
     * Flatrate + free providers for the device's region, mirroring macOS
     * `extractWatchProviders`: only that region (no fallback), blocklisted
     * carrier-bundled/add-on providers dropped, "... Channel" add-ons dropped, deduped
     * by provider id.
     */
    private fun providerNames(response: TMDBWatchProvidersResponse?, region: String): List<String> {
        val regionData = response?.results?.get(region) ?: return emptyList()
        val merged = (regionData.flatrate ?: emptyList()) + (regionData.free ?: emptyList())
        val seen = mutableSetOf<Int>()
        return merged
            .filter { it.provider_id == null || it.provider_id !in BLOCKED_PROVIDER_IDS }
            .filter {
                val name = it.provider_name.lowercase()
                !name.contains("amazon channel") &&
                    !name.contains("prime video channel") &&
                    !name.contains("apple tv channel")
            }
            .filter { it.provider_id == null || seen.add(it.provider_id) }
            .map { it.provider_name }
            .filter { it.isNotBlank() }
            .distinct()
    }

    companion object {
        const val KEY_ITEMS = "items"

        /** Items per pass; a normal library converges in a couple of passes. */
        const val BATCH_SIZE = 150

        /** Concurrent TMDB calls, kept inside the rate limit. */
        const val MAX_CONCURRENT = 6

        /** Cap a single run so it cannot crawl a huge library forever. */
        const val MAX_TOTAL = 750
        const val MAX_ITERATIONS = 6

        /**
         * Carrier-bundled, add-on channel, and aggregator providers, matching macOS
         * `blockedProviderIDs`.
         */
        private val BLOCKED_PROVIDER_IDS = setOf(
            614,  // VI movies and tv
            502,  // Tata Play
            1898, // Amazon MX Player
            2100, // Amazon Prime Video with Ads
            2285  // JustWatch TV
        )
    }
}
