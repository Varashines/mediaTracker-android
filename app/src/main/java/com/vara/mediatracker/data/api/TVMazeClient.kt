package com.vara.mediatracker.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Date

/**
 * TVMaze client, used for one thing: the precise instant an episode airs.
 *
 * TMDB does not return `air_time` on `/tv/{id}/season/{n}` at all — verified against a
 * live response, where the field is absent from the episode keys entirely. Parsing a
 * bare `air_date` therefore places an episode at midnight, up to a day before it
 * actually airs, which is enough to push `daysSinceAir` across BadgeEngine's 3-day
 * premiere boundary and turn a PREMIERE into a NEW. TVMaze publishes `airstamp`, a full
 * ISO instant, which is what the macOS app uses for its `cachedNextAiringDate`.
 *
 * TVMaze needs no API key.
 */
class TVMazeClient(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** TVMaze show id from a TVDB id, which TMDB exposes via external_ids. */
    suspend fun lookupByTvDbId(tvDbId: Int): Result<Int> = runCatching {
        val obj = getJsonObject("$BASE/lookup/shows?thetvdb=$tvDbId")
        obj["id"]?.jsonPrimitive?.int
            ?: error("No TVMaze match for tvdb $tvDbId")
    }

    /**
     * Title search, used when a show has no TVDB id. Candidates are ranked by title
     * similarity so an exact match beats a more popular but wrong show.
     */
    suspend fun searchShowByTitle(title: String): Result<Int> = runCatching {
        val arr = getJsonArray("$BASE/search/shows?q=${encodeQuery(title)}")
        arr.mapNotNull { element ->
            val show = element.jsonObject["show"]?.jsonObject ?: return@mapNotNull null
            val id = show["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = show["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            id to name
        }
            .maxByOrNull { (_, name) -> titleSimilarity(title, name) }
            ?.takeIf { (_, name) -> titleSimilarity(title, name) >= MIN_TITLE_SIMILARITY }
            ?.first
            ?: error("No confident TVMaze match for '$title'")
    }

    /**
     * The next episode a show will air, as a real instant.
     *
     * @return the airing instant, or null when nothing is scheduled.
     */
    suspend fun nextEpisodeInstant(tvMazeId: Int): Result<Date?> = runCatching {
        val show = getJsonObject("$BASE/shows/$tvMazeId?embed=nextepisode")
        val next = show["_embedded"]?.jsonObject?.get("nextepisode")?.jsonObject
        parseInstant(next?.get("airstamp")?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Falls back to air date + air time when `airstamp` is missing, interpreted in the
     * show's own timezone so it still lands on the correct side of the windows.
     */
    private fun parseInstant(airstamp: String?): Date? {
        if (airstamp.isNullOrBlank()) return null
        return runCatching { Date.from(Instant.parse(airstamp)) }.getOrNull()
    }

    private suspend fun getJsonObject(url: String): JsonObject =
        json.parseToJsonElement(get(url)).jsonObject

    private suspend fun getJsonArray(url: String): JsonArray =
        json.parseToJsonElement(get(url)) as JsonArray

    private suspend fun get(url: String): String = client.get(url).body()

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /** Dice coefficient over alphanumeric characters. */
    private fun titleSimilarity(a: String, b: String): Double {
        val x = a.lowercase().filter(Char::isLetterOrDigit)
        val y = b.lowercase().filter(Char::isLetterOrDigit)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.zip(y).count { (p, q) -> p == q }
        return 2.0 * common / (x.length + y.length)
    }

    companion object {
        const val BASE = "https://api.tvmaze.com"
        const val MIN_TITLE_SIMILARITY = 0.72
    }
}