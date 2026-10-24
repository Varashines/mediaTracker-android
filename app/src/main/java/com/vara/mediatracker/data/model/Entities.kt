package com.vara.mediatracker.data.model

import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(
    tableName = "media_items",
    indices = [
        // Covers the hot `WHERE isSoftDeleted = 0 ORDER BY lastInteractionDate DESC`
        // query every screen reads from, plus the per-state variant.
        Index(value = ["isSoftDeleted", "lastInteractionDate"]),
        Index(value = ["state", "isSoftDeleted"])
    ]
)
@Serializable
data class MediaItemEntity(
    @PrimaryKey val id: String,              // e.g. "movie_550" or "tv_1399"
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseDate: String? = null,
    val type: String = "Movie",              // "Movie" or "TV"
    val state: String = "Wishlist",          // "Wishlist", "Watching", "Completed", "Paused", "Dropped"
    val taste: String = "None",              // "Love", "Like", "Dislike", "None" (singular, as stored)
    val customPosterUrl: String? = null,
    /**
     * Precise instant of the next airing, ISO-8601, from TVMaze `airstamp`.
     *
     * The macOS equivalent is `cachedNextAiringDate`, and BadgeEngine reads it in the
     * same two places. TMDB cannot supply this: its season endpoint has no `air_time`
     * field at all, so without it a title's air date is known only to the day, which is
     * not precise enough for a 48-hour SOON window or a 3-day premiere boundary.
     */
    val nextAiringInstant: String? = null,
    /**
     * Show timezone identifier (e.g. "America/New_York"), from TVMaze's
     * `network.country.timezone`. Used to interpret a real broadcast airtime; the
     * macOS `TVShowDetails.timezone` is the equivalent.
     */
    val timezone: String? = null,
    /**
     * Resolved network / service name, used to select the streaming drop-time rule.
     * macOS keeps this as `cachedNetwork`.
     */
    val network: String? = null,
    /**
     * Discovery facets, comma-joined where a title has several. Backed by
     * `MetadataEnrichmentWorker` so the Discovery Hub can aggregate without extra
     * network calls at read time. macOS stores these as `cachedGenres`,
     * `cachedLanguage`, and the Network/Genre/Language/Provider entities.
     */
    val genres: String? = null,
    val language: String? = null,
    val studios: String? = null,
    val providers: String? = null,
    val isSoftDeleted: Boolean = false,
    val lastInteractionDate: String? = null,
    val lastStateChangeDate: String? = null,
    /**
     * When the title joined the library. Distinct from `lastInteractionDate`: this must NOT
     * move when the user watches, likes or edits it, or "Recently Added" reshuffles.
     * macOS keeps the same value in `MediaItem.dateAdded`.
     */
    val dateAdded: String? = null,
    val updatedAt: String? = null,
    /**
     * The Mac's extracted poster palette, carried through sync.
     *
     * Android deliberately does not extract these. The Mac owns the algorithm, and a second one
     * here would answer differently for the same poster — and only one of the two can be right.
     * They are read-only on this device: pulled, displayed, and pushed back unchanged.
     *
     * Each is nullable independently. Rows written by an older Mac path carry only the primary,
     * so a consumer must fall back per field rather than assuming all three arrive together.
     */
    val themeColorHex: String? = null,
    val themeMutedColorHex: String? = null,
    val themeSecondaryColorHex: String? = null
)

@Entity(
    tableName = "tv_episodes",
    // Every episode read filters by showId (per-show lists, eligibility scans, and the
    // enrichment `NOT EXISTS` subqueries). Without this the whole table is scanned.
    indices = [Index(value = ["showId"])]
)
@Serializable
data class EpisodeEntity(
    @PrimaryKey val uniqueId: String,        // e.g. "tv_1399_s1_e1"
    val showId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String = "",
    val overview: String = "",
    val stillPath: String? = null,
    val isWatched: Boolean = false,
    val watchedDate: String? = null,
    val airDate: String? = null,
    /**
     * Local air time of day, "HH:mm", as returned by TMDB.
     *
     * `airDate` alone is not enough for badge parity: it parses to midnight, which
     * places an episode up to a day before it actually airs. That is enough to push
     * `daysSinceAir` across the 3-day PREMIERE boundary and turn PREMIERE into NEW.
     * macOS avoids this because it stores the real instant.
     */
    val airTime: String? = null,
    /**
     * Full ISO-8601 air instant from TVMaze (`airstamp`), the most precise source for
     * an episode. macOS stores the same value on `TVEpisode.airstamp`; it wins over a
     * bare date + time because it already encodes the correct offset.
     */
    val airstamp: String? = null,
    val runtime: Int? = null,
    val updatedAt: String? = null
)

/**
 * Persisted smart-badge state for one title.
 *
 * Badges used to be derived inside `remember {}` on every Home composition, which
 * meant they were only ever as fresh as the last time a screen recomposed. Persisting
 * them lets a background worker keep them current while the app is closed, and means
 * the UI reads a small indexed table instead of running BadgeEngine across every
 * title and every episode on the main thread.
 *
 * `targetDate` is the air/release date the badge was computed against. It makes the
 * badge's window explicit and lets notifications tell "newly SOON" from "still SOON".
 */
