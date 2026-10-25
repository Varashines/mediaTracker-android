package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.db.MediaDao
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.data.model.Taste
import com.vara.mediatracker.util.WireTime
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import com.vara.mediatracker.data.api.TMDBClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /**
     * The Mac's extracted poster palette.
     *
     * Read-only on this device: the Mac authors them, so they ride along on a pull and are not
     * part of the push payload at all. Being absent is what keeps a push from clearing them — the
     * server only writes the columns it is sent.
     */
    val theme_color_hex: String? = null,
    val theme_muted_color_hex: String? = null,
    val theme_secondary_color_hex: String? = null,
    val updated_at: String? = null
)

/**
 * A media row as it goes *up*, which is not the same shape as a row coming down.
 *
 * The upload type has no palette fields at all, rather than palette fields that are null. kotlinx
 * omits a property whose value is its default, so a batch of `RemoteMedia` objects disagreed about
 * their key set — one title with a taste, one without, one carrying a custom poster — and PostgREST
 * rejects that in full with PGRST102, the same 400 the Mac was failing on.
 *
 * Setting `encodeDefaults` fixes the keys, and sending `theme_color_hex = null` to fix them would be
 * worse: a null is an instruction to clear the column, so every Android sync would wipe the palette
 * the Mac extracted. A type that does not have the columns cannot say anything about them, which is
 * what this device actually means.
 */
