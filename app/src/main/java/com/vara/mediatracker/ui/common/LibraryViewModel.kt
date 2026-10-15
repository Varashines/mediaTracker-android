package com.vara.mediatracker.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vara.mediatracker.data.badge.BadgeResult
import com.vara.mediatracker.data.badge.SmartBadge
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.FacetLogoEntity
import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.home.HomeEligibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Episode totals / next-episode state for one show, shown on Home rails and detail lists. */
data class TVShowProgress(
    val totalEpisodes: Int,
    val watchedEpisodes: Int,
    val nextEpisode: EpisodeEntity?,
    val nextAiringDate: String?
)

/**
 * The whole library joined once, off the UI thread.
 *
 * Every screen used to collect `getAllEpisodesFlow()` (~9k rows) itself and then group,
 * sort and scan that list inside a `remember {}` on the main thread. Tab switches also
 * threw the result away, because `AnimatedContent` disposes the outgoing screen — so the
 * same work was redone on every transition and stuttered the animation.
 *
 * This holder is produced once per DB change on [Dispatchers.Default] and shared by the
 * activity-scoped ViewModel, so the UI only ever reads the finished result.
 */
data class LibraryCore(
    val items: List<MediaItemEntity> = emptyList(),
    val allEpisodes: List<EpisodeEntity> = emptyList(),
    val episodesByShow: Map<String, List<EpisodeEntity>> = emptyMap(),
    val badges: Map<String, BadgeResult> = emptyMap(),
    val badgeRows: List<MediaBadgeEntity> = emptyList(),
    /** lowercased facet name -> TMDB logo path (networks, studios, providers). */
    val logos: Map<String, String> = emptyMap()
)

/** Home-specific derivations (Continue Watching, Coming Soon, hero) off the UI thread. */
data class HomeData(
    val showProgressMap: Map<String, TVShowProgress> = emptyMap(),
    val resumeItems: List<MediaItemEntity> = emptyList(),
    val comingSoonItems: List<MediaItemEntity> = emptyList(),
    val recentlyAddedItems: List<MediaItemEntity> = emptyList(),
    val lovedItems: List<MediaItemEntity> = emptyList(),
    val spotlightItems: List<MediaItemEntity> = emptyList(),
    val spotlightItem: MediaItemEntity? = null
)

/** The four Room flows joined before the (off-main) library build. */
private data class RawLibrary(
    val items: List<MediaItemEntity>,
    val episodes: List<EpisodeEntity>,
    val badgeRows: List<MediaBadgeEntity>,
    val logoRows: List<FacetLogoEntity>
)

