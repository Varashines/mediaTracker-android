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
        val todo = db.mediaDao().getItemsNeedingMetadataEnrichment(BATCH_SIZE)
        if (todo.isEmpty()) return Result.success(workDataOf(KEY_ITEMS to 0))

        val tmdb = container.tmdbClient()
        var processed = 0

        try {
            val gate = Semaphore(MAX_CONCURRENT)
            coroutineScope {
                todo.map { item ->
                    async(Dispatchers.IO) {
                        gate.withPermit { if (enrich(db, tmdb, item)) processed++ }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            return Result.retry()
        }

        return Result.success(workDataOf(KEY_ITEMS to processed))
    }

    /** @return true when the row was written (or was already fully populated). */
    private suspend fun enrich(
        db: AppDatabase,
        tmdb: TMDBClient,
        item: MediaItemEntity
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
        val providers = providerNames(providersResponse).takeIf { it.isNotEmpty() }?.joinToString(", ")

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
     * Flatrate provider names for the US region, falling back to the first region that
     * has any. Matches the macOS Discovery provider facet.
     */
    private fun providerNames(response: TMDBWatchProvidersResponse?): List<String> {
        if (response == null) return emptyList()
        val region = response.results["US"]
            ?: response.results.values.firstOrNull { !it.flatrate.isNullOrEmpty() }
            ?: return emptyList()
        return (region.flatrate ?: emptyList())
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
    }
}
