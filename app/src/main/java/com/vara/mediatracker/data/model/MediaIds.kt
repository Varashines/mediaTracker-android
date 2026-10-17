package com.vara.mediatracker.data.model

/**
 * Canonical identifier helpers shared by every producer of media / episode keys.
 *
 * Historically two different formats were emitted for the same episode:
 *   - DetailScreen.kt (TMDB enrichment):  "{tmdbId}_{season}_{episode}"     e.g. "1399_1_1"
 *   - CloudSyncManager.kt (Supabase push): "tv_{showId}_s{season}_e{episode}" e.g. "tv_1399_s1_e1"
 *
 * Rows created by one path were invisible to the other, producing duplicate
 * episode entries in Room and duplicated rows in Supabase. Everything must go
 * through [buildEpisodeUniqueId] now; [parseEpisodeUniqueId] additionally
 * understands the legacy form so existing installs can be reconciled.
 */
object MediaIds {

    private val CANONICAL_EPISODE = Regex("^tv_(\\d+)_s(\\d+)_e(\\d+)$")
    private val LEGACY_EPISODE = Regex("^(\\d+)_(\\d+)_(\\d+)$")

    /**
     * Normalizes any accepted show identifier to the canonical `tv_<tmdbId>` form.
     *
     * TV-show specific: passing a movie id ("movie_550") would yield "tv_550",
     * which is meaningless. Only call this for TV ids.
     */
    fun tvShowKey(mediaId: String): String {
        val trimmed = mediaId.trim()
        return if (trimmed.startsWith("tv_")) trimmed else "tv_" + trimmed.substringAfterLast("_")
    }

    /** Extracts the numeric TMDB id from "movie_550" / "tv_1399" / "1399". Returns 0 if unparseable. */
    fun tmdbIdOf(mediaId: String): Int = mediaId.trim().substringAfterLast("_").toIntOrNull() ?: 0

    /** Canonical episode key, e.g. `tv_1399_s2_e5`. Accepts any accepted show-id form. */
    fun buildEpisodeUniqueId(showId: String, seasonNumber: Int, episodeNumber: Int): String {
        val cleanShowId = tvShowKey(showId).removePrefix("tv_")
        return "tv_${cleanShowId}_s${seasonNumber}_e${episodeNumber}"
    }

    /**
     * The cloud/Mac episode key, e.g. `1399_2_5`.
     *
     * Supabase and the macOS app key episodes as `{tmdbId}_{season}_{episode}` (no `tv_`
     * prefix, no `s`/`e`). Android stores the canonical form locally but must push this
     * legacy shape so the two apps share rows instead of creating duplicates.
     */
    fun buildCloudEpisodeId(showId: String, seasonNumber: Int, episodeNumber: Int): String =
        "${tmdbIdOf(showId)}_${seasonNumber}_${episodeNumber}"

    fun isCanonicalEpisodeId(uniqueId: String): Boolean = CANONICAL_EPISODE.matches(uniqueId)

    /**
     * Canonical `tv_{show}_s{season}_e{episode}` for either spelling; `null` stays `null`
     * (movies carry no episode id).
     *
     * The two spellings are compared literally elsewhere in the sync, which is how one viewing
     * ended up in the ledger twice: the event recorded here used the canonical form, the one
     * pulled back from the Mac the legacy `{show}_{s}_{e}` form.
     */
    fun canonicalEpisodeIdOrNull(id: String?): String? = id?.let {
        parseEpisodeUniqueId(it)?.let { key ->
            buildEpisodeUniqueId(key.showId, key.seasonNumber, key.episodeNumber)
        } ?: it
    }

    /** Parses both canonical and legacy episode keys. Returns null for unrecognized formats. */
    fun parseEpisodeUniqueId(uniqueId: String): EpisodeKey? {
        val id = uniqueId.trim()
        CANONICAL_EPISODE.matchEntire(id)?.let { m ->
            return EpisodeKey(
                showId = "tv_${m.groupValues[1]}",
                seasonNumber = m.groupValues[2].toInt(),
                episodeNumber = m.groupValues[3].toInt()
            )
        }
        LEGACY_EPISODE.matchEntire(id)?.let { m ->
            return EpisodeKey(
                showId = "tv_${m.groupValues[1]}",
                seasonNumber = m.groupValues[2].toInt(),
                episodeNumber = m.groupValues[3].toInt()
            )
        }
        return null
    }
}

/** Parsed components of an episode key. */
data class EpisodeKey(
    val showId: String,
    val seasonNumber: Int,
    val episodeNumber: Int
)