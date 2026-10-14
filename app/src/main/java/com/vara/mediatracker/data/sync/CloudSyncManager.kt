package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.db.MediaDao
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
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
    suspend fun pushMediaUpdate(mediaId: String, type: String, state: String, taste: String) = runCatching {
        val now = isoNow()
        val payload = RemoteMedia(
            user_id = syncKey,
            media_id = mediaId,
            type = type,
            state = state,
            taste = taste,
            last_interaction_date = now,
            last_state_change_date = now,
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
    data class SyncSummary(val outgoing: Int, val incoming: Int)

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
        val incomingCollectionItems: List<CollectionItemEntity> = emptyList()
    )

    /** Computes what a sync would change, without writing anything (macOS SyncImpact). */
    suspend fun computeImpact(): Result<SyncImpact> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }
        val r = reconcile()
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
    suspend fun sync(): Result<SyncSummary> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }
        val r = reconcile()
        if (r.incomingMedia.isNotEmpty()) mediaDao.upsertMedia(r.incomingMedia)
        if (r.incomingEpisodes.isNotEmpty()) mediaDao.upsertEpisodes(r.incomingEpisodes)
        if (r.incomingCollections.isNotEmpty()) db.collectionDao().upsertCollections(r.incomingCollections)
        if (r.incomingCollectionItems.isNotEmpty()) db.collectionDao().upsertCollectionItems(r.incomingCollectionItems)
        pushMediaBatch(r.outgoingMedia)
        pushEpisodeBatch(r.outgoingEpisodes)
        pushCollections(r.outgoingCollections)
        pushCollectionItems(r.outgoingCollectionItems)
        SyncSummary(
            outgoing = r.outgoingMedia.size + r.outgoingEpisodes.size +
                r.outgoingCollections.size + r.outgoingCollectionItems.size,
            incoming = r.incomingMedia.size + r.incomingEpisodes.size +
                r.incomingCollections.size + r.incomingCollectionItems.size
        )
    }

    /** Fetches the cloud and diffs it against the local library. Pure read. */
    private suspend fun reconcile(): Reconcile {
        val remoteMedia = fetchRemoteMedia()
        val remoteEpisodes = fetchRemoteEpisodes()
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
                outgoingMedia.add(local)
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
                val localTime = parseInstant(local.lastStateChangeDate)
                val remoteTime = parseInstant(remote.updated_at)
                if (remoteTime != null && (localTime == null || remoteTime > localTime)) {
                    incomingMedia.add(fromRemote(remote, local))
                } else {
                    outgoingMedia.add(local)
                }
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
                if (local.isWatched) outgoingEpisodes.add(local)
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

        val collections = reconcileCollections()
        return Reconcile(
            outgoingMedia, incomingMedia, outgoingEpisodes, incomingEpisodes, conflictTitles,
            outgoingCollections = collections.outgoingCollections,
            incomingCollections = collections.incomingCollections,
            outgoingCollectionItems = collections.outgoingItems,
            incomingCollectionItems = collections.incomingItems
        )
    }

    /** Collections + membership diff. Newest-wins per row; membership uses tombstones. */
    private suspend fun reconcileCollections(): CollectionReconcile {
        val remoteCollections = fetchRemoteCollections()
        val remoteItems = fetchRemoteCollectionItems()
        val localCollections = db.collectionDao().getAllCollections()
        val localItems = db.collectionDao().getAllCollectionItems()

        val remoteById = remoteCollections.associateBy { it.collection_id }
        val localById = localCollections.associateBy { it.id }

        val outgoingCollections = mutableListOf<CollectionEntity>()
        val incomingCollections = mutableListOf<CollectionEntity>()

        for (local in localCollections) {
            val remote = remoteById[local.id]
            if (remote == null) {
                outgoingCollections.add(local)
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
        val liveLocalCollections = localById.values.filter { !it.isDeleted }.map { it.id }.toSet()
        val localByKey = localItems.associateBy { "${it.collectionId}|${it.mediaId}" }
        val remoteByKey = remoteItems.associateBy { "${it.collection_id}|${it.media_id}" }
        val outgoingItems = mutableListOf<CollectionItemEntity>()
        val incomingItems = mutableListOf<CollectionItemEntity>()

        for (key in localByKey.keys + remoteByKey.keys) {
            val local = localByKey[key]
            val remote = remoteByKey[key]
            when {
                local == null && remote != null -> {
                    // Pull a new member only into a collection we actually hold, and never
                    // pull a tombstone for a membership we never had.
                    if (!remote.is_deleted && liveLocalCollections.contains(remote.collection_id)) {
                        incomingItems.add(
                            CollectionItemEntity(remote.collection_id, remote.media_id, false, remote.updated_at)
                        )
                    }
                }
                local != null && remote == null -> {
                    if (liveLocalCollections.contains(local.collectionId)) outgoingItems.add(local)
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

    private suspend fun fetchRemoteMedia(): List<RemoteMedia> {
        // Paginated like fetchRemoteEpisodes: PostgREST caps an unpaginated response at
        // 1000 rows, so a library past that would silently truncate on pull.
        val out = mutableListOf<RemoteMedia>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val response: HttpResponse = client.get("$supabaseUrl/rest/v1/user_media") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                header("x-sync-key", syncKey)
                header("Range", "$offset-${offset + pageSize - 1}")
            }
            val page: List<RemoteMedia> = response.body()
            if (page.isEmpty()) break
            out.addAll(page)
            offset += page.size
            if (page.size < pageSize) break
        }
        return out
    }

    private suspend fun fetchRemoteEpisodes(): List<RemoteEpisode> = fetchAllPages("user_episodes")

    private suspend fun fetchRemoteCollections(): List<RemoteCollection> = fetchAllPages("user_collections")

    private suspend fun fetchRemoteCollectionItems(): List<RemoteCollectionItem> =
        fetchAllPages("user_collection_items")

    /** GETs every page of a PostgREST table — it caps an unpaginated response at 1000 rows. */
    private suspend inline fun <reified T> fetchAllPages(endpoint: String): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val response: HttpResponse = client.get("$supabaseUrl/rest/v1/$endpoint") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
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
            updatedAt = remote.updated_at
        )

    private fun parseInstant(value: String?): Date? =
        value?.let { runCatching { Date.from(java.time.Instant.parse(it)) }.getOrNull() }
}