@Serializable
data class RemoteMediaPush(
    val user_id: String,
    val media_id: String,
    val type: String,
    val state: String,
    val taste: String,
    val is_soft_deleted: Boolean,
    val last_interaction_date: String?,
    val last_state_change_date: String?,
    val date_added: String?,
    val updated_at: String?
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
    /** One sync at a time: the worker, Settings and app start can all ask at once. */
    private val syncMutex = Mutex()

    /** media_id → when a batch last pushed it, so a single-row push can yield to the batch. */
    private val lastBatchPush = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
                    // Emit every property, including ones sitting at their default. Without this a
                    // batch of rows disagrees about its keys as soon as one title has a taste and
                    // another has none, and PostgREST rejects the request with PGRST102.
                    encodeDefaults = true
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

    private fun isoNow(): String = WireTime.now()

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
                taste = Taste.normalize(rem.taste),
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
                // "" is how the Mac says "reset to the network artwork"; this device stores
                // "no poster" as null, so the two are folded together before they reach the UI.
                customPosterUrl = rem.custom_poster_url?.takeIf { it.isNotBlank() },
                isSoftDeleted = rem.is_soft_deleted,
                lastInteractionDate = rem.last_interaction_date,
                lastStateChangeDate = rem.last_state_change_date,
                // Without this the rebuilt row carried a null, `upsertMedia` wrote it over the
                // local value, and "Recently added" reset itself on every pull — then the next
                // push sent the null back and cleared the cloud's copy of the same date.
                dateAdded = existing?.dateAdded ?: rem.date_added,
                updatedAt = rem.updated_at,
                // The Mac's palette, per field: a null remote never clears a colour we already hold.
                themeColorHex = rem.theme_color_hex ?: existing?.themeColorHex,
                themeMutedColorHex = rem.theme_muted_color_hex ?: existing?.themeMutedColorHex,
                themeSecondaryColorHex = rem.theme_secondary_color_hex ?: existing?.themeSecondaryColorHex
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
        // A sync that pushed this row within the last couple of seconds has already said this.
        val stamp = lastBatchPush[mediaId]
        if (stamp != null && System.currentTimeMillis() - stamp < 2_000L) return@runCatching
        val now = isoNow()
        // The poster and the palette are the Mac's to author. Neither is in the push payload, so
        // this device cannot clear them: a nullable property at its default would otherwise be
        // serialized as an explicit null, which PostgREST writes as "clear this column" —
        // exactly how a phone that had never received the poster erased it.
        val existing = mediaDao.getMediaById(mediaId)
        val payload = RemoteMediaPush(
            user_id = syncKey,
            media_id = mediaId,
            type = type,
            state = state,
            taste = Taste.normalize(taste),
            is_soft_deleted = existing?.isSoftDeleted ?: false,
            // The row's own edit times, not `now`.
            //
            // Stamping the push time here rewrote the cloud's `last_interaction_date` on every
            // push. The pull then adopted it locally, which made the title look edited since the
            // last sync, so the next sync pushed it again — one title per sync, cycling through
            // whichever was written most recently. `updated_at` is the write time and is the only
            // thing a push should advance.
            last_interaction_date = existing?.lastInteractionDate ?: now,
            last_state_change_date = if (isStateChange) {
                existing?.lastStateChangeDate ?: now
            } else {
                existing?.lastStateChangeDate
            },
            date_added = dateAdded ?: existing?.dateAdded,
            updated_at = now
        )

        client.post("$supabaseUrl/rest/v1/user_media") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
        val outgoingCollectionItems: Int = 0,
        val incomingCycles: Int = 0,
        val outgoingCycles: Int = 0,
        val incomingEvents: Int = 0,
        val outgoingEvents: Int = 0
    ) {
        val totalIncoming: Int
            get() = incomingTitles.size + incomingEpisodes + incomingCollections.size + incomingCollectionItems +
                incomingCycles + incomingEvents
        val totalOutgoing: Int
            get() = outgoingTitles.size + outgoingEpisodes + outgoingCollections.size + outgoingCollectionItems +
                outgoingCycles + outgoingEvents
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
    suspend fun computeImpact(since: String? = null): Result<SyncImpact> =
        syncMutex.withLock { computeImpactLocked(since) }

    private suspend fun computeImpactLocked(since: String?): Result<SyncImpact> = runCatching {
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
            outgoingCollectionItems = r.outgoingCollectionItems.size,
            incomingCycles = r.incomingCycles.size,
            outgoingCycles = r.outgoingCycles.size,
            incomingEvents = r.incomingWatchEvents.size,
            outgoingEvents = r.outgoingWatchEvents.size
        )
    }

    /**
     * Full two-way sync: push local wins and apply cloud wins. Conflicts are
     * last-write-wins by timestamp. Prefer [computeImpact] first to preview it.
     *
     * @return how many rows were sent up and applied down.
     */
    suspend fun sync(since: String? = null): Result<SyncSummary> =
        syncMutex.withLock { syncLocked(since) }

    private suspend fun syncLocked(since: String?): Result<SyncSummary> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }
        val r = reconcile(since)

        // One transaction, and a guard against the pull undoing what happened during it.
        //
        // The payload is decided from a snapshot read before a network round-trip that can take
        // minutes, and each upsert replace the whole row — so a tap during the pull was reverted
        // by the page it raced with. Comparing the incoming row against the row on disk *now*
        // and skipping anything edited since keeps the newer of the two.
        db.withTransaction {
            val localStamps = mediaDao.getMediaByIds(r.incomingMedia.map { it.id })
                .associate { it.id to (it.lastInteractionDate ?: "") }
            val applicable = r.incomingMedia.filter { incoming ->
                val local = localStamps[incoming.id]
                local == null || local <= (incoming.lastInteractionDate ?: "")
            }
            if (applicable.isNotEmpty()) mediaDao.upsertMedia(applicable)
            if (r.incomingEpisodes.isNotEmpty()) mediaDao.upsertEpisodes(r.incomingEpisodes)
            if (r.incomingCollections.isNotEmpty()) db.collectionDao().upsertCollections(r.incomingCollections)
            if (r.incomingCollectionItems.isNotEmpty()) db.collectionDao().upsertCollectionItems(r.incomingCollectionItems)
            if (r.incomingCycles.isNotEmpty()) db.watchHistoryDao().upsertCycles(r.incomingCycles)
        }
        // A rewatch resets the projection up to the moment it started, exactly as it does on the
        // Mac. Comparing against the cycle's start rather than "a cycle I have not seen" makes it
        // idempotent and also repairs a device that already pulled the cycle before this rule
        // existed — which is the state the phone is in right now.
        // Only a cycle that has *just* started may reset anything. A fresh install pulls every
        // cycle ever written, and an old active rewatch would otherwise clear a show that is simply
        // sitting in this device's library — which is how every series came back unmarked.
        val resetCutoff = java.util.Date(System.currentTimeMillis() - 48L * 3600L * 1000L)
        r.incomingCycles
            .filter { it.isRewatch && it.state == "active" }
            .filter { cycle ->
                val started = com.vara.mediatracker.util.WireTime.parseInstant(cycle.startedAt)
                started != null && started.after(resetCutoff)
            }
            .forEach { cycle -> mediaDao.clearEpisodeProjectionBefore(cycle.mediaId, cycle.startedAt) }
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

        val media = SyncReconciler.reconcileMedia(mediaDao.getAllMediaList(), remoteMedia, since)
        val episodes = SyncReconciler.reconcileEpisodes(mediaDao.getAllEpisodesList(), remoteEpisodes, since)

        // Every table contributes to the watermark. It used to be media and episodes only, so the
        // other three could advance without moving it — the next pull then re-read them, or worse,
        // skipped a row whose only change landed in a table the watermark was not looking at.
        val stamps = mutableListOf<String?>()
        stamps += remoteMedia.map { it.updated_at }
        stamps += remoteEpisodes.map { it.updated_at }
        val collections = reconcileCollections(since, stamps)
        val history = reconcileWatchHistory(since, stamps)
        return Reconcile(
            media.outgoing, media.incoming, episodes.outgoing, episodes.incoming, media.conflicts,
            outgoingCollections = collections.outgoingCollections,
            incomingCollections = collections.incomingCollections,
            outgoingCollectionItems = collections.outgoingItems,
            incomingCollectionItems = collections.incomingItems,
            outgoingCycles = history.outgoingCycles,
            incomingCycles = history.incomingCycles,
            outgoingWatchEvents = history.outgoingEvents,
            incomingWatchEvents = history.incomingEvents,
            watermark = nextWatermark(since, stamps)
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
    private fun changedSince(stamp: String?, watermark: String?): Boolean =
        SyncReconciler.changedSince(stamp, watermark)

    /**
     * The watermark to store after this pull: the newest server `updated_at` observed across all
     * five tables, exactly as the server wrote it.
     *
     * No back-off. Subtracting five seconds was meant to cover a read racing an in-flight commit,
     * and it does not: a row committed after our GET carries a *later* stamp and is pulled next
     * time either way. What the back-off did do was make every row written in those five seconds
     * look edited on the next sync, which is how one title per sync came to be re-pushed forever.
     */
    private fun nextWatermark(previous: String?, stamps: List<String?>): String? {
        return stamps.filterNotNull().filter { it.isNotBlank() }.maxOrNull() ?: previous
    }

    private suspend fun reconcileWatchHistory(since: String? = null, stamps: MutableList<String?> = mutableListOf()): WatchHistoryReconcile {
        val remoteCycles = fetchRemoteWatchCycles(since)
        val remoteEvents = fetchRemoteWatchEvents(since)
        stamps += remoteCycles.map { it.updated_at }
        stamps += remoteEvents.map { it.updated_at }
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
            } else if (localTime != null && (remoteTime == null || localTime > remoteTime)) {
                // The other half of the rule, which was missing: a cycle *completed* here is the
                // newer one, so it has to go up. Without this a show finished on the phone stayed
                // open on the Mac forever, because only cycle *creation* was ever pushed.
                outgoingCycles.add(local)
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

        // Events are decided by the reconciler (shared with the JVM scenario tests).
        val events = SyncReconciler.reconcileEvents(localEvents, remoteEvents, since)

        return WatchHistoryReconcile(outgoingCycles, incomingCycles, events.outgoing, events.incoming)
    }

    /**
     * True when the ledger already holds this viewing.
     *
     * Episode ids are compared *canonically* ([MediaIds.canonicalEpisodeIdOrNull]): the legacy
     * `{show}_{s}_{e}` and the `tv_\u2026` spellings used to be compared literally, so the same
     * watch — recorded here and pulled back from the Mac — counted as two. Stamps are compared as
     * instants rather than by their date prefix, because a stamp written from local wall-clock
     * time can cross midnight, which is how one viewing came to appear on two days in Year in
     * Review.
     */
    private data class WatchHistoryReconcile(
        val outgoingCycles: List<WatchCycleEntity>,
        val incomingCycles: List<WatchCycleEntity>,
        val outgoingEvents: List<WatchEventEntity>,
        val incomingEvents: List<WatchEventEntity>
    )

    private suspend fun fetchRemoteWatchCycles(since: String? = null): List<RemoteWatchCycle> = fetchAllPages("user_watch_cycles", since, { it.cycle_id }, { it.user_id }, keyColumn = "cycle_id")

    private suspend fun fetchRemoteWatchEvents(since: String? = null): List<RemoteWatchEvent> = fetchAllPages("user_watch_events", since, { it.event_id }, { it.user_id }, keyColumn = "event_id")

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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun cycleChangeTime(startedAt: String?, completedAt: String?): Date? =
        parseInstant(completedAt) ?: parseInstant(startedAt)

    /** Collections + membership diff. Newest-wins per row; membership uses tombstones. */
    private suspend fun reconcileCollections(since: String? = null, stamps: MutableList<String?> = mutableListOf()): CollectionReconcile {
        val remoteCollections = fetchRemoteCollections(since)
        val remoteItems = fetchRemoteCollectionItems(since)
        stamps += remoteCollections.map { it.updated_at }
        stamps += remoteItems.map { it.updated_at }
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

    private fun canonicalEpisodeId(id: String): String = MediaIds.canonicalEpisodeIdOrNull(id) ?: id

    /**
     * The one-time colour pull.
     *
     * The bulk backfill that put the palette in the cloud preserved each row's `updated_at` so it
     * would not look like an edit and force a re-pull on every device. That was the right call for
     * that job, but it has a consequence: the ordinary watermarked pull — `updated_at > watermark`
     * — can never see these colours, because their stamps are older than any watermark.
     *
     * So this asks for them once, without a watermark, and writes only the three columns. Not a full
     * reconcile: state and taste have not changed, and dragging every row through the conflict rules
     * to learn a derived colour would be a great deal of work for no change.
     *
     * Returns the number of rows written, or null when the fetch failed — so a caller can tell
     * "nothing to do" from "did not reach the cloud" and only mark the job done in the first case.
     * Idempotent: a second run writes nothing.
     */
    suspend fun pullThemeColors(): Int? = runCatching {
        val remote = fetchRemoteMedia(since = null)
        val byId = mediaDao.getAllMediaList().associateBy { it.id }
        val updated = remote.mapNotNull { row ->
            val primary = row.theme_color_hex ?: return@mapNotNull null
            val existing = byId[row.media_id] ?: return@mapNotNull null
            val unchanged = existing.themeColorHex == primary &&
                existing.themeMutedColorHex == row.theme_muted_color_hex &&
                existing.themeSecondaryColorHex == row.theme_secondary_color_hex
            if (unchanged) return@mapNotNull null
            existing.copy(
                themeColorHex = primary,
                themeMutedColorHex = row.theme_muted_color_hex,
                themeSecondaryColorHex = row.theme_secondary_color_hex
            )
        }
        if (updated.isNotEmpty()) mediaDao.upsertMedia(updated)
        updated.size
    }.getOrNull()

    private suspend fun fetchRemoteMedia(since: String? = null): List<RemoteMedia> =
        fetchAllPages("user_media", since, { it.media_id }, { it.user_id }, keyColumn = "media_id")

    private suspend fun fetchRemoteEpisodes(since: String? = null): List<RemoteEpisode> = fetchAllPages("user_episodes", since, { it.episode_id }, { it.user_id }, keyColumn = "episode_id")

    private suspend fun fetchRemoteCollections(since: String? = null): List<RemoteCollection> = fetchAllPages("user_collections", since, { it.collection_id }, { it.user_id }, keyColumn = "collection_id")

    private suspend fun fetchRemoteCollectionItems(since: String? = null): List<RemoteCollectionItem> =
        fetchAllPages(
            "user_collection_items", since, { "${it.collection_id}|${it.media_id}" }, { it.user_id },
            keyColumn = "collection_id,media_id"
        )

    /** GETs every page of a PostgREST table — it caps an unpaginated response at 1000 rows. */
    /**
     * GETs every page of a PostgREST table — it caps an unpaginated response at 1000 rows.
     *
     * [since] is the incremental watermark: every table carries a server-set `updated_at`, so
     * an unchanged table returns zero rows instead of the whole library. This matters more
     * than it looks — a full pull is ~8 MB, and the background sync runs every 30 minutes
     * (~11 GB/month against Supabase's 5 GB free egress quota).
     */
    /**
     * Row-by-row decoding for pages that arrive as text, matching the client's own JSON
     * configuration so a row that decodes here decodes the same way through ContentNegotiation.
     */
    private val wireJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private suspend inline fun <reified T> fetchAllPages(
        endpoint: String,
        since: String? = null,
        keyOf: (T) -> String,
        /** The tenant column, checked before a row is applied. */
        userIdOf: (T) -> String,
        /**
         * The table's primary-key column(s), used to make the page order total.
         * `collection_id,media_id` for the two-column join table.
         */
        keyColumn: String
    ): List<T> {
        val out = mutableListOf<T>()
        val seen = HashSet<String>()
        val pageSize = 1000
        var offset = 0

        // Deterministic offset paging.
        //
        // This used to be a keyset cursor on `updated_at` alone: `gte` the newest stamp seen, and
        // stop when that stamp stopped moving. A batch push gives more than a page of rows the
        // same stamp, so the cursor could never get past that block — the next request returned
        // the same page, `fresh` was zero, and every row at that stamp or newer stayed invisible
        // for good. The watermark then equalled that stamp, so later pulls started inside it.
        //
        // Ordering by (updated_at, primary key) makes the order total, which is what offset paging
        // needs to be safe. `seen` still guards the case where a row moves between pages.
        while (true) {
            val response: HttpResponse = client.get("$supabaseUrl/rest/v1/$endpoint") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
                parameter("order", "updated_at.asc,$keyColumn.asc")
                // Inclusive on purpose: a row written in the same instant as the watermark must
                // not be skipped. `seen` de-duplicates the overlap.
                if (!since.isNullOrBlank()) parameter("updated_at", "gte.$since")
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                header("x-sync-key", syncKey)
                header("Range", "$offset-${offset + pageSize - 1}")
            }

            // Decode row by row. `response.body<List<T>>()` threw on the first malformed row, which
            // failed the whole sync and left the watermark where it was — so the same poison page
            // came back on every run and nothing newer ever arrived. One unreadable row is not a
            // reason to lose the rest of the table.
            val text = response.bodyAsText()
            if (text.isBlank()) break
            val elements = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: break
            if (elements.isEmpty()) break

            var undecodable = 0
            var foreignTenants = 0
            for (element in elements) {
                val row = try {
                    wireJson.decodeFromJsonElement<T>(element)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    undecodable++
                    continue
                }
                // Second line of defence behind RLS. The Mac has had this since it stopped
                // putting the tenant in the URL; a policy mistake should mean "rows discarded",
                // never "another tenant's library imported".
                if (userIdOf(row) != syncKey) {
                    foreignTenants++
                    continue
                }
                if (seen.add(keyOf(row))) out += row
            }
            if (foreignTenants > 0) {
                Log.w("CloudSync", "$endpoint: discarded $foreignTenants row(s) belonging to another tenant")
            }
            if (undecodable > 0) {
                // Visible and countable; the next pull meets the same rows again rather than
                // silently skipping them for good.
                Log.w(
                    "CloudSync",
                    "$endpoint: skipped $undecodable of ${elements.size} undecodable row(s) at offset $offset"
                )
            }

            // Advance by what the server returned, not what decoded, so an unreadable page cannot
            // stall pagination.
            offset += elements.size
            if (elements.size < pageSize) break
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
            RemoteMediaPush(
                user_id = syncKey,
                media_id = item.id,
                type = item.type,
                state = item.state,
                taste = Taste.normalize(item.taste),
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val stampedAt = System.currentTimeMillis()
        items.forEach { lastBatchPush[it.id] = stampedAt }
    }

    /**
     * Tombstones titles in the cloud so a delete propagates to the other device.
     * (A plain state push now carries `is_soft_deleted` like every other field, so this is only
     * about the rows the caller chose to tombstone.)
     */
    suspend fun pushMediaDeletes(items: List<MediaItemEntity>) {
        if (items.isEmpty()) return
        val now = isoNow()
        val payload = items.map { item ->
            RemoteMediaPush(
                user_id = syncKey,
                media_id = item.id,
                type = item.type,
                state = item.state,
                taste = Taste.normalize(item.taste),
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
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
            header("Prefer", "resolution=merge-duplicates, return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    /** Local entity from a remote row, preserving the locally-derived fields. */
    /**
     * Parses a wire timestamp, bounding anything in the future to now.
     *
     * An edit cannot postdate the write that carried it, but a client clock used to stamp rows
     * hours ahead — which made one device's rows permanently newer, so a later edit on the other
     * device could never win and the bogus value was re-pushed on every sync. The server clamps on
     * write as well; this bounds whatever is already stored locally.
     */
    private fun parseInstant(value: String?): Date? = WireTime.parseInstant(value)
}
