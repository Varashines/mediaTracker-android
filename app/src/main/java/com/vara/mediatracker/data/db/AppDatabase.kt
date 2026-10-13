package com.vara.mediatracker.data.db

import androidx.room.*
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE isSoftDeleted = 0 ORDER BY lastInteractionDate DESC")
    fun getAllActiveMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE state = :state AND isSoftDeleted = 0 ORDER BY lastInteractionDate DESC")
    fun getMediaByState(state: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getMediaById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items")
    suspend fun getAllMediaList(): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(item: MediaItemEntity)

    @Query("UPDATE media_items SET state = :state, lastStateChangeDate = :now WHERE id = :id")
    suspend fun updateState(id: String, state: String, now: String)

    @Query("UPDATE media_items SET taste = :taste WHERE id = :id")
    suspend fun updateTaste(id: String, taste: String)

    // Episodes
    @Query("SELECT * FROM tv_episodes")
    fun getAllEpisodesFlow(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM tv_episodes WHERE showId = :showId ORDER BY seasonNumber ASC, episodeNumber ASC")
    fun getEpisodesForShow(showId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM tv_episodes WHERE showId = :showId")
    suspend fun getEpisodesListForShow(showId: String): List<EpisodeEntity>

    @Query("SELECT * FROM tv_episodes WHERE uniqueId = :uniqueId LIMIT 1")
    suspend fun getEpisodeByUniqueId(uniqueId: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("UPDATE tv_episodes SET isWatched = :watched, watchedDate = :watchedDate WHERE uniqueId = :uniqueId")
    suspend fun setEpisodeWatched(uniqueId: String, watched: Boolean, watchedDate: String?)

    /**
     * TV shows whose episode metadata is missing or incomplete.
     *
     * Air dates only ever arrived by opening a DetailScreen, which left ~98% of the
     * episode table with no air date and made badge rules that depend on air times
     * unreachable. Ordered by recency so a backfill spends its budget on what the user
     * is most likely looking at.
     *
     * The `nextAiringInstant NOT LIKE '____-__-__T%'` clause re-enriches shows whose
     * stored instant predates the TVMaze ISO format (it used to be a `Date.toString()`
     * value), healing them without re-fetching shows that are already correct.
     */
    @Query(
        """
        SELECT * FROM media_items m
        WHERE m.isSoftDeleted = 0
          AND m.type LIKE '%TV%'
          AND (
            m.nextAiringInstant IS NULL
            OR m.nextAiringInstant NOT LIKE '____-__-__T%'
            OR NOT EXISTS (SELECT 1 FROM tv_episodes e WHERE e.showId = m.id)
            OR EXISTS (SELECT 1 FROM tv_episodes e WHERE e.showId = m.id AND e.airDate IS NULL)
          )
        ORDER BY m.lastInteractionDate DESC
        LIMIT :limit
        """
    )
    suspend fun getShowsNeedingEpisodeEnrichment(limit: Int): List<MediaItemEntity>

    /**
     * Titles missing Discovery Hub facets (genres / language / providers). One pass
     * fills them from TMDB details + watch providers; once set the row drops out.
     */
    @Query(
        """
        SELECT * FROM media_items
        WHERE isSoftDeleted = 0
          AND (genres IS NULL OR language IS NULL OR providers IS NULL)
        ORDER BY lastInteractionDate DESC
        LIMIT :limit
        """
    )
    suspend fun getItemsNeedingMetadataEnrichment(limit: Int): List<MediaItemEntity>

    /** Clears cached providers so they are re-fetched for a new region. */
    @Query("UPDATE media_items SET providers = NULL")
    suspend fun clearProviders()

    // --- Used by EpisodeKeyReconciler to rewrite legacy primary keys ---
    @Query("SELECT * FROM tv_episodes")
    suspend fun getAllEpisodesList(): List<EpisodeEntity>

    @Query("DELETE FROM tv_episodes WHERE uniqueId IN (:uniqueIds)")
    suspend fun deleteEpisodesByIds(uniqueIds: List<String>)
}

@Dao
interface SeasonDao {
    @Query("SELECT * FROM tv_seasons WHERE showId = :showId")
    suspend fun getSeasonsForShow(showId: String): List<TVSeasonEntity>

    @Query("SELECT * FROM tv_seasons")
    suspend fun getAllSeasons(): List<TVSeasonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeasons(seasons: List<TVSeasonEntity>)

    /** Season numbers that actually declare an episode count, keyed by season number. */
    @Query("SELECT * FROM tv_seasons WHERE showId = :showId AND episodeCount > 0")
    suspend fun getDeclaredCountsForShow(showId: String): List<TVSeasonEntity>
}

/**
 * Persisted badge state.
 *
 * Reads are Flows so a background recompute pushes straight into any open screen
 * without the UI having to recompute anything.
 */
@Dao
interface BadgeDao {
    @Query("SELECT * FROM media_badges")
    fun observeBadges(): Flow<List<MediaBadgeEntity>>

    @Query("SELECT * FROM media_badges WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getBadge(mediaId: String): MediaBadgeEntity?

    @Query("SELECT * FROM media_badges")
    suspend fun getAllBadges(): List<MediaBadgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBadges(badges: List<MediaBadgeEntity>)

    @Query("DELETE FROM media_badges")
    suspend fun deleteAllBadges()

    /** Drops rows for titles that no longer exist or were soft deleted. */
    @Query(
        "DELETE FROM media_badges WHERE mediaId NOT IN " +
            "(SELECT id FROM media_items WHERE isSoftDeleted = 0)"
    )
    suspend fun pruneOrphanedBadges()

    @Query("SELECT COUNT(*) FROM media_badges")
    suspend fun badgeCount(): Int
}

@Database(
    entities = [
        MediaItemEntity::class,
        EpisodeEntity::class,
        MediaBadgeEntity::class,
        TVSeasonEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun badgeDao(): BadgeDao
    abstract fun seasonDao(): SeasonDao
}