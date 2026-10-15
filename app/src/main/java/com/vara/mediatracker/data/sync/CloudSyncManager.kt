package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.db.MediaDao
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.data.model.WatchCycleEntity
import com.vara.mediatracker.data.model.WatchEventEntity
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vara.mediatracker.data.api.TMDBClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

@Serializable
data class RemoteMedia(
    val user_id: String,
    val media_id: String,
    val type: String,
    val state: String,
    val taste: String = "None",
    val custom_poster_url: String? = null,
    val is_soft_deleted: Boolean = false,
    val last_interaction_date: String? = null,
    val last_state_change_date: String? = null,
    /** When the title joined the library — the Mac's `dateAdded`. Never moves on a watch. */
    val date_added: String? = null,
    val updated_at: String? = null
)

@Serializable
data class RemoteEpisode(
    val user_id: String,
    val episode_id: String,
    val show_id: String,
    val season_number: Int,
    val episode_number: Int,
    val is_watched: Boolean,
    val watched_date: String? = null,
    val updated_at: String? = null
)

@Serializable
data class RemoteCollection(
    val user_id: String,
    val collection_id: String,
    val name: String,
    val system_image: String = "folder",
    val notes: String? = null,
    val is_pinned: Boolean = false,
    val is_smart: Boolean = false,
    val smart_rules: String? = null,
    val completed_item_ids: List<String> = emptyList(),
    val is_deleted: Boolean = false,
    val updated_at: String? = null
)

@Serializable
data class RemoteCollectionItem(
    val user_id: String,
    val collection_id: String,
    val media_id: String,
    val is_deleted: Boolean = false,
    val updated_at: String? = null
)

/*
 * Watch history payloads carry *no* default values on purpose: kotlinx.serialization omits
 * fields that equal their default (and the app's Json has encodeDefaults = false), so a batch
 * mixing rows with and without `episode_id` would send objects with different keys and
 * PostgREST rejects that with PGRST102 "All object keys must match".
 */
@Serializable
data class RemoteWatchCycle(
    val user_id: String,
    val cycle_id: String,
    val media_id: String,
    val kind: String,
    val started_at: String,
    val completed_at: String?,
    val state: String,
    val is_rewatch: Boolean,
    val is_complete: Boolean,
    val scope_episode_ids: List<String>,
    val is_backfilled: Boolean,
    val updated_at: String?
)

@Serializable
data class RemoteWatchEvent(
    val user_id: String,
    val event_id: String,
    val cycle_id: String,
    val media_id: String,
    val episode_id: String?,
    val watched_at: String,
    val source: String,
    val timezone_identifier: String?,
    val runtime_minutes: Int?,
    val voided_at: String?,
    val deduplication_key: String,
    val is_backfilled: Boolean,
    val updated_at: String?
)

class CloudSyncManager(
    private val db: AppDatabase,
    private val mediaDao: MediaDao,
    private val supabaseUrl: String,
    private val anonKey: String,
    private val syncKey: String,
    private val tmdbApiKey: String = "",
    private val client: HttpClient = defaultClient()
) {
    companion object {
        /**
         * TMDB allows a generous rate, but a small in-flight cap keeps the sync well
         * inside it and leaves headroom on constrained connections. Raising this
         * dramatically is the first thing to try if enrichment is still slow.
         */
        private const val MAX_CONCURRENT_TMDB_FETCHES = 16

        /** Rows written per upsert while enrichment streams in. */
        private const val ENRICHMENT_FLUSH_SIZE = 25

        /** Fallback for call sites outside the DI graph; prefer injecting a shared client. */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    /** Media row plus any season metadata recovered from the same response. */
    private data class Enriched(val media: MediaItemEntity, val seasons: List<TVSeasonEntity>)

    /**
     * Fetches TMDB metadata for a single item, or null if it has no resolvable TMDB id.
     *
     * Season lengths come out of the TV details payload we already paid for, so
     * recording them during sync costs no extra requests and means FINALE badges are
     * correct without ever opening a detail screen.
     */
    private suspend fun enrichItem(
        tmdbClient: TMDBClient,
        item: MediaItemEntity
    ): Enriched? {
        val tmdbId = MediaIds.tmdbIdOf(item.id)
        if (tmdbId <= 0) return null
        val isMovie = item.type.contains("Movie", ignoreCase = true)
        val details = (if (isMovie) tmdbClient.getMovieDetails(tmdbId) else tmdbClient.getTVDetails(tmdbId))
            .getOrNull()

        val showId = MediaIds.tvShowKey(item.id)
        val seasons = if (isMovie) {
            emptyList()
        } else {
            details?.seasons.orEmpty()
                .filter { it.season_number >= 1 }
                .map { season ->
                    TVSeasonEntity(
                        showId = showId,
                        seasonNumber = season.season_number,
                        episodeCount = season.episode_count ?: 0,
                        name = season.name,
                        airDate = season.air_date
                    )
                }
        }

        val poster = TMDBClient.buildPosterUrl(details?.poster_path) ?: item.posterUrl
        return Enriched(
            media = item.copy(
                title = details?.title ?: details?.name ?: item.title,
                overview = details?.overview ?: item.overview,
                posterUrl = poster,
                releaseDate = details?.release_date ?: details?.first_air_date ?: item.releaseDate
            ),
            seasons = seasons
        )
    }

    /**
     * UTC ISO-8601 with milliseconds, matching macOS's `ISO8601DateFormatter`
     * (`.withInternetDateTime, .withFractionalSeconds`).
     *
     * `DateTimeFormatter` is immutable/thread-safe, unlike the shared `SimpleDateFormat`
     * this replaced (same class of bug as `BadgeEngine.parseDate`).
     */
    private val isoFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    private fun isoNow(): String = isoFormat.format(Instant.now())

    /**
     * Pulls library state from Supabase and applies updates locally.
     */
    suspend fun pullFromCloud(): Result<Int> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }

        // 1. Pull Media Items (paginated — see fetchRemoteMedia).
        val remoteMediaList: List<RemoteMedia> = fetchRemoteMedia()
        val tmdbClient = if (tmdbApiKey.isNotBlank()) TMDBClient(tmdbApiKey, client) else null

        // Fetch existing database cache to preserve already-fetched titles & posters
        val existingMap = mediaDao.getAllMediaList().associateBy { it.id }

        // Update states and tastes immediately without overwriting posters/titles
        val updatedEntities = remoteMediaList.map { rem ->
            val existing = existingMap[rem.media_id]
            MediaItemEntity(
                id = rem.media_id,
                title = existing?.title?.takeIf { it != rem.media_id } ?: rem.media_id,
                overview = existing?.overview ?: "",
                posterUrl = existing?.posterUrl,
                backdropUrl = existing?.backdropUrl,
                releaseDate = existing?.releaseDate,
                type = rem.type,
                state = rem.state,
                taste = rem.taste,
                // Preserve locally-derived air + discovery metadata; the remote schema
                // carries none of it, and reconstructing without these wiped the TVMaze
                // timezone/network/instant and the Discovery facets (genres/language/
                // studios/providers) on every pull.
                nextAiringInstant = existing?.nextAiringInstant,
                timezone = existing?.timezone,
                network = existing?.network,
                genres = existing?.genres,
                language = existing?.language,
                studios = existing?.studios,
                providers = existing?.providers,
                customPosterUrl = rem.custom_poster_url,
                isSoftDeleted = rem.is_soft_deleted,
                lastInteractionDate = rem.last_interaction_date,
                lastStateChangeDate = rem.last_state_change_date,
                updatedAt = rem.updated_at
            )
        }
        mediaDao.upsertMedia(updatedEntities)

        // Smart enrichment: ONLY query TMDB for items that don't have a poster or proper title yet!
        val itemsNeedingMetadata = if (tmdbClient != null) {
            updatedEntities.filter { it.posterUrl.isNullOrBlank() || it.title == it.id }
        } else emptyList()

        if (itemsNeedingMetadata.isNotEmpty() && tmdbClient != null) {
            // Previously this walked `chunked(10)` sequentially, so an 800-item library
            // took 80 fully serialized rounds and each round blocked the next. A shared
            // semaphore lets every item be in flight up to the cap, and results are
            // flushed in small batches so the UI fills in progressively instead of all
            // at once at the end.
            val gate = Semaphore(MAX_CONCURRENT_TMDB_FETCHES)
            val pending = Channel<Enriched>(Channel.UNLIMITED)

            coroutineScope {
                val workers = itemsNeedingMetadata.map { item ->
                    async(Dispatchers.IO) {
                        gate.withPermit { enrichItem(tmdbClient, item) }
                            ?.let { pending.send(it) }
                    }
                }
                val flusher = launch(Dispatchers.IO) {
                    val mediaBatch = ArrayList<MediaItemEntity>(ENRICHMENT_FLUSH_SIZE)
                    val seasonBatch = ArrayList<TVSeasonEntity>()
                    for (enriched in pending) {
                        mediaBatch.add(enriched.media)
                        seasonBatch.addAll(enriched.seasons)
                        if (mediaBatch.size >= ENRICHMENT_FLUSH_SIZE) {
                            mediaDao.upsertMedia(mediaBatch.toList())
                            mediaBatch.clear()
                        }
                    }
                    if (mediaBatch.isNotEmpty()) mediaDao.upsertMedia(mediaBatch.toList())
                    if (seasonBatch.isNotEmpty()) {
                        db.seasonDao().upsertSeasons(seasonBatch)
                    }
                }
                workers.awaitAll()
                pending.close()
                flusher.join()
            }
        }

        // 2. Pull Episodes Progress (Paginated in batches of 1000 to overcome Supabase limit)
        var offset = 0
        val pageSize = 1000
        var hasMore = true
        var totalEpisodesFetched = 0

        while (hasMore) {
            val from = offset
            val to = offset + pageSize - 1
            val epResponse: HttpResponse = client.get("$supabaseUrl/rest/v1/user_episodes") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                header("x-sync-key", syncKey)
                header("Range", "$from-$to")
            }

            val remoteEpList: List<RemoteEpisode> = epResponse.body()
            if (remoteEpList.isNotEmpty()) {
                val localEpisodes = remoteEpList.map { rem ->
                    EpisodeEntity(
                        uniqueId = MediaIds.parseEpisodeUniqueId(rem.episode_id)
                            ?.let { MediaIds.buildEpisodeUniqueId(it.showId, it.seasonNumber, it.episodeNumber) }
                            ?: rem.episode_id,
                        showId = MediaIds.tvShowKey(rem.show_id),
                        seasonNumber = rem.season_number,
                        episodeNumber = rem.episode_number,
                        isWatched = rem.is_watched,
                        watchedDate = rem.watched_date,
                        updatedAt = rem.updated_at
                    )
                }
                mediaDao.upsertEpisodes(localEpisodes)
                totalEpisodesFetched += remoteEpList.size
                offset += remoteEpList.size
                if (remoteEpList.size < pageSize) {
                    hasMore = false
                }
            } else {
                hasMore = false
            }
        }

        remoteMediaList.size + totalEpisodesFetched
    }

    /**
     * Pushes a local episode toggle directly to the cloud.
     */
    suspend fun pushEpisodeWatch(showId: String, season: Int, episode: Int, watched: Boolean) = runCatching {
        val now = isoNow()
        val canonicalShowId = MediaIds.tvShowKey(showId)
        val epId = MediaIds.buildCloudEpisodeId(canonicalShowId, season, episode)
        val payload = RemoteEpisode(
            user_id = syncKey,
            episode_id = epId,
            show_id = canonicalShowId,
            season_number = season,
            episode_number = episode,
            is_watched = watched,
            watched_date = if (watched) now else null,
            updated_at = now
        )

        client.post("$supabaseUrl/rest/v1/user_episodes") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /**
     * Pushes media state or taste changes directly to the cloud.
     */
    /**
     * Pushes a state or taste change.
     *
     * [isStateChange] must be false for a taste-only change: `last_state_change_date` drives
     * macOS's "watched this year", so advancing it on a like would log an un-watched title as
     * watched. When false the field is omitted (kotlinx omits defaults), leaving the cloud's
     * existing value untouched.
     */
    suspend fun pushMediaUpdate(
        mediaId: String,
        type: String,
        state: String,
        taste: String,
        isStateChange: Boolean = true,
        /** Set when this push also introduces the title, so its "added" time rides along. */
        dateAdded: String? = null
    ) = runCatching {
        val now = isoNow()
        val payload = RemoteMedia(
            user_id = syncKey,
            media_id = mediaId,
            type = type,
            state = state,
            taste = taste,
            last_interaction_date = now,
            last_state_change_date = if (isStateChange) now else null,
            date_added = dateAdded,
            updated_at = now
        )

        client.post("$supabaseUrl/rest/v1/user_media") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /** Incoming/outgoing counts for one two-way sync, for the UI indicator. */
    data class SyncSummary(val outgoing: Int, val incoming: Int, val watermark: String? = null)

    /** Pre-sync preview: what a sync would change, without applying anything. */
    data class SyncImpact(
        val incomingTitles: List<String>,
        val outgoingTitles: List<String>,
        val conflictTitles: List<String>,
        val incomingEpisodes: Int,
        val outgoingEpisodes: Int,
        val incomingCollections: List<String> = emptyList(),
        val outgoingCollections: List<String> = emptyList(),
        val incomingCollectionItems: Int = 0,
        val outgoingCollectionItems: Int = 0
    ) {
        val totalIncoming: Int
            get() = incomingTitles.size + incomingEpisodes + incomingCollections.size + incomingCollectionItems
        val totalOutgoing: Int
            get() = outgoingTitles.size + outgoingEpisodes + outgoingCollections.size + outgoingCollectionItems
        val hasChanges: Boolean get() = totalIncoming + totalOutgoing > 0
    }

    private data class Reconcile(
        val outgoingMedia: List<MediaItemEntity>,
        val incomingMedia: List<MediaItemEntity>,
        val outgoingEpisodes: List<EpisodeEntity>,
        val incomingEpisodes: List<EpisodeEntity>,
        val conflictTitles: List<String>,
        val outgoingCollections: List<CollectionEntity> = emptyList(),
        val incomingCollections: List<CollectionEntity> = emptyList(),
        val outgoingCollectionItems: List<CollectionItemEntity> = emptyList(),
        val incomingCollectionItems: List<CollectionItemEntity> = emptyList(),
        val outgoingCycles: List<WatchCycleEntity> = emptyList(),
        val incomingCycles: List<WatchCycleEntity> = emptyList(),
        val outgoingWatchEvents: List<WatchEventEntity> = emptyList(),
        val incomingWatchEvents: List<WatchEventEntity> = emptyList(),
        /** Newest server `updated_at` seen, to advance the incremental pull watermark. */
        val watermark: String? = null
    )

    /** Computes what a sync would change, without writing anything (macOS SyncImpact). */
    suspend fun computeImpact(since: String? = null): Result<SyncImpact> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }
        val r = reconcile(since)
        SyncImpact(
            incomingTitles = r.incomingMedia.map { it.title },
            outgoingTitles = r.outgoingMedia.map { it.title },
            conflictTitles = r.conflictTitles,
            incomingEpisodes = r.incomingEpisodes.size,
            outgoingEpisodes = r.outgoingEpisodes.size,
            incomingCollections = r.incomingCollections.map { it.name },
            outgoingCollections = r.outgoingCollections.map { it.name },
            incomingCollectionItems = r.incomingCollectionItems.size,
            outgoingCollectionItems = r.outgoingCollectionItems.size
        )
    }

    /**
     * Full two-way sync: push local wins and apply cloud wins. Conflicts are
     * last-write-wins by timestamp. Prefer [computeImpact] first to preview it.
     *
     * @return how many rows were sent up and applied down.
     */
    suspend fun sync(since: String? = null): Result<SyncSummary> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }
        val r = reconcile(since)
        if (r.incomingMedia.isNotEmpty()) mediaDao.upsertMedia(r.incomingMedia)
        if (r.incomingEpisodes.isNotEmpty()) mediaDao.upsertEpisodes(r.incomingEpisodes)
        if (r.incomingCollections.isNotEmpty()) db.collectionDao().upsertCollections(r.incomingCollections)
        if (r.incomingCollectionItems.isNotEmpty()) db.collectionDao().upsertCollectionItems(r.incomingCollectionItems)
        if (r.incomingCycles.isNotEmpty()) db.watchHistoryDao().upsertCycles(r.incomingCycles)
        if (r.incomingWatchEvents.isNotEmpty()) db.watchHistoryDao().upsertEvents(r.incomingWatchEvents)
        pushMediaBatch(r.outgoingMedia)
        pushEpisodeBatch(r.outgoingEpisodes)
        pushCollections(r.outgoingCollections)
        pushCollectionItems(r.outgoingCollectionItems)
        pushWatchCycles(r.outgoingCycles)
        pushWatchEvents(r.outgoingWatchEvents)
        SyncSummary(
            outgoing = r.outgoingMedia.size + r.outgoingEpisodes.size +
                r.outgoingCollections.size + r.outgoingCollectionItems.size +
                r.outgoingCycles.size + r.outgoingWatchEvents.size,
            incoming = r.incomingMedia.size + r.incomingEpisodes.size +
                r.incomingCollections.size + r.incomingCollectionItems.size +
                r.incomingCycles.size + r.incomingWatchEvents.size,
            watermark = r.watermark
        )
    }

    /** Fetches the cloud and diffs it against the local library. Pure read. */
    private suspend fun reconcile(since: String? = null): Reconcile {
        val remoteMedia = fetchRemoteMedia(since)
        val remoteEpisodes = fetchRemoteEpisodes(since)
        val remoteMediaById = remoteMedia.associateBy { it.media_id }
        // Cloud rows carry legacy episode ids ("1412_5_10"); normalize to the canonical
        // "tv_1412_s5_e10" so they actually match the local rows.
        val remoteEpisodesById = remoteEpisodes.associateBy { canonicalEpisodeId(it.episode_id) }

        val localMedia = mediaDao.getAllMediaList()
        val localEpisodes = mediaDao.getAllEpisodesList()
        val localMediaById = localMedia.associateBy { it.id }

        val outgoingMedia = mutableListOf<MediaItemEntity>()
        val incomingMedia = mutableListOf<MediaItemEntity>()
        val conflictTitles = mutableListOf<String>()
        for (local in localMedia) {
            val remote = remoteMediaById[local.id]
            if (remote == null) {
                // Under a watermarked fetch, "absent from the response" means *unchanged*, not
                // "not in the cloud" — so only push when this device actually edited the row
                // since the last sync. (With no watermark this is a full pull, where absent
                // really does mean missing.)
                if (isNewerThanWatermark(local, since)) outgoingMedia.add(local)
                continue
            }
            // A differing state/taste *or* a differing soft-delete is a conflict. Without the
            // soft-delete term a delete on the other device was never seen here, so the title
            // simply stayed put.
            val differs = !local.state.equals(remote.state, true) ||
                !local.taste.equals(remote.taste, true) ||
                local.isSoftDeleted != remote.is_soft_deleted
            if (differs) {
                conflictTitles.add(local.title)
                // Compare on the *user-edit* time, mirroring macOS's `syncEditTime`: whichever
                // device edited state/taste last wins. (Not `updated_at`, which is the row's
                // write time and would let a metadata refresh outrank a real edit.)
                val localTime = parseInstant(local.lastInteractionDate) ?: parseInstant(local.lastStateChangeDate)
                val remoteTime = parseInstant(remote.last_interaction_date) ?: parseInstant(remote.updated_at)
                if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                    incomingMedia.add(fromRemote(remote, local))
                } else {
                    outgoingMedia.add(local)
                }
            } else if (local.dateAdded == null && remote.date_added != null) {
                // Pure backfill: nothing conflicts, we just never learned when this was added.
                // date_added is immutable and one-way, so there is nothing to resolve — and
                // without this it never arrived, because fromRemote only runs for rows that
                // differ or are new.
                incomingMedia.add(local.copy(dateAdded = remote.date_added))
            }
        }
        for (remote in remoteMedia) {
            if (!localMediaById.containsKey(remote.media_id)) incomingMedia.add(fromRemote(remote, null))
        }

        val outgoingEpisodes = mutableListOf<EpisodeEntity>()
        val incomingEpisodes = mutableListOf<EpisodeEntity>()
        for (local in localEpisodes) {
            val remote = remoteEpisodesById[local.uniqueId]
            if (remote == null) {
                // Watermarked fetch: absent means "unchanged", so only push a watch this
                // device made since the last sync.
                if (local.isWatched && changedSince(local.watchedDate, since)) outgoingEpisodes.add(local)
                continue
            }
            if (local.isWatched != remote.is_watched) {
                val localTime = parseInstant(local.watchedDate)
                val remoteTime = parseInstant(remote.updated_at)
                if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                    incomingEpisodes.add(local.copy(isWatched = remote.is_watched, watchedDate = remote.watched_date))
                } else {
                    outgoingEpisodes.add(local)
                }
            }
        }

        val collections = reconcileCollections(since)
        val history = reconcileWatchHistory(since)
        return Reconcile(
            outgoingMedia, incomingMedia, outgoingEpisodes, incomingEpisodes, conflictTitles,
            outgoingCollections = collections.outgoingCollections,
            incomingCollections = collections.incomingCollections,
            outgoingCollectionItems = collections.outgoingItems,
            incomingCollectionItems = collections.incomingItems,
            outgoingCycles = history.outgoingCycles,
            incomingCycles = history.incomingCycles,
            outgoingWatchEvents = history.outgoingEvents,
            incomingWatchEvents = history.incomingEvents,
            // Advance the watermark to the newest server stamp we have seen (minus a margin
            // for reads racing an in-flight commit), so the next pull only asks for changes.
            watermark = nextWatermark(since, remoteMedia.map { it.updated_at } + remoteEpisodes.map { it.updated_at })
        )
    }

    /**
     * Whether this device has edited [local] since [watermark].
     *
     * Compares parsed instants, not the raw strings: the watermark comes from
     * `Instant.toString()` (which omits fractional seconds when they are zero) while local
     * stamps always carry millis, so a lexicographic compare would be wrong.
     * A nil watermark means the last sync was a full pull.
     */
    /**
     * True when [stamp] is newer than [watermark] — or when there is no watermark, i.e. the
     * last sync was a full pull, where "absent remotely" genuinely means "missing".
     *
     * Compares parsed instants, not the raw strings: the watermark comes from
     * `Instant.toString()` (which omits trailing zero fractions) while local stamps carry
     * millis, so a lexicographic compare is wrong.
     */
    private fun changedSince(stamp: String?, watermark: String?): Boolean {
        if (watermark.isNullOrBlank()) return true
        val since = parseInstant(watermark) ?: return true
        val value = parseInstant(stamp) ?: return true
        return value.after(since)
    }

    private fun isNewerThanWatermark(local: MediaItemEntity, watermark: String?): Boolean {
        if (watermark.isNullOrBlank()) return true
        val since = parseInstant(watermark) ?: return true
        val edited = parseInstant(local.lastInteractionDate) ?: parseInstant(local.lastStateChangeDate)
        return edited == null || edited.after(since)
    }

    /**
     * The watermark to store after this pull: the newest server `updated_at` observed, backed
     * off slightly. Server time, not the device clock — a fast device clock would skip rows.
     */
    private fun nextWatermark(previous: String?, stamps: List<String?>): String? {
        val newest = stamps.filterNotNull().filter { it.isNotBlank() }.maxOrNull() ?: return previous
        return runCatching {
            java.time.Instant.parse(newest).minusSeconds(5).toString()
        }.getOrNull() ?: newest
    }

    private suspend fun reconcileWatchHistory(since: String? = null): WatchHistoryReconcile {
        val remoteCycles = fetchRemoteWatchCycles(since)
        val remoteEvents = fetchRemoteWatchEvents(since)
        val localCycles = db.watchHistoryDao().getAllCycles()
        val localEvents = db.watchHistoryDao().getAllEvents()

        val remoteCycleById = remoteCycles.associateBy { it.cycle_id }
        val remoteEventById = remoteEvents.associateBy { it.event_id }
        val localEventById = localEvents.associateBy { it.id }
        val localCycleIds = localCycles.mapTo(HashSet()) { it.id }

        val outgoingCycles = mutableListOf<WatchCycleEntity>()
        val incomingCycles = mutableListOf<WatchCycleEntity>()
        for (local in localCycles) {
            val remote = remoteCycleById[local.id]
            if (remote == null) {
                if (changedSince(local.updatedAt, since)) outgoingCycles.add(local)
                continue
            }
            // A cycle only progresses (started -> completed), so completedAt ?? startedAt
            // orders it well enough — it has no modified timestamp of its own.
            val localTime = cycleChangeTime(local.startedAt, local.completedAt)
            val remoteTime = cycleChangeTime(remote.started_at, remote.completed_at)
            if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                incomingCycles.add(
                    local.copy(
                        state = remote.state,
                        completedAt = remote.completed_at,
                        isComplete = remote.is_complete,
                        updatedAt = remote.updated_at
                    )
                )
            }
        }
        for (remote in remoteCycles) {
            if (!localCycleIds.contains(remote.cycle_id)) {
                incomingCycles.add(
                    WatchCycleEntity(
                        id = remote.cycle_id,
                        mediaId = remote.media_id,
                        kind = remote.kind,
                        startedAt = remote.started_at,
                        completedAt = remote.completed_at,
                        state = remote.state,
                        isRewatch = remote.is_rewatch,
                        isComplete = remote.is_complete,
                        scopeEpisodeIds = remote.scope_episode_ids,
                        isBackfilled = true,
                        updatedAt = remote.updated_at
                    )
                )
            }
        }

        val outgoingEvents = mutableListOf<WatchEventEntity>()
        val incomingEvents = mutableListOf<WatchEventEntity>()
        for (local in localEvents) {
            val remote = remoteEventById[local.id]
            // Events are immutable facts: push when it carries a void the cloud doesn't (the
            // only forward change), or — under a full pull — when the cloud lacks it entirely.
            // With a watermark, "missing from the response" means unchanged.
            if (remote != null) {
                if (local.voidedAt != null && remote.voided_at == null) outgoingEvents.add(local)
            } else if (changedSince(local.updatedAt, since)) {
                outgoingEvents.add(local)
            }
        }
        for (remote in remoteEvents) {
            when (val local = localEventById[remote.event_id]) {
                null -> {
                    // Don't double-log a watch already covered locally: the Mac synthesises an
                    // event for a watch that arrives without history, and that copy carries a
                    // different dedup key.
                    if (!hasEquivalentLocalEvent(localEvents, remote)) {
                        incomingEvents.add(
                            WatchEventEntity(
                                id = remote.event_id,
                                cycleId = remote.cycle_id,
                                mediaId = remote.media_id,
                                episodeId = remote.episode_id,
                                watchedAt = remote.watched_at,
                                source = remote.source,
                                timezoneIdentifier = remote.timezone_identifier,
                                runtimeMinutes = remote.runtime_minutes,
                                voidedAt = remote.voided_at,
                                deduplicationKey = remote.deduplication_key,
                                isBackfilled = true,
                                updatedAt = remote.updated_at
                            )
                        )
                    }
                }
                else -> if (local.voidedAt == null && remote.voided_at != null) {
                    incomingEvents.add(local.copy(voidedAt = remote.voided_at, updatedAt = remote.updated_at))
                }
            }
        }

        return WatchHistoryReconcile(outgoingCycles, incomingCycles, outgoingEvents, incomingEvents)
    }

    private fun hasEquivalentLocalEvent(all: List<WatchEventEntity>, remote: RemoteWatchEvent): Boolean {
        val remoteDay = remote.watched_at.take(10)
        return all.any {
            it.mediaId == remote.media_id && it.episodeId == remote.episode_id &&
                it.voidedAt == null && it.watchedAt.take(10) == remoteDay
        }
    }

    private data class WatchHistoryReconcile(
        val outgoingCycles: List<WatchCycleEntity>,
        val incomingCycles: List<WatchCycleEntity>,
        val outgoingEvents: List<WatchEventEntity>,
        val incomingEvents: List<WatchEventEntity>
    )

    private suspend fun fetchRemoteWatchCycles(since: String? = null): List<RemoteWatchCycle> = fetchAllPages("user_watch_cycles", since)

    private suspend fun fetchRemoteWatchEvents(since: String? = null): List<RemoteWatchEvent> = fetchAllPages("user_watch_events", since)

    private suspend fun pushWatchCycles(cycles: List<WatchCycleEntity>) {
        if (cycles.isEmpty()) return
        val now = isoNow()
        val payload = cycles.map { c ->
            RemoteWatchCycle(
                user_id = syncKey,
                cycle_id = c.id,
                media_id = c.mediaId,
                kind = c.kind,
                started_at = c.startedAt,
                completed_at = c.completedAt,
                state = c.state,
                is_rewatch = c.isRewatch,
                is_complete = c.isComplete,
                scope_episode_ids = c.scopeEpisodeIds,
                is_backfilled = c.isBackfilled,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_watch_cycles") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private suspend fun pushWatchEvents(events: List<WatchEventEntity>) {
        if (events.isEmpty()) return
        val now = isoNow()
        val payload = events.map { e ->
            RemoteWatchEvent(
                user_id = syncKey,
                event_id = e.id,
                cycle_id = e.cycleId,
                media_id = e.mediaId,
                episode_id = e.episodeId,
                watched_at = e.watchedAt,
                source = e.source,
                timezone_identifier = e.timezoneIdentifier,
                runtime_minutes = e.runtimeMinutes,
                voided_at = e.voidedAt,
                deduplication_key = e.deduplicationKey,
                is_backfilled = e.isBackfilled,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_watch_events") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun cycleChangeTime(startedAt: String?, completedAt: String?): Date? =
        parseInstant(completedAt) ?: parseInstant(startedAt)

    /** Collections + membership diff. Newest-wins per row; membership uses tombstones. */
    private suspend fun reconcileCollections(since: String? = null): CollectionReconcile {
        val remoteCollections = fetchRemoteCollections(since)
        val remoteItems = fetchRemoteCollectionItems(since)
        val localCollections = db.collectionDao().getAllCollections()
        val localItems = db.collectionDao().getAllCollectionItems()

        val remoteById = remoteCollections.associateBy { it.collection_id }
        val localById = localCollections.associateBy { it.id }

        val outgoingCollections = mutableListOf<CollectionEntity>()
        val incomingCollections = mutableListOf<CollectionEntity>()

        for (local in localCollections) {
            val remote = remoteById[local.id]
            if (remote == null) {
                // Same watermark subtlety as the media loop: absent means "unchanged", so only
                // push a collection this device actually edited since the last sync.
                if (changedSince(local.updatedAt, since)) outgoingCollections.add(local)
                continue
            }
            val localTime = parseInstant(local.updatedAt)
            val remoteTime = parseInstant(remote.updated_at)
            val remoteWins = remoteTime != null && (localTime == null || remoteTime > localTime)
            val differs = local.isDeleted != remote.is_deleted ||
                local.name != remote.name ||
                local.systemImage != remote.system_image ||
                (local.notes ?: "") != (remote.notes ?: "") ||
                local.isPinned != remote.is_pinned ||
                local.completedItemIds != remote.completed_item_ids ||
                (local.smartRules ?: "") != (remote.smart_rules ?: "")
            if (differs) {
                if (remoteWins) incomingCollections.add(fromRemoteCollection(remote, local))
                else outgoingCollections.add(local)
            }
        }
        for (remote in remoteCollections) {
            if (!localById.containsKey(remote.collection_id) && !remote.is_deleted) {
                incomingCollections.add(fromRemoteCollection(remote, null))
            }
        }

        // Membership: for each (collection, media) either side knows about, newest wins.
        //
        // "Live" means live *here or in the cloud*: the collection rows are only written to
        // Room after this reconcile returns, so on the first sync none of the imported
        // collections are local yet. Requiring a local collection meant their membership was
        // never pulled — the collections arrived with no titles.
        val liveCollectionIDs = (
            localById.values.filter { !it.isDeleted }.map { it.id } +
                remoteCollections.filter { !it.is_deleted }.map { it.collection_id }
            ).toSet()
        val localByKey = localItems.associateBy { "${it.collectionId}|${it.mediaId}" }
        val remoteByKey = remoteItems.associateBy { "${it.collection_id}|${it.media_id}" }
        val outgoingItems = mutableListOf<CollectionItemEntity>()
        val incomingItems = mutableListOf<CollectionItemEntity>()

        for (key in localByKey.keys + remoteByKey.keys) {
            val local = localByKey[key]
            val remote = remoteByKey[key]
            when {
                local == null && remote != null -> {
                    // Pull a new member into any collection that is live on either side, and
                    // never pull a tombstone for a membership we never had.
                    if (!remote.is_deleted && liveCollectionIDs.contains(remote.collection_id)) {
                        incomingItems.add(
                            CollectionItemEntity(remote.collection_id, remote.media_id, false, remote.updated_at)
                        )
                    }
                }
                local != null && remote == null -> {
                    // Absent from a watermarked fetch = unchanged, not "missing from the cloud".
                    if (liveCollectionIDs.contains(local.collectionId) && changedSince(local.updatedAt, since)) {
                        outgoingItems.add(local)
                    }
                }
                local != null && remote != null && local.isDeleted != remote.is_deleted -> {
                    val localTime = parseInstant(local.updatedAt)
                    val remoteTime = parseInstant(remote.updated_at)
                    if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                        incomingItems.add(local.copy(isDeleted = remote.is_deleted, updatedAt = remote.updated_at))
                    } else {
                        outgoingItems.add(local)
                    }
                }
            }
        }

        return CollectionReconcile(outgoingCollections, incomingCollections, outgoingItems, incomingItems)
    }

    private data class CollectionReconcile(
        val outgoingCollections: List<CollectionEntity>,
        val incomingCollections: List<CollectionEntity>,
        val outgoingItems: List<CollectionItemEntity>,
        val incomingItems: List<CollectionItemEntity>
    )

    private fun canonicalEpisodeId(id: String): String =
        MediaIds.parseEpisodeUniqueId(id)?.let {
            MediaIds.buildEpisodeUniqueId(it.showId, it.seasonNumber, it.episodeNumber)
        } ?: id

    private suspend fun fetchRemoteMedia(since: String? = null): List<RemoteMedia> =
        fetchAllPages("user_media", since)

    private suspend fun fetchRemoteEpisodes(since: String? = null): List<RemoteEpisode> = fetchAllPages("user_episodes", since)

    private suspend fun fetchRemoteCollections(since: String? = null): List<RemoteCollection> = fetchAllPages("user_collections", since)

    private suspend fun fetchRemoteCollectionItems(since: String? = null): List<RemoteCollectionItem> =
        fetchAllPages("user_collection_items", since)

    /** GETs every page of a PostgREST table — it caps an unpaginated response at 1000 rows. */
    /**
     * GETs every page of a PostgREST table — it caps an unpaginated response at 1000 rows.
     *
     * [since] is the incremental watermark: every table carries a server-set `updated_at`, so
     * an unchanged table returns zero rows instead of the whole library. This matters more
     * than it looks — a full pull is ~8 MB, and the background sync runs every 30 minutes
     * (~11 GB/month against Supabase's 5 GB free egress quota).
     */
    private suspend inline fun <reified T> fetchAllPages(endpoint: String, since: String? = null): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val response: HttpResponse = client.get("$supabaseUrl/rest/v1/$endpoint") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
                if (!since.isNullOrBlank()) parameter("updated_at", "gt.$since")
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                header("x-sync-key", syncKey)
                header("Range", "$offset-${offset + pageSize - 1}")
            }
            val page: List<T> = response.body()
            if (page.isEmpty()) break
            out.addAll(page)
            offset += page.size
            if (page.size < pageSize) break
        }
        return out
    }

    /** Upserts collection rows in a single POST — used by the Collections UI. */
    suspend fun pushCollections(collections: List<CollectionEntity>) {
        if (collections.isEmpty()) return
        val now = isoNow()
        val payload = collections.map { c ->
            RemoteCollection(
                user_id = syncKey,
                collection_id = c.id,
                name = c.name,
                system_image = c.systemImage,
                notes = c.notes,
                is_pinned = c.isPinned,
                is_smart = c.isSmart,
                smart_rules = c.smartRules,
                completed_item_ids = c.completedItemIds,
                is_deleted = c.isDeleted,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_collections") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /** Upserts collection membership rows in a single POST — used by the Library. */
    suspend fun pushCollectionItems(items: List<CollectionItemEntity>) {
        if (items.isEmpty()) return
        val now = isoNow()
        val payload = items.map { i ->
            RemoteCollectionItem(
                user_id = syncKey,
                collection_id = i.collectionId,
                media_id = i.mediaId,
                is_deleted = i.isDeleted,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_collection_items") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun fromRemoteCollection(remote: RemoteCollection, existing: CollectionEntity?): CollectionEntity =
        CollectionEntity(
            id = remote.collection_id,
            name = remote.name,
            systemImage = remote.system_image,
            notes = remote.notes,
            isPinned = remote.is_pinned,
            isSmart = remote.is_smart,
            smartRules = remote.smart_rules,
            completedItemIds = remote.completed_item_ids,
            isDeleted = remote.is_deleted,
            updatedAt = remote.updated_at
        )

    /** Upserts many media rows in a single POST — used by Library multi-select. */
    suspend fun pushMediaBatch(items: List<MediaItemEntity>) {
        if (items.isEmpty()) return
        val now = isoNow()
        val payload = items.map { item ->
            RemoteMedia(
                user_id = syncKey,
                media_id = item.id,
                type = item.type,
                state = item.state,
                taste = item.taste,
                custom_poster_url = item.customPosterUrl,
                // Must be sent: without it a soft-delete would be pushed as a live row and
                // the delete would never propagate.
                is_soft_deleted = item.isSoftDeleted,
                last_interaction_date = item.lastInteractionDate ?: now,
                last_state_change_date = item.lastStateChangeDate ?: now,
                date_added = item.dateAdded,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_media") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /**
     * Tombstones titles in the cloud so a delete propagates to the other device.
     * (A plain state push omits `is_soft_deleted`, since `false` is the serialization
     * default, so deletes need to be sent explicitly.)
     */
    suspend fun pushMediaDeletes(items: List<MediaItemEntity>) {
        if (items.isEmpty()) return
        val now = isoNow()
        val payload = items.map { item ->
            RemoteMedia(
                user_id = syncKey,
                media_id = item.id,
                type = item.type,
                state = item.state,
                taste = item.taste,
                custom_poster_url = item.customPosterUrl,
                is_soft_deleted = true,
                last_interaction_date = now,
                last_state_change_date = now,
                date_added = item.dateAdded,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_media") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private suspend fun pushEpisodeBatch(episodes: List<EpisodeEntity>) {
        if (episodes.isEmpty()) return
        val now = isoNow()
        val payload = episodes.map { ep ->
            val canonicalShowId = MediaIds.tvShowKey(ep.showId)
            RemoteEpisode(
                user_id = syncKey,
                episode_id = MediaIds.buildCloudEpisodeId(canonicalShowId, ep.seasonNumber, ep.episodeNumber),
                show_id = canonicalShowId,
                season_number = ep.seasonNumber,
                episode_number = ep.episodeNumber,
                is_watched = ep.isWatched,
                watched_date = ep.watchedDate ?: if (ep.isWatched) now else null,
                updated_at = now
            )
        }
        client.post("$supabaseUrl/rest/v1/user_episodes") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /** Local entity from a remote row, preserving the locally-derived fields. */
    private fun fromRemote(remote: RemoteMedia, existing: MediaItemEntity?): MediaItemEntity =
        MediaItemEntity(
            id = remote.media_id,
            title = existing?.title?.takeIf { it != remote.media_id } ?: remote.media_id,
            overview = existing?.overview ?: "",
            posterUrl = existing?.posterUrl,
            backdropUrl = existing?.backdropUrl,
            releaseDate = existing?.releaseDate,
            type = remote.type,
            state = remote.state,
            taste = remote.taste,
            nextAiringInstant = existing?.nextAiringInstant,
            timezone = existing?.timezone,
            network = existing?.network,
            genres = existing?.genres,
            language = existing?.language,
            studios = existing?.studios,
            providers = existing?.providers,
            customPosterUrl = remote.custom_poster_url,
            isSoftDeleted = remote.is_soft_deleted,
            lastInteractionDate = remote.last_interaction_date,
            lastStateChangeDate = remote.last_state_change_date,
            // Keep what we already know; take the cloud's (the Mac's dateAdded) when we don't —
            // that is how rows that predate the column get a real "added" time.
            dateAdded = existing?.dateAdded ?: remote.date_added,
            updatedAt = remote.updated_at
        )

    private fun parseInstant(value: String?): Date? =
        value?.let { runCatching { Date.from(java.time.Instant.parse(it)) }.getOrNull() }
}
