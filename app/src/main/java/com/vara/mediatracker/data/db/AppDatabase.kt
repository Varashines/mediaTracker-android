package com.vara.mediatracker.data.db

import androidx.room.*
import com.vara.mediatracker.data.model.CollectionConverters
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.FacetLogoEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
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

    /**
     * State change. Also stamps `lastInteractionDate` — it is the sync conflict key
     * ("last user edit"), so it must move for any user-visible tracking change.
     */
    @Query("UPDATE media_items SET state = :state, lastStateChangeDate = :now, lastInteractionDate = :now WHERE id = :id")
    suspend fun updateState(id: String, state: String, now: String)

    /** Bulk state change for Library multi-select: one statement instead of N. */
    @Query("UPDATE media_items SET state = :state, lastStateChangeDate = :now, lastInteractionDate = :now WHERE id IN (:ids)")
    suspend fun updateStateForIds(ids: List<String>, state: String, now: String)

    /**
     * Soft-deletes a title. Rows are kept (tombstoned) so the delete syncs across devices
     * and can be undone; every read already filters `isSoftDeleted = 0`.
     */
    @Query("UPDATE media_items SET isSoftDeleted = 1, lastStateChangeDate = :now, lastInteractionDate = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: String)

    @Query("UPDATE media_items SET isSoftDeleted = 1, lastStateChangeDate = :now, lastInteractionDate = :now WHERE id IN (:ids)")
    suspend fun softDeleteForIds(ids: List<String>, now: String)

    /**
     * Taste change (like/love). Stamps `lastInteractionDate` too — a like is a user edit and
     * the sync keys on it, so without this an Android like never looked newer than the Mac.
     */
    @Query("UPDATE media_items SET taste = :taste, lastInteractionDate = :now WHERE id = :id")
    suspend fun updateTaste(id: String, taste: String, now: String)

    /** One-off heal: rewrite a legacy taste spelling to its canonical form. */
    @Query("UPDATE media_items SET taste = :canonical WHERE taste = :legacy")
    suspend fun replaceTaste(legacy: String, canonical: String)

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

    @Query("UPDATE tv_episodes SET isWatched = :watched, watchedDate = :watchedDate WHERE showId = :showId AND seasonNumber = :seasonNumber")
    suspend fun setSeasonEpisodesWatched(showId: String, seasonNumber: Int, watched: Boolean, watchedDate: String?)

    @Query("UPDATE tv_episodes SET isWatched = :watched, watchedDate = :watchedDate WHERE showId = :showId")
    suspend fun setAllEpisodesWatched(showId: String, watched: Boolean, watchedDate: String?)

    @Query("UPDATE media_items SET lastInteractionDate = :now WHERE id = :id")
    suspend fun updateLastInteractionDate(id: String, now: String)

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

/** Facet logo paths for the Discovery/Library browse surfaces. */
@Dao
interface FacetLogoDao {
    @Query("SELECT * FROM facet_logos")
    fun observeLogos(): Flow<List<FacetLogoEntity>>

    @Query("SELECT logoPath FROM facet_logos WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun logoFor(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLogos(logos: List<FacetLogoEntity>)
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

/** Collections + membership. Mirrors macOS's `MediaCollection` model. */
@Dao
interface CollectionDao {
    /**
     * All live collections for the UI. Smart collections carry rules rather than stored
     * members; their contents are evaluated in-memory (see `SmartRules`).
     */
    @Query("SELECT * FROM collections WHERE isDeleted = 0 ORDER BY isPinned DESC, name COLLATE NOCASE ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections")
    suspend fun getAllCollections(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getCollection(id: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(collections: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Query("SELECT * FROM collection_items")
    suspend fun getAllCollectionItems(): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId AND isDeleted = 0")
    fun observeItems(collectionId: String): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection_items WHERE isDeleted = 0")
    fun observeAllItems(): Flow<List<CollectionItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionItems(items: List<CollectionItemEntity>)
}

/** Watch history: the per-viewing cycles and the immutable events behind them. */
@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_cycles")
    suspend fun getAllCycles(): List<WatchCycleEntity>

    @Query("SELECT * FROM watch_events")
    suspend fun getAllEvents(): List<WatchEventEntity>

    @Query("SELECT * FROM watch_cycles WHERE mediaId = :mediaId ORDER BY startedAt DESC")
    suspend fun getCyclesFor(mediaId: String): List<WatchCycleEntity>

    /** The cycle a new watch belongs to: the newest one that hasn't been closed out. */
    @Query("SELECT * FROM watch_cycles WHERE mediaId = :mediaId AND state != 'completed' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenCycle(mediaId: String): WatchCycleEntity?

    /** The live event for one episode (or the movie itself when [episodeId] is null). */
    @Query("SELECT * FROM watch_events WHERE mediaId = :mediaId AND episodeId IS :episodeId AND voidedAt IS NULL LIMIT 1")
    suspend fun getActiveEvent(mediaId: String, episodeId: String?): WatchEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCycles(cycles: List<WatchCycleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<WatchEventEntity>)
}

@Database(
    entities = [
        MediaItemEntity::class,
        EpisodeEntity::class,
        MediaBadgeEntity::class,
        TVSeasonEntity::class,
        FacetLogoEntity::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        WatchCycleEntity::class,
        WatchEventEntity::class
    ],
    version = 15,
    exportSchema = true
)
@TypeConverters(CollectionConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun badgeDao(): BadgeDao
    abstract fun seasonDao(): SeasonDao
    abstract fun facetLogoDao(): FacetLogoDao
    abstract fun collectionDao(): CollectionDao
    abstract fun watchHistoryDao(): WatchHistoryDao
}