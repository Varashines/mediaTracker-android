package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.db.MediaDao
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
import java.text.SimpleDateFormat
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

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Pulls library state from Supabase and applies updates locally.
     */
    suspend fun pullFromCloud(): Result<Int> = runCatching {
        if (supabaseUrl.isBlank() || anonKey.isBlank() || syncKey.isBlank()) {
            return Result.failure(IllegalStateException("Sync credentials not configured"))
        }

        // 1. Pull Media Items
        val mediaResponse: HttpResponse = client.get("$supabaseUrl/rest/v1/user_media") {
            parameter("user_id", "eq.$syncKey")
            parameter("select", "*")
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
        }

        val remoteMediaList: List<RemoteMedia> = mediaResponse.body()
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
        val now = isoFormat.format(Date())
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
        val now = isoFormat.format(Date())
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
        val outgoingEpisodes: Int
    ) {
        val totalIncoming: Int get() = incomingTitles.size + incomingEpisodes
        val totalOutgoing: Int get() = outgoingTitles.size + outgoingEpisodes
    }

    private data class Reconcile(
        val outgoingMedia: List<MediaItemEntity>,
        val incomingMedia: List<MediaItemEntity>,
        val outgoingEpisodes: List<EpisodeEntity>,
        val incomingEpisodes: List<EpisodeEntity>,
        val conflictTitles: List<String>
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
            outgoingEpisodes = r.outgoingEpisodes.size
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
        pushMediaBatch(r.outgoingMedia)
        pushEpisodeBatch(r.outgoingEpisodes)
        SyncSummary(
            outgoing = r.outgoingMedia.size + r.outgoingEpisodes.size,
            incoming = r.incomingMedia.size + r.incomingEpisodes.size
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
            if (!local.state.equals(remote.state, true) || !local.taste.equals(remote.taste, true)) {
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

        return Reconcile(outgoingMedia, incomingMedia, outgoingEpisodes, incomingEpisodes, conflictTitles)
    }

    private fun canonicalEpisodeId(id: String): String =
        MediaIds.parseEpisodeUniqueId(id)?.let {
            MediaIds.buildEpisodeUniqueId(it.showId, it.seasonNumber, it.episodeNumber)
        } ?: id

    private suspend fun fetchRemoteMedia(): List<RemoteMedia> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/user_media") {
            parameter("user_id", "eq.$syncKey")
            parameter("select", "*")
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
        }
        return response.body()
    }

    private suspend fun fetchRemoteEpisodes(): List<RemoteEpisode> {
        val out = mutableListOf<RemoteEpisode>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val response: HttpResponse = client.get("$supabaseUrl/rest/v1/user_episodes") {
                parameter("user_id", "eq.$syncKey")
                parameter("select", "*")
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                header("x-sync-key", syncKey)
                header("Range", "$offset-${offset + pageSize - 1}")
            }
            val page: List<RemoteEpisode> = response.body()
            if (page.isEmpty()) break
            out.addAll(page)
            offset += page.size
            if (page.size < pageSize) break
        }
        return out
    }

    private suspend fun pushMediaBatch(items: List<MediaItemEntity>) {
        if (items.isEmpty()) return
        val now = isoFormat.format(Date())
        val payload = items.map { item ->
            RemoteMedia(
                user_id = syncKey,
                media_id = item.id,
                type = item.type,
                state = item.state,
                taste = item.taste,
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

    private suspend fun pushEpisodeBatch(episodes: List<EpisodeEntity>) {
        if (episodes.isEmpty()) return
        val now = isoFormat.format(Date())
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
