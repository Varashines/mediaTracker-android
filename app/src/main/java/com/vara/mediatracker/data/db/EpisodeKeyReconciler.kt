package com.vara.mediatracker.data.db

import androidx.room.withTransaction
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds

/**
 * One-shot, idempotent repair of episode primary keys written by pre-canonical builds.
 *
 * Older builds emitted two incompatible formats for the same episode, so a single
 * TV episode could exist twice — once as `{tmdbId}_{s}_{e}` (created by TMDB
 * enrichment in the detail screen) and once as `tv_{showId}_s{s}_e{e}` (created by
 * the Supabase sync pull). This walks every episode row, re-keys the legacy ones
 * onto the canonical format, and merges watch state when both forms exist.
 *
 * Safe to run on every launch: once no legacy rows remain it is a no-op.
 */
class EpisodeKeyReconciler(private val db: AppDatabase) {

    /** @return number of episode rows re-keyed or merged. */
    suspend fun reconcile(): Int = db.withTransaction {
        val dao = db.mediaDao()
        val all = dao.getAllEpisodesList()
        if (all.isEmpty()) return@withTransaction 0

        val canonical = HashMap<String, EpisodeEntity>(all.size)
        val legacy = ArrayList<EpisodeEntity>()
        for (episode in all) {
            if (MediaIds.isCanonicalEpisodeId(episode.uniqueId)) {
                canonical[episode.uniqueId] = episode
            } else {
                legacy += episode
            }
        }
        if (legacy.isEmpty()) return@withTransaction 0

        val staleIds = ArrayList<String>(legacy.size)
        val upserts = ArrayList<EpisodeEntity>(legacy.size)
        var migrated = 0

        for (old in legacy) {
            val parsed = MediaIds.parseEpisodeUniqueId(old.uniqueId)
            val targetId = if (parsed == null) {
                // Unrecognized format — leave the row alone rather than corrupt it.
                continue
            } else {
                MediaIds.buildEpisodeUniqueId(parsed.showId, parsed.seasonNumber, parsed.episodeNumber)
            }
            if (targetId == old.uniqueId) continue

            val existing = canonical[targetId]
            val merged = if (existing == null) {
                old.copy(
                    uniqueId = targetId,
                    showId = MediaIds.tvShowKey(old.showId)
                )
            } else {
                mergeEpisodes(existing, old).also { canonical[targetId] = it }
            }

            staleIds += old.uniqueId
            upserts += merged
            migrated++
        }

        if (staleIds.isNotEmpty()) {
            // Delete legacy rows BEFORE inserting re-keyed ones so the re-keyed
            // primary keys are free to occupy.
            dao.deleteEpisodesByIds(staleIds)
        }
        if (upserts.isNotEmpty()) {
            dao.upsertEpisodes(upserts)
        }
        migrated
    }

    /**
     * Collapses a duplicated episode pair into one row. Watch state is additive
     * (watched if either copy was watched); metadata prefers the canonical row and
     * falls back to the legacy row for anything it is missing.
     */
    private fun mergeEpisodes(canonical: EpisodeEntity, legacy: EpisodeEntity): EpisodeEntity {
        val watched = canonical.isWatched || legacy.isWatched
        return canonical.copy(
            showId = MediaIds.tvShowKey(canonical.showId),
            name = canonical.name.ifBlank { legacy.name },
            overview = canonical.overview.ifBlank { legacy.overview },
            stillPath = canonical.stillPath ?: legacy.stillPath,
            isWatched = watched,
            watchedDate = if (watched) latest(canonical.watchedDate, legacy.watchedDate) else null,
            airDate = canonical.airDate ?: legacy.airDate,
            runtime = canonical.runtime ?: legacy.runtime,
            updatedAt = latest(canonical.updatedAt, legacy.updatedAt)
        )
    }

    /** ISO-8601 timestamps sort lexicographically, so the max is the newest. */
    private fun latest(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}