package com.vara.mediatracker.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TMDBSeasonInfo(
    val season_number: Int,
    val name: String? = null,
    val episode_count: Int? = null,
    val air_date: String? = null
)

@Serializable
data class TMDBGenre(
    val id: Int,
    val name: String
)

/** A creator / crew person (used for a TV show's `created_by`). */
@Serializable
data class TMDBPerson(
    val id: Int,
    val name: String,
    val profile_path: String? = null
)

@Serializable
data class TMDBCastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val profile_path: String? = null,
    val order: Int = 0
)

@Serializable
data class TMDBCreditsResponse(
    val id: Int? = null,
    val cast: List<TMDBCastMember> = emptyList(),
    val crew: List<TMDBCrewMember> = emptyList()
)

@Serializable
data class TMDBCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val job: String? = null,
    val department: String? = null
)

@Serializable
data class TMDBProductionCountry(
    val iso_3166_1: String? = null,
    val name: String? = null
)

@Serializable
data class TMDBSearchItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val media_type: String? = null,
    val vote_average: Double? = null
)

@Serializable
data class TMDBSearchResponse(
    val page: Int = 1,
    val results: List<TMDBSearchItem> = emptyList(),
    val total_results: Int = 0
)

@Serializable
data class TMDBNetwork(
    val id: Int? = null,
    val name: String,
    val logo_path: String? = null,
    val origin_country: String? = null
)

@Serializable
data class TMDBProductionCompany(
    val id: Int? = null,
    val name: String,
    val logo_path: String? = null,
    val origin_country: String? = null
)

@Serializable
data class TMDBDetailsResponse(
    val title: String? = null,
    val name: String? = null,
    /** The title as it airs, which is what TVMaze indexes a regional show under. */
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val runtime: Int? = null,
    val episode_run_time: List<Int>? = null,
    val genres: List<TMDBGenre>? = null,
    val original_language: String? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val seasons: List<TMDBSeasonInfo>? = null,
    val networks: List<TMDBNetwork>? = null,
    val production_companies: List<TMDBProductionCompany>? = null,
    /** TMDB status, e.g. "Ended", "Returning Series", "Released". */
    val status: String? = null,
    /** TV creators. */
    val created_by: List<TMDBPerson>? = null,
    /** Production countries. */
    val production_countries: List<TMDBProductionCountry>? = null,
    /** TV origin country codes. */
    val origin_country: List<String>? = null
)

@Serializable
data class TMDBContentRating(
    val iso_3166_1: String? = null,
    val rating: String? = null
)

@Serializable
data class TMDBContentRatingsResponse(
    val results: List<TMDBContentRating> = emptyList()
)

@Serializable
data class TMDBReleaseDateEntry(
    val certification: String? = null
)

@Serializable
data class TMDBReleaseDateResult(
    val iso_3166_1: String? = null,
    val release_dates: List<TMDBReleaseDateEntry> = emptyList()
)

@Serializable
data class TMDBReleaseDatesResponse(
    val results: List<TMDBReleaseDateResult> = emptyList()
)

@Serializable
data class TMDBPersonDetailsResponse(
    val id: Int? = null,
    val name: String? = null,
    val biography: String? = null,
    val known_for_department: String? = null,
    val profile_path: String? = null
)

@Serializable
data class TMDBCombinedCredit(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val media_type: String? = null,
    val poster_path: String? = null,
    val character: String? = null
)

@Serializable
data class TMDBCombinedCreditsResponse(
    val cast: List<TMDBCombinedCredit> = emptyList()
)

@Serializable
data class TMDBEpisodeItem(
    val id: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val season_number: Int,
    val episode_number: Int,
    val air_date: String? = null,
    val air_time: String? = null,
    val runtime: Int? = null,
    val still_path: String? = null
)

@Serializable
data class TMDBSeasonDetailsResponse(
    val _id: String? = null,
    val air_date: String? = null,
    val name: String? = null,
    val season_number: Int,
    val episodes: List<TMDBEpisodeItem> = emptyList()
)

@Serializable
data class TMDBImageItem(
    val file_path: String,
    val iso_639_1: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val vote_average: Double? = null
)

@Serializable
data class TMDBExternalIds(
    val imdb_id: String? = null,
    val tvdb_id: Int? = null,
    val facebook_id: String? = null,
    val instagram_id: String? = null,
    val twitter_id: String? = null,
    val wikidata_id: String? = null,
    val youtube_id: String? = null
)

@Serializable
data class TMDBImagesResponse(
    val id: Int? = null,
    val logos: List<TMDBImageItem> = emptyList(),
    val backdrops: List<TMDBImageItem> = emptyList(),
    val posters: List<TMDBImageItem> = emptyList()
)

@Serializable
data class TMDBWatchProvider(
    val provider_id: Int? = null,
    val provider_name: String,
    val logo_path: String? = null,
    val display_priority: Int = 0
)