@Entity(
    tableName = "media_badges",
    indices = [Index(value = ["badgeLabel"]), Index(value = ["targetDate"])]
)
data class MediaBadgeEntity(
    @PrimaryKey val mediaId: String,
    val badgeLabel: String,
    val isSparkle: Boolean,
    val targetDate: String?,
    val computedAt: Long
)

/**
 * Per-season metadata, chiefly TMDB's declared `episodeCount`.
 *
 * BadgeEngine's FINALE rule compares the next unwatched episode against the season's
 * real length. Counting local episode rows only works when a show's seasons have been
 * fully fetched; on a partial fetch a mid-season episode would be mistaken for a
 * finale. macOS avoids this by reading `TVSeason.episodeCount` from the season
 * record, so this table is the equivalent source of truth.
 */
@Entity(
    tableName = "tv_seasons",
    primaryKeys = ["showId", "seasonNumber"],
    indices = [Index(value = ["showId"])]
)
data class TVSeasonEntity(
    val showId: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val name: String? = null,
    val airDate: String? = null,
    val updatedAt: String? = null
)

/**
 * Logo path for a Discovery facet value (network / studio / provider).
 *
 * The metadata worker already receives these from TMDB but used to keep only the names,
 * so the Hub and Library had to render coloured letter markers. Storing the paths keyed
 * by facet + name lets every surface show the real logo without a per-title column.
 */
@Entity(
    tableName = "facet_logos",
    primaryKeys = ["facet", "name"]
)
data class FacetLogoEntity(
    val facet: String,      // "network" | "studio" | "provider"
    val name: String,
    val logoPath: String
)

/**
 * A user-created (or smart) collection, mirroring macOS's `MediaCollection`.
 *
 * [id] is the macOS UUID string, so a collection created on either device keeps the same
 * identity when it syncs. Smart collections carry [smartRules] (macOS's `SmartRuleSet`
 * JSON) and have no stored members — their contents are computed per device.
 */
@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"])]
)
@Serializable
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemImage: String = "folder",
    val notes: String? = null,
    val isPinned: Boolean = false,
    val isSmart: Boolean = false,
    val smartRules: String? = null,
    val completedItemIds: List<String> = emptyList(),
    val isDeleted: Boolean = false,
    val updatedAt: String? = null
)

/**
 * Collection membership (the join table). [isDeleted] is a tombstone so removing a title
 * from a collection propagates instead of being re-added by the next pull.
 */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "mediaId"],
    indices = [Index(value = ["collectionId"]), Index(value = ["mediaId"])]
)
@Serializable
data class CollectionItemEntity(
    val collectionId: String,
    val mediaId: String,
    val isDeleted: Boolean = false,
    val updatedAt: String? = null
)

/** Room has no array column; media ids never contain newlines, so join/split is lossless. */
class CollectionConverters {
    @TypeConverter
    fun fromStringList(value: List<String>?): String = (value ?: emptyList()).joinToString("\n")

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        value?.takeIf { it.isNotEmpty() }?.split("\n") ?: emptyList()
}

/**
 * One viewing run of a title, mirroring macOS's `WatchCycle`.
 *
 * A second+ run carries `isRewatch = true`, which is what makes "when did I rewatch this"
 * meaningful rather than just "when did I last watch it".
 */
@Entity(
    tableName = "watch_cycles",
    indices = [Index(value = ["mediaId"])]
)
@Serializable
data class WatchCycleEntity(
    @PrimaryKey val id: String,              // UUID string, shared with macOS
    val mediaId: String,
    val kind: String,                        // "movie" | "tvShow"
    val startedAt: String,                   // ISO-8601
    val completedAt: String? = null,
    val state: String = "active",            // active | completed | paused | archived
    val isRewatch: Boolean = false,
    val isComplete: Boolean = false,
    val scopeEpisodeIds: List<String> = emptyList(),
    val isBackfilled: Boolean = false,
    val updatedAt: String? = null
)

/**
 * One immutable watch fact, mirroring macOS's `WatchEvent`. Rows are only ever voided, never
 * edited, so the sync merge is a union by id plus a forward-only void.
 */
@Entity(
    tableName = "watch_events",
    indices = [Index(value = ["mediaId"]), Index(value = ["cycleId"]), Index(value = ["watchedAt"])]
)
@Serializable
data class WatchEventEntity(
    @PrimaryKey val id: String,              // UUID string, shared with macOS
    val cycleId: String,
    val mediaId: String,
    val episodeId: String? = null,
    val watchedAt: String,                   // ISO-8601
    val source: String = "manual",           // manual | automatic | imported | migration
    val timezoneIdentifier: String? = null,
    val runtimeMinutes: Int? = null,
    val voidedAt: String? = null,
    val deduplicationKey: String,
    val isBackfilled: Boolean = false,
    val updatedAt: String? = null
)
