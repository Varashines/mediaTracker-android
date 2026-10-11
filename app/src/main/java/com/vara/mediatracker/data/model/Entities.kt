package com.vara.mediatracker.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val id: String,              // e.g. "movie_550" or "tv_1399"
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseDate: String? = null,
    val type: String = "Movie",              // "Movie" or "TV"
    val state: String = "Wishlist",          // "Wishlist", "Watching", "Completed", "Paused", "Dropped"
    val taste: String = "None",              // "Loved", "Liked", "Disliked", "Hated", "None"
    val customPosterUrl: String? = null,
    val isSoftDeleted: Boolean = false,
    val lastInteractionDate: String? = null,
    val lastStateChangeDate: String? = null,
    val updatedAt: String? = null
)

@Entity(tableName = "tv_episodes")
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