@Serializable
data class TMDBWatchRegion(
    val link: String? = null,
    val flatrate: List<TMDBWatchProvider>? = null,
    val free: List<TMDBWatchProvider>? = null,
    val rent: List<TMDBWatchProvider>? = null,
    val buy: List<TMDBWatchProvider>? = null
)

@Serializable
data class TMDBWatchProvidersResponse(
    val id: Int? = null,
    val results: Map<String, TMDBWatchRegion> = emptyMap()
)

/**
 * @param client shared HTTP client. Injecting it keeps one OkHttp connection pool
 *   for the whole app and lets tests drive the client through Ktor's MockEngine.
 */
class TMDBClient(
    private val apiKey: String,
    private val client: HttpClient = defaultClient()
) {
    private val isBearerToken: Boolean
        get() = apiKey.trim().length > 40 && apiKey.trim().startsWith("ey")

    private fun HttpRequestBuilder.applyAuth() {
        val trimmed = apiKey.trim()
        if (isBearerToken) {
            header("Authorization", "Bearer $trimmed")
        } else {
            parameter("api_key", trimmed)
        }
    }

    suspend fun getMovieDetails(id: Int): Result<TMDBDetailsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id") {
            applyAuth()
        }.body()
    }

    suspend fun getTVDetails(id: Int): Result<TMDBDetailsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id") {
            applyAuth()
        }.body()
    }

    suspend fun getMovieImages(id: Int): Result<TMDBImagesResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id/images") {
            applyAuth()
            parameter("include_image_language", "en,null")
        }.body()
    }

    suspend fun getTVImages(id: Int): Result<TMDBImagesResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id/images") {
            applyAuth()
            parameter("include_image_language", "en,null")
        }.body()
    }

    /** US content rating for a TV show, e.g. "TV-MA". */
    suspend fun getTVContentRatings(id: Int): Result<TMDBContentRatingsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id/content_ratings") {
            applyAuth()
        }.body()
    }

    /** US certification for a movie, e.g. "R", "PG-13". */
    suspend fun getMovieReleaseDates(id: Int): Result<TMDBReleaseDatesResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id/release_dates") {
            applyAuth()
        }.body()
    }

    /** Person bio + filmography, used by the detail cast bio sheet. */
    suspend fun getPersonDetails(id: Int): Result<TMDBPersonDetailsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/person/$id") {
            applyAuth()
        }.body()
    }

    /** A person's combined movie + TV credits, used for "Known Works". */
    suspend fun getPersonCombinedCredits(id: Int): Result<TMDBCombinedCreditsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/person/$id/combined_credits") {
            applyAuth()
        }.body()
    }

    /** TVDB id is the reliable bridge from TMDB to TVMaze. */
    suspend fun getTVExternalIds(tvId: Int): Result<TMDBExternalIds> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$tvId/external_ids") {
            applyAuth()
        }.body()
    }

    suspend fun getSeasonDetails(tvId: Int, seasonNumber: Int): Result<TMDBSeasonDetailsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$tvId/season/$seasonNumber") {
            applyAuth()
        }.body()
    }

    suspend fun getMovieCredits(id: Int): Result<TMDBCreditsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id/credits") {
            applyAuth()
        }.body()
    }

    suspend fun getTVCredits(id: Int): Result<TMDBCreditsResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id/credits") {
            applyAuth()
        }.body()
    }

    suspend fun searchMulti(query: String): Result<TMDBSearchResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/search/multi") {
            applyAuth()
            parameter("query", query)
            parameter("include_adult", false)
        }.body()
    }

    /** Trending across movies + TV (window: "day" or "week"). */
    suspend fun getTrending(window: String = "week"): Result<TMDBSearchResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/trending/all/$window") {
            applyAuth()
        }.body()
    }

    suspend fun getMovieRecommendations(id: Int): Result<TMDBSearchResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id/recommendations") {
            applyAuth()
        }.body()
    }

    suspend fun getTVRecommendations(id: Int): Result<TMDBSearchResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id/recommendations") {
            applyAuth()
        }.body()
    }

    suspend fun getMovieWatchProviders(id: Int): Result<TMDBWatchProvidersResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/movie/$id/watch/providers") {
            applyAuth()
        }.body()
    }

    suspend fun getTVWatchProviders(id: Int): Result<TMDBWatchProvidersResponse> = runCatching {
        client.get("https://api.themoviedb.org/3/tv/$id/watch/providers") {
            applyAuth()
        }.body()
    }

    companion object {
        /**
         * Fallback client for call sites outside the DI graph. Production code should
         * inject a shared client instead so connections are pooled across instances.
         */
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }

        fun buildPosterUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w780$path"
        }

        fun buildBackdropUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w1280$path"
        }

        fun buildLogoUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w780$path"
        }

        fun buildProfileUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w342$path"
        }
    }
}