/**
 * Activity-scoped so its state survives tab switches (see [LibraryCore]).
 *
 * Both flows are `WhileSubscribed`: [core] stays warm while any screen is open, and
 * [home] is only computed while Home is on screen — but its last value is retained, so
 * returning to Home shows the previous result until the fresh one lands.
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val core: StateFlow<LibraryCore> = combine(
        container.mediaDao.getAllActiveMedia(),
        container.mediaDao.getAllEpisodesFlow(),
        container.database.badgeDao().observeBadges(),
        container.database.facetLogoDao().observeLogos()
    ) { items, episodes, badgeRows, logoRows ->
        RawLibrary(items, episodes, badgeRows, logoRows)
    }.mapLatest { raw ->
        withContext(Dispatchers.Default) {
            LibraryCore(
                items = raw.items,
                allEpisodes = raw.episodes,
                // Sorted once here so every consumer (Home rails, Hub, Calendar, Detail)
                // gets season/episode order without re-sorting each show's list itself.
                episodesByShow = raw.episodes
                    .sortedWith(compareBy({ it.showId }, { it.seasonNumber }, { it.episodeNumber }))
                    .groupBy { it.showId },
                badges = raw.badgeRows.associate { row ->
                    row.mediaId to BadgeResult(
                        SmartBadge.fromLabel(row.badgeLabel) ?: SmartBadge.NEW,
                        isSparkle = row.isSparkle
                    )
                },
                badgeRows = raw.badgeRows,
                logos = raw.logoRows.associate { it.name.lowercase(Locale.US) to it.logoPath }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryCore())

    val home: StateFlow<HomeData> = core
        .mapLatest { withContext(Dispatchers.Default) { computeHome(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeData())

    private fun computeHome(core: LibraryCore): HomeData {
        val items = core.items
        val episodesByShow = core.episodesByShow
        val badges = core.badges
        val todayDateStr = LocalDate.now().toString()
        val now = Date()

        val showProgressMap = items.filter { it.type.contains("TV", ignoreCase = true) }.associate { item ->
            val showKey = MediaIds.tvShowKey(item.id)
            // `episodesByShow` is already ordered by (showId, season, episode) in `core`.
            val showEps = episodesByShow[showKey] ?: episodesByShow[item.id] ?: emptyList()
            val total = showEps.size
            val watched = showEps.count { it.isWatched }
            val nextEp = showEps.firstOrNull { !it.isWatched }
            // nextAir is the FIRST unwatched episode that airs in the STRICT FUTURE (> today)
            val nextAir = showEps.firstOrNull { ep ->
                !ep.isWatched && !ep.airDate.isNullOrBlank() && ep.airDate > todayDateStr
            }?.airDate
            item.id to TVShowProgress(
                totalEpisodes = total,
                watchedEpisodes = watched,
                nextEpisode = nextEp,
                nextAiringDate = nextAir
            )
        }

        val resumeItems = HomeEligibility.resumeCandidates(
            items = items,
            episodesByShow = episodesByShow,
            badges = badges,
            now = now
        )

        val resumeIds = resumeItems.map { it.id }.toSet()
        // Resolve each candidate's next-airing instant once, then filter and sort on it.
        // The previous version recomputed the instant a second time inside the sort.
        val comingSoonItems = items.asSequence()
            .filter { !it.isSoftDeleted && !resumeIds.contains(it.id) }
            .mapNotNull { item ->
                val key = if (item.type.contains("TV", ignoreCase = true)) MediaIds.tvShowKey(item.id) else null
                val instant = HomeEligibility.nextAiringInstant(item, key?.let { episodesByShow[it] }.orEmpty())
                if (HomeEligibility.isComingSoonAt(item, instant, now)) instant to item else null
            }
            .sortedBy { it.first?.time ?: Long.MAX_VALUE }
            .take(15)
            .map { it.second }
            .toList()

        val recentlyAddedItems = items.filter { !it.isSoftDeleted }
            // Genuinely "added", not "recently touched": dateAdded doesn't move on a watch,
            // a like or a sync. Rows predating the column fall back until a sync supplies the
            // Mac's dateAdded (the same value the Mac's own Home orders by).
            //
            // Title is the tie-break, matching macOS's `dateAdded desc, title asc`. Without it
            // ties keep the input order — which is lastInteractionDate DESC — so the ~155 rows
            // that share the Mac's bulk-import instant reshuffled whenever anything was watched.
            .sortedWith(
                compareByDescending<MediaItemEntity> { it.dateAdded ?: it.lastInteractionDate ?: "" }
                    .thenBy { it.title.lowercase() }
            )
            .take(15)

        val lovedItems = items.filter {
            !it.isSoftDeleted &&
                (it.taste.equals("Loved", ignoreCase = true) || it.taste.equals("Love", ignoreCase = true))
        }

        val spotlightItems = (resumeItems + comingSoonItems + lovedItems + items)
            .filter { it.backdropUrl != null || it.posterUrl != null }
            .distinctBy { it.id }
            .take(5)
        val spotlightItem = spotlightItems.firstOrNull()

        return HomeData(
            showProgressMap = showProgressMap,
            resumeItems = resumeItems,
            comingSoonItems = comingSoonItems,
            recentlyAddedItems = recentlyAddedItems,
            lovedItems = lovedItems,
            spotlightItems = spotlightItems,
            spotlightItem = spotlightItem
        )
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LibraryViewModel(container) }
        }
    }
}
