package com.vara.mediatracker.data.sync

import com.vara.mediatracker.data.db.MediaDao
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
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
    private val mediaDao: MediaDao,
    private val supabaseUrl: String,
    private val anonKey: String,
    private val syncKey: String
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
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
        val localMediaEntities = remoteMediaList.map { rem ->
            MediaItemEntity(
                id = rem.media_id,
                title = rem.media_id, // Metadata fetcher will enrich with TMDB title
                type = rem.type,
                state = rem.state,
                taste = rem.taste,
                customPosterUrl = rem.custom_poster_url,
                isSoftDeleted = rem.is_soft_deleted,
                lastInteractionDate = rem.last_interaction_date,
                lastStateChangeDate = rem.last_state_change_date,
                updatedAt = rem.updated_at
            )
        }
        mediaDao.upsertMedia(localMediaEntities)

        // 2. Pull Episodes Progress
        val epResponse: HttpResponse = client.get("$supabaseUrl/rest/v1/user_episodes") {
            parameter("user_id", "eq.$syncKey")
            parameter("select", "*")
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            header("x-sync-key", syncKey)
        }

        val remoteEpList: List<RemoteEpisode> = epResponse.body()
        val localEpisodes = remoteEpList.map { rem ->
            EpisodeEntity(
                uniqueId = rem.episode_id,
                showId = rem.show_id,
                seasonNumber = rem.season_number,
                episodeNumber = rem.episode_number,
                isWatched = rem.is_watched,
                watchedDate = rem.watched_date,
                updatedAt = rem.updated_at
            )
        }
        mediaDao.upsertEpisodes(localEpisodes)

        remoteMediaList.size + remoteEpList.size
    }

    /**
     * Pushes a local episode toggle directly to the cloud.
     */
    suspend fun pushEpisodeWatch(showId: String, season: Int, episode: Int, watched: Boolean) = runCatching {
        val now = isoFormat.format(Date())
        val epId = "tv_${showId}_s${season}_e${episode}"
        val payload = RemoteEpisode(
            user_id = syncKey,
            episode_id = epId,
            show_id = "tv_$showId",
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
}
