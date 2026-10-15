package com.vara.mediatracker.ui.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.vara.mediatracker.data.TrackingLogic
import com.vara.mediatracker.data.WatchHistoryRecorder
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TMDBDetailsResponse
import com.vara.mediatracker.data.api.TMDBSearchItem
import com.vara.mediatracker.data.api.TmdbResponseCache
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.ChipTone
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.ProgressLine
import com.vara.mediatracker.ui.common.SectionHeader
import com.vara.mediatracker.ui.common.SwipeActionRow
import com.vara.mediatracker.ui.common.StatusChip
import com.vara.mediatracker.ui.common.parseAirInstant
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.rememberTodayDateString
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/** A tracking state: raw value, label, icon, picker description, and accent tint. */
private data class TrackingState(
    val raw: String,
    val label: String,
    val icon: ImageVector,
    val description: String,
    val tint: Color? = null
)

/** The six tracking states, in picker order. */
private val TrackingStates = listOf(
    TrackingState("Wishlist", "Watchlist", Icons.Default.Schedule, "Saved for future viewing", Color(0xFF64B5F6)),
    TrackingState("Active", "In Progress", Icons.Default.PlayArrow, "Currently in progress"),
    TrackingState("Completed", "Completed", Icons.Default.Check, "Finished watching", Color(0xFF66BB6A)),
    TrackingState("On Hold", "On Hold", Icons.Default.Pause, "Temporarily paused", Color(0xFFFBBF24)),
    TrackingState("Dropped", "Dropped", Icons.Default.Close, "Discontinued watching", Color(0xFFEF5350)),
    TrackingState("Re-watching", "Re-watching", Icons.Default.Refresh, "Experiencing the story again", Color(0xFFAB47BC))
)

private fun matchedState(state: String): TrackingState =
    TrackingStates.firstOrNull {
        it.raw.equals(state, ignoreCase = true) ||
            it.label.equals(state, ignoreCase = true) ||
            (it.raw == "Active" && state.equals("Watching", ignoreCase = true))
    } ?: TrackingStates[0]

private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

/** Detail view tabs. TV defaults to Episodes; movies default to Info. */
private enum class DetailTab(val label: String) {
    Episodes("Episodes"),
    Info("Info"),
    Related("Related")
}

/**
 * Tracking-first detail screen: a compact hero (poster, genre, rating, release pill),
 * status + taste pills, a metadata strip, the episode checklist with quick mark-watched,
 * and a floating Up Next action so the next episode is always one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    mediaId: String,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit = {}
) {
    val db = container.database
    val prefs = container.preferences
    val scope = rememberCoroutineScope()
    val sem = LocalSemanticColors.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var mediaItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    var statusPickerOpen by remember { mutableStateOf(false) }
    var collectionPickerOpen by remember { mutableStateOf(false) }
    val showIdKey = MediaIds.tvShowKey(mediaId)
    val episodes by db.mediaDao().getEpisodesForShow(showIdKey)
        .collectAsState(initial = emptyList())
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var detailsLoading by remember { mutableStateOf(true) }

    val seasonsList = remember(episodes) {
        episodes.map { it.seasonNumber }.distinct().sorted()
    }
    var selectedSeason by remember(seasonsList) {
        mutableStateOf(
            seasonsList.firstOrNull { s -> episodes.any { it.seasonNumber == s && !it.isWatched } }
                ?: seasonsList.firstOrNull() ?: 1
        )
    }
    val currentSeasonEpisodes = remember(episodes, selectedSeason) {
        episodes.filter { it.seasonNumber == selectedSeason }
    }
    val nextUpEpisode = remember(episodes) { episodes.firstOrNull { !it.isWatched } }

    var tmdbDetails by remember { mutableStateOf<TMDBDetailsResponse?>(null) }
    var contentRating by remember { mutableStateOf<String?>(null) }
    var castMembers by remember { mutableStateOf<List<com.vara.mediatracker.data.api.TMDBCastMember>>(emptyList()) }
    var directorName by remember { mutableStateOf<String?>(null) }
    var relatedItems by remember { mutableStateOf<List<com.vara.mediatracker.data.api.TMDBSearchItem>>(emptyList()) }
    var selectedActor by remember { mutableStateOf<com.vara.mediatracker.data.api.TMDBCastMember?>(null) }
    var titleLogoUrl by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(mediaId, reloadTrigger) {
        loadFailed = false
        val loaded = runCatching { db.mediaDao().getMediaById(mediaId) }.getOrNull()
        if (loaded == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        mediaItem = loaded

        if (prefs.tmdbApiKey.isNotBlank()) {
            val tmdbId = MediaIds.tmdbIdOf(loaded.id)
            if (tmdbId > 0) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val tmdbClient = container.tmdbClient()
                        val isMovie = loaded.type.contains("Movie", ignoreCase = true)

                        val detailsKey = TmdbResponseCache.detailsKey(loaded.id)
                        val details = TmdbResponseCache.get<TMDBDetailsResponse>(detailsKey)
                            ?: (if (isMovie) tmdbClient.getMovieDetails(tmdbId) else tmdbClient.getTVDetails(tmdbId))
                                .getOrNull()?.also { TmdbResponseCache.put(detailsKey, it) }
                        tmdbDetails = details

                        val tmdbBackdrop = TMDBClient.buildBackdropUrl(details?.backdrop_path)
                        val tmdbPoster = TMDBClient.buildPosterUrl(details?.poster_path)
                        if (tmdbPoster != null || tmdbBackdrop != null) {
                            val updated = loaded.copy(
                                posterUrl = loaded.posterUrl ?: tmdbPoster,
                                backdropUrl = loaded.backdropUrl ?: tmdbBackdrop
                            )
                            if (updated != loaded) {
                                db.mediaDao().upsertMedia(listOf(updated))
                                mediaItem = updated
                            }
                        }

                        val net = if (isMovie) {
                            details?.production_companies?.firstOrNull()?.name
                        } else {
                            details?.networks?.firstOrNull()?.name
                        }
                        if (!net.isNullOrBlank()) {
                            prefs.setCachedNetwork(loaded.id, net)
                            val current = mediaItem ?: loaded
                            if (current.network != net) {
                                val withNet = current.copy(network = net)
                                db.mediaDao().upsertMedia(listOf(withNet))
                                mediaItem = withNet
                            }
                        }

                        val rt = if (isMovie) {
                            details?.runtime
                        } else {
                            details?.episode_run_time?.firstOrNull { it > 0 } ?: details?.runtime
                        }
                        if (rt != null && rt > 0) prefs.setCachedRuntime(loaded.id, rt)

                        val genreStr = details?.genres?.map { it.name }?.joinToString(", ")
                        if (!genreStr.isNullOrBlank()) prefs.setCachedGenres(loaded.id, genreStr)

                        val voteAvg = details?.vote_average
                        if (voteAvg != null && voteAvg > 0) prefs.setCachedVoteAverage(loaded.id, voteAvg.toFloat())

                        val ratingKey = "contentrating:${loaded.id}"
                        contentRating = TmdbResponseCache.get<String>(ratingKey) ?: run {
                            val fetched = if (isMovie) {
                                tmdbClient.getMovieReleaseDates(tmdbId).getOrNull()?.results
                                    ?.firstOrNull { it.iso_3166_1 == "US" }?.release_dates
                                    ?.firstNotNullOfOrNull { it.certification?.takeIf { c -> c.isNotBlank() } }
                            } else {
                                tmdbClient.getTVContentRatings(tmdbId).getOrNull()?.results
                                    ?.firstOrNull { it.iso_3166_1 == "US" }?.rating?.takeIf { it.isNotBlank() }
                            }
                            fetched?.also { TmdbResponseCache.put(ratingKey, it) }
                        }

                        // Title-treatment wordmark logo, cached by id.
                        val logoKey = "titlelogo:${loaded.id}"
                        val cachedLogo = TmdbResponseCache.get<String>(logoKey)
                        val logoPath = cachedLogo ?: run {
                            val imagesRes = if (isMovie) tmdbClient.getMovieImages(tmdbId) else tmdbClient.getTVImages(tmdbId)
                            val logos = imagesRes.getOrNull()?.logos ?: emptyList()
                            val best = logos
                                .filter { it.iso_639_1 == null || it.iso_639_1 == "en" }
                                .maxByOrNull { it.vote_average ?: 0.0 }
                                ?: logos.maxByOrNull { it.vote_average ?: 0.0 }
                            best?.file_path?.also { TmdbResponseCache.put(logoKey, it) }
                        }
                        titleLogoUrl = logoPath?.let { TMDBClient.buildLogoUrl(it) }

                        // Cast for the carousel.
                        val creditsKey = TmdbResponseCache.creditsKey(loaded.id)
                        val credits = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBCreditsResponse>(creditsKey)
                            ?: (if (isMovie) tmdbClient.getMovieCredits(tmdbId) else tmdbClient.getTVCredits(tmdbId))
                                .getOrNull()?.also { TmdbResponseCache.put(creditsKey, it) }
                        castMembers = credits?.cast?.take(15) ?: emptyList()
                        directorName = credits?.crew?.firstOrNull { it.job == "Director" }?.name

                        // Related (TMDB recommendations), top 6 with artwork.
                        val recKey = "recommendations:${loaded.id}"
                        relatedItems = TmdbResponseCache.get<List<com.vara.mediatracker.data.api.TMDBSearchItem>>(recKey) ?: run {
                            val fetched = (if (isMovie) tmdbClient.getMovieRecommendations(tmdbId) else tmdbClient.getTVRecommendations(tmdbId))
                                .getOrNull()?.results
                                ?.filter { it.poster_path != null }
                                ?.take(6)
                                .orEmpty()
                            fetched.also { TmdbResponseCache.put(recKey, it) }
                        }

                        if (!isMovie) {
                            isLoadingEpisodes = true
                            val seasons = details?.seasons ?: emptyList()
                            val currentLocalEps = db.mediaDao().getEpisodesListForShow(showIdKey)
                                .associateBy { "${it.seasonNumber}_${it.episodeNumber}" }

                            // Fetch the seasons concurrently (bounded) instead of one after the
                            // other: a 10-season show went from ~2.5s of episode skeleton to ~0.4s.
                            val seasonNumbers = seasons.map { it.season_number }.filter { it >= 1 }
                            val fetchedSeasons = coroutineScope {
                                val gate = Semaphore(5)
                                seasonNumbers.map { seasonNumber ->
                                    async {
                                        val seasonKey = TmdbResponseCache.seasonKey(loaded.id, seasonNumber)
                                        TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBSeasonDetailsResponse>(seasonKey)
                                            ?: gate.withPermit {
                                                tmdbClient.getSeasonDetails(tmdbId, seasonNumber).getOrNull()
                                                    ?.also { TmdbResponseCache.put(seasonKey, it) }
                                            }
                                    }
                                }.awaitAll()
                            }
                            val seasonDetailsByNumber = seasonNumbers.zip(fetchedSeasons).toMap()

                            val newEpisodesList = mutableListOf<EpisodeEntity>()
                            val seasonRows = mutableListOf<TVSeasonEntity>()
                            for (season in seasons) {
                                if (season.season_number < 1) continue
                                val seasonDetails = seasonDetailsByNumber[season.season_number]
                                val eps = seasonDetails?.episodes ?: emptyList()

                                seasonRows.add(
                                    TVSeasonEntity(
                                        showId = showIdKey,
                                        seasonNumber = season.season_number,
                                        episodeCount = season.episode_count?.takeIf { it > 0 } ?: eps.size,
                                        name = season.name,
                                        airDate = season.air_date
                                    )
                                )

                                for (ep in eps) {
                                    val key = "${ep.season_number}_${ep.episode_number}"
                                    val existing = currentLocalEps[key]
                                    val uniqueId = MediaIds.buildEpisodeUniqueId(showIdKey, ep.season_number, ep.episode_number)
                                    newEpisodesList.add(
                                        EpisodeEntity(
                                            uniqueId = existing?.uniqueId ?: uniqueId,
                                            showId = showIdKey,
                                            seasonNumber = ep.season_number,
                                            episodeNumber = ep.episode_number,
                                            name = ep.name ?: existing?.name ?: "",
                                            overview = ep.overview ?: existing?.overview ?: "",
                                            stillPath = ep.still_path ?: existing?.stillPath,
                                            isWatched = existing?.isWatched ?: false,
                                            watchedDate = existing?.watchedDate,
                                            airDate = ep.air_date ?: existing?.airDate,
                                            airTime = ep.air_time ?: existing?.airTime,
                                            airstamp = existing?.airstamp,
                                            runtime = ep.runtime ?: existing?.runtime,
                                            updatedAt = existing?.updatedAt
                                        )
                                    )
                                }
                            }
                            if (newEpisodesList.isNotEmpty()) db.mediaDao().upsertEpisodes(newEpisodesList)
                            if (seasonRows.isNotEmpty()) db.seasonDao().upsertSeasons(seasonRows)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isLoadingEpisodes = false
                    }
                }
            }
        }
        detailsLoading = false
    }

    val item = mediaItem
    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loadFailed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
                    Text("Couldn't load this title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("It may have been removed, or the library is still syncing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Text("Back") }
                        Button(onClick = { reloadTrigger++ }, shape = RoundedCornerShape(12.dp)) { Text("Retry") }
                    }
                }
            } else {
                LoadingIndicator()
            }
        }
        return
    }

    val isTV = item.type.contains("TV", ignoreCase = true)
    // Hoisted so each EpisodeCard doesn't call LocalDate.now() on every recomposition.
    val todayDate = rememberTodayDateString()
    val syncManager = remember { container.syncManager() }

    val vote = tmdbDetails?.vote_average ?: prefs.getCachedVoteAverage(item.id)?.toDouble()
    val episodeRuntimes = episodes.mapNotNull { it.runtime }.filter { it > 0 }.sorted()
    val medianRuntime = if (episodeRuntimes.isNotEmpty()) episodeRuntimes[episodeRuntimes.size / 2] else null
    val runtimeMin: Int? = if (isTV) {
        medianRuntime
            ?: tmdbDetails?.episode_run_time?.firstOrNull { it > 0 }
            ?: tmdbDetails?.runtime
            ?: prefs.getCachedRuntime(item.id)
    } else {
        tmdbDetails?.runtime ?: prefs.getCachedRuntime(item.id)
    }
    val timeLabel = runtimeMin?.takeIf { it > 0 }?.let {
        if (isTV) "${it}m" else if (it > 60) "${it / 60}h ${it % 60}m" else "${it}m"
    } ?: "NA"
    val ratingLabel = contentRating?.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: "NA"
    val tmdbNetName = if (isTV) {
        tmdbDetails?.networks?.firstOrNull()?.name ?: prefs.getCachedNetwork(item.id)
    } else {
        tmdbDetails?.production_companies?.firstOrNull()?.name ?: prefs.getCachedNetwork(item.id)
    }
    val networkInfo = remember(item, tmdbNetName) {
        if (!tmdbNetName.isNullOrBlank()) NetworkResolver.resolveByName(tmdbNetName, isTV = isTV) else NetworkResolver.resolve(item)
    }
    val displayNetwork = networkInfo.name.takeIf { it.isNotBlank() } ?: "NA"
    // The "Network logo in detail" setting was stored but never read. Honour it here: use
    // TMDB's network / studio logo when one exists, else fall back to the name.
    val networkLogoUrl = remember(tmdbDetails, prefs.detailNetworkLogo) {
        if (!prefs.detailNetworkLogo) {
            null
        } else {
            val path = if (isTV) {
                tmdbDetails?.networks?.firstOrNull()?.logo_path
            } else {
                tmdbDetails?.production_companies?.firstOrNull()?.logo_path
            }
            path?.takeIf { it.isNotBlank() }?.let { TMDBClient.buildLogoUrl(it) }
        }
    }
    val currentMatchedState = matchedState(item.state)

    // Taste (reaction) state for the tracking card.
    var tasteMenuOpen by remember { mutableStateOf(false) }
    val isLoved = item.taste.equals("Loved", ignoreCase = true) || item.taste.equals("Love", ignoreCase = true)
    val isLiked = item.taste.equals("Liked", ignoreCase = true) || item.taste.equals("Like", ignoreCase = true)
    val isDisliked = item.taste.equals("Disliked", ignoreCase = true)
    val tasteIcon = when {
        isLoved -> Icons.Default.Favorite
        isLiked -> Icons.Default.ThumbUp
        isDisliked -> Icons.Default.ThumbDown
        else -> Icons.Default.FavoriteBorder
    }
    val tasteTint = when {
        isLoved -> sem.loved
        isLiked -> sem.liked
        isDisliked -> sem.disliked
        else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    }

    var selectedTab by remember(mediaId, isTV) {
        mutableStateOf(if (isTV) DetailTab.Episodes else DetailTab.Info)
    }

    // Info-tab facts.
    val statusFact = tmdbDetails?.status?.takeIf { it.isNotBlank() }
    val countryFact = remember(tmdbDetails) {
        tmdbDetails?.production_countries?.mapNotNull { it.name }?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            ?: tmdbDetails?.origin_country?.joinToString(", ")?.takeIf { it.isNotBlank() }
    }
    val languageFact = remember(tmdbDetails) {
        tmdbDetails?.original_language?.takeIf { it.isNotBlank() }?.let { code ->
            runCatching { Locale(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() } ?: code.uppercase(Locale.US)
        }
    }
    val releaseFact = remember(tmdbDetails, item.releaseDate, isTV) {
        val raw = if (isTV) tmdbDetails?.first_air_date ?: item.releaseDate else tmdbDetails?.release_date ?: item.releaseDate
        if (raw.isNullOrBlank()) null else runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw.take(10))
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(parsed!!)
        }.getOrNull() ?: raw.take(4)
    }
    val productionFact = remember(tmdbDetails) {
        (tmdbDetails?.production_companies?.mapNotNull { it.name }.orEmpty() + tmdbDetails?.networks?.mapNotNull { it.name }.orEmpty())
            .distinct().take(4).joinToString(", ").takeIf { it.isNotBlank() }
    }
    val creatorsFact = remember(tmdbDetails) {
        tmdbDetails?.created_by?.mapNotNull { it.name }?.filter { it.isNotBlank() }?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() }
    }

    val nextUpInstant = remember(nextUpEpisode, item.network, item.studios, item.genres, item.timezone) {
        nextUpEpisode?.let {
            parseAirInstant(it.airDate, it.airTime, it.airstamp, item.timezone, AirTimeResolver.serviceHint(item.network, item.studios, item.genres))
        }
    }
    val nextUpCountdown = rememberCountdownText(nextUpInstant)

    fun markWatched(episode: EpisodeEntity, watched: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            TrackingLogic.markEpisodeWatched(
                container = container,
                episode = episode,
                watched = watched,
                onShowUpdated = { mediaItem = it }
            )
        }
    }

    fun markSeasonWatched(seasonNumber: Int, seasonEpisodes: List<EpisodeEntity>, watched: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            TrackingLogic.markSeasonWatched(
                container = container,
                showId = showIdKey,
                seasonNumber = seasonNumber,
                seasonEpisodes = seasonEpisodes,
                watched = watched,
                onShowUpdated = { mediaItem = it }
            )
        }
    }

    fun setTaste(next: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val now = isoNow()
            db.mediaDao().updateTaste(item.id, next, now)
            mediaItem = item.copy(taste = next, lastInteractionDate = now)
            // Taste-only: don't advance last_state_change_date (it drives the watch stats).
            syncManager.pushMediaUpdate(item.id, item.type, item.state, next, isStateChange = false)
        }
    }

    /** Open a related title if it's already tracked, otherwise add it from TMDB first. */
    fun openOrAddRelated(res: TMDBSearchItem) {
        val isMovie = res.media_type == "movie"
        val relId = (if (isMovie) "movie_" else "tv_") + res.id
        val relTitle = res.title ?: res.name ?: "Untitled"
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val existing = runCatching { db.mediaDao().getMediaById(relId) }.getOrNull()
            if (existing == null) {
                val now = isoNow()
                val newItem = MediaItemEntity(
                    id = relId,
                    title = relTitle,
                    overview = res.overview ?: "",
                    posterUrl = TMDBClient.buildPosterUrl(res.poster_path),
                    backdropUrl = TMDBClient.buildBackdropUrl(res.backdrop_path),
                    releaseDate = res.release_date ?: res.first_air_date,
                    type = if (isMovie) "Movie" else "TV Show",
                    state = "Wishlist",
                    lastInteractionDate = now,
                    lastStateChangeDate = now,
                    updatedAt = now
                )
                db.mediaDao().upsertMedia(newItem)
                syncManager.pushMediaUpdate(relId, newItem.type, "Wishlist", "None")
                AppToast.show("Added \"$relTitle\"")
            }
            onOpenMedia(relId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DetailTopBar(
            title = item.title,
            subtitle = if (isTV && episodes.isNotEmpty()) "Season $selectedSeason • ${episodes.size} Episodes" else null,
            onBack = onBack,
            onAddToCollection = { collectionPickerOpen = true },
            onShare = {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "Check out \"${item.title}\" (${item.type}) on MediaTracker!\n${item.overview.take(160)}")
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share \"${item.title}\""))
            },
            onOpenWeb = {
                val tmdbId = MediaIds.tmdbIdOf(item.id)
                if (tmdbId > 0) {
                    val path = if (isTV) "tv" else "movie"
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/$path/$tmdbId"))
                    runCatching { context.startActivity(webIntent) }
                } else {
                    AppToast.show("No TMDB ID available")
                }
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Hero: poster, genre + rating + release pill, wordmark logo.
                item {
                    // Backdrop, falling back to the poster when no backdrop art exists.
                    val backdropUrl = remember(item.backdropUrl, item.posterUrl, tmdbDetails?.backdrop_path, tmdbDetails?.poster_path) {
                        TMDBClient.buildBackdropUrl(tmdbDetails?.backdrop_path)
                            ?: item.backdropUrl
                            ?: TMDBClient.buildPosterUrl(tmdbDetails?.poster_path)
                            ?: item.posterUrl
                    }
                    val genres = tmdbDetails?.genres?.map { it.name }
                        ?: prefs.getCachedGenres(item.id)?.split(",")?.map { it.trim() }
                        ?: emptyList()
                    val releaseLabel = remember(tmdbDetails, item.releaseDate, isTV) {
                        val raw = if (isTV) tmdbDetails?.first_air_date ?: item.releaseDate
                        else tmdbDetails?.release_date ?: item.releaseDate
                        when {
                            raw.isNullOrBlank() -> null
                            isTV -> raw.take(4)
                            else -> runCatching {
                                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw.take(10))
                                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(parsed!!)
                            }.getOrNull() ?: raw.take(4)
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(256.dp)) {
                        if (backdropUrl != null) {
                            AsyncImage(model = backdropUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                        }
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.35f),
                                    0.35f to Color.Transparent,
                                    0.62f to Color.Black.copy(alpha = 0.55f),
                                    1f to Color.Black.copy(alpha = 0.92f)
                                )
                            )
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = ExpressiveShapes.AsymTag, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Text(
                                        text = (genres.firstOrNull() ?: if (isTV) "TV SHOW" else "MOVIE").uppercase(Locale.US),
                                        style = AppTextStyles.MonoLabel,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        maxLines = 1
                                    )
                                }
                                if (vote != null && vote > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = sem.gold, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(String.format(Locale.US, "%.1f", vote), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White)
                                    }
                                }
                                if (releaseLabel != null) {
                                    Surface(shape = ExpressiveShapes.AsymTag, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White) {
                                        Text(
                                            text = releaseLabel,
                                            style = AppTextStyles.MonoLabel,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            if (titleLogoUrl != null) {
                                AsyncImage(
                                    model = titleLogoUrl,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.height(44.dp).widthIn(max = 240.dp)
                                )
                            } else {
                                Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Metadata rail: rating / runtime / network, lifted out of the hero so the
                // artwork isn't fighting a text cluster in the bottom 80dp.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HeroMetaPill(text = ratingLabel)
                        HeroMetaPill(text = timeLabel, icon = Icons.Default.Schedule)
                        HeroMetaPill(text = displayNetwork, logoUrl = networkLogoUrl)
                    }
                }

                // Tracking chips: status + taste, compact and left-aligned.
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { statusPickerOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Row(modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(currentMatchedState.icon, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(currentMatchedState.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change status", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }

                        Box {
                            Surface(
                                onClick = { tasteMenuOpen = true },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(tasteIcon, contentDescription = "Taste", tint = tasteTint, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isLoved -> "Loved"
                                            isLiked -> "Liked"
                                            isDisliked -> "Disliked"
                                            else -> "Rate"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            if (tasteMenuOpen) {
                                Popup(
                                    alignment = Alignment.TopEnd,
                                    offset = IntOffset(0, with(LocalDensity.current) { 46.dp.roundToPx() }),
                                    onDismissRequest = { tasteMenuOpen = false },
                                    properties = PopupProperties(focusable = true)
                                ) {
                                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 10.dp) {
                                        Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            ReactionButton(Icons.Default.Favorite, "Loved", isLoved, sem.loved) {
                                                tasteMenuOpen = false
                                                setTaste(if (isLoved) "None" else "Loved")
                                            }
                                            ReactionButton(Icons.Default.ThumbUp, "Liked", isLiked, sem.liked) {
                                                tasteMenuOpen = false
                                                setTaste(if (isLiked) "None" else "Liked")
                                            }
                                            ReactionButton(Icons.Default.ThumbDown, "Disliked", isDisliked, sem.disliked) {
                                                tasteMenuOpen = false
                                                setTaste(if (isDisliked) "None" else "Disliked")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Movie: primary watch toggle (always visible, outside the tabs).
                if (!isTV) {
                    item {
                        val completed = item.state.equals("Completed", ignoreCase = true)
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    TrackingLogic.toggleMovieWatched(
                                        container = container,
                                        movie = item,
                                        onMovieUpdated = { mediaItem = it }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(if (completed) Icons.Default.Refresh else Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (completed) "Mark In Progress" else "Mark Watched", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Tabs: Episodes / Info / Related.
                item {
                    val tabs = if (isTV) listOf(DetailTab.Episodes, DetailTab.Info, DetailTab.Related)
                               else listOf(DetailTab.Info, DetailTab.Related)
                    ExpressiveSegmentedControl(
                        items = tabs,
                        selectedItem = selectedTab,
                        onItemSelected = { selectedTab = it },
                        labelProvider = { it.label },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // ----- Episodes (TV only) -----
                if (isTV && selectedTab == DetailTab.Episodes) {
                    if (seasonsList.size > 1) {
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(seasonsList, key = { it }) { sNum ->
                                    val sEps = episodes.filter { it.seasonNumber == sNum }
                                    SeasonPill(
                                        seasonNumber = sNum,
                                        watched = sEps.count { it.isWatched },
                                        total = sEps.size,
                                        selected = sNum == selectedSeason,
                                        onClick = { selectedSeason = sNum }
                                    )
                                }
                            }
                        }
                    }

                    if (currentSeasonEpisodes.isNotEmpty()) {
                        val seasonWatched = currentSeasonEpisodes.count { it.isWatched }
                        val seasonTotal = currentSeasonEpisodes.size
                        val allSeasonWatched = seasonWatched == seasonTotal && seasonTotal > 0
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "SEASON $selectedSeason PROGRESS",
                                            style = AppTextStyles.MonoLabel,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        ProgressLine(
                                            fraction = if (seasonTotal > 0) seasonWatched.toFloat() / seasonTotal else 0f,
                                            label = "$seasonWatched/$seasonTotal"
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Surface(
                                        onClick = {
                                            markSeasonWatched(selectedSeason, currentSeasonEpisodes, !allSeasonWatched)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (allSeasonWatched) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (allSeasonWatched) Icons.Default.Refresh else Icons.Default.DoneAll,
                                                contentDescription = null,
                                                tint = if (allSeasonWatched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = if (allSeasonWatched) "Unwatch" else "Watch All",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (allSeasonWatched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (currentSeasonEpisodes.isEmpty()) {
                        if (isLoadingEpisodes || detailsLoading) {
                            item { EpisodeSkeletonList() }
                        } else {
                            item {
                                Text(
                                    text = "No episodes available for Season $selectedSeason.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    } else {
                        items(currentSeasonEpisodes, key = { it.uniqueId }) { ep ->
                            // Swipe right = watched, left = un-watch. Idempotent, so swiping the
                            // same way twice is harmless; the opposite swipe is the undo.
                            SwipeActionRow(
                                rightIcon = Icons.Default.Check,
                                rightTint = sem.success,
                                onSwipeRight = { if (!ep.isWatched) markWatched(ep, true) },
                                leftIcon = Icons.Default.Refresh,
                                leftTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                onSwipeLeft = { if (ep.isWatched) markWatched(ep, false) }
                            ) {
                                EpisodeCard(
                                    episode = ep,
                                    serviceHint = AirTimeResolver.serviceHint(item.network, item.studios, item.genres),
                                    timezone = item.timezone,
                                    today = todayDate,
                                    onToggleWatched = { markWatched(ep, it) }
                                )
                            }
                        }
                    }
                }

                // ----- Info -----
                if (selectedTab == DetailTab.Info) {
                    // Synopsis (top).
                    if (item.overview.isNotBlank()) {
                        item {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            var hasOverflow by remember(item.id) { mutableStateOf(false) }
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                SectionHeader(title = "Synopsis")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = item.overview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 21.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (!expanded) hasOverflow = it.hasVisualOverflow }
                                )
                                if (hasOverflow || expanded) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (expanded) "See less" else "See more",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Genres + key-value facts.
                    val infoGenres = tmdbDetails?.genres?.map { it.name }
                        ?: prefs.getCachedGenres(item.id)?.split(",")?.map { it.trim() }
                        ?: emptyList()
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            if (infoGenres.isNotEmpty()) FactRow("Genres", infoGenres.joinToString(", "))
                            releaseFact?.let { FactRow(if (isTV) "First aired" else "Released", it) }
                            FactRow("Runtime", timeLabel)
                            statusFact?.let { FactRow("Status", it) }
                            countryFact?.let { FactRow("Country", it) }
                            languageFact?.let { FactRow("Language", it) }
                            if (isTV) {
                                creatorsFact?.let { FactRow("Created by", it) }
                            } else {
                                directorName?.takeIf { it.isNotBlank() }?.let { FactRow("Directed by", it) }
                            }
                            productionFact?.let { FactRow("Production", it) }
                        }
                    }

                    if (castMembers.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SectionHeader(title = "Cast & Characters", modifier = Modifier.weight(1f))
                                    Text("Tap for bio", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.foundation.lazy.LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(castMembers, key = { it.id }) { actor ->
                                        CastMemberCard(actor = actor, onClick = { selectedActor = actor })
                                    }
                                }
                            }
                        }
                    } else if (detailsLoading) {
                        item {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                SectionHeader(title = "Cast & Characters", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                CastSkeletonRow()
                            }
                        }
                    }
                }

                // ----- Related -----
                if (selectedTab == DetailTab.Related) {
                    item {
                        if (relatedItems.isEmpty()) {
                            Text(
                                text = if (detailsLoading) "Loading recommendations…" else "No related titles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        } else {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                relatedItems.chunked(3).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowItems.forEach { rel ->
                                            RelatedPoster(
                                                item = rel,
                                                onClick = { openOrAddRelated(rel) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Up Next action (wide).
            if (isTV && nextUpEpisode != null) {
                // An unaired next episode must never be markable in one tap — the pill turns
                // informational (clock, "AIRING SOON") and its click is disabled.
                val nextUpAired = nextUpInstant == null || nextUpInstant.time <= System.currentTimeMillis()
                FloatingUpNext(
                    episode = nextUpEpisode,
                    countdown = nextUpCountdown,
                    aired = nextUpAired,
                    onClick = { markWatched(nextUpEpisode, true) },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)
                )
            }
        }
    }

    if (statusPickerOpen) {
        StatusPickerSheet(
            states = TrackingStates,
            current = currentMatchedState,
            onSelect = { state ->
                statusPickerOpen = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val now = isoNow()
                    val wasCompleted = item.state.equals("Completed", ignoreCase = true)
                    val isCompleted = state.raw.equals("Completed", ignoreCase = true)
                    db.mediaDao().updateState(item.id, state.raw, now)
                    mediaItem = item.copy(state = state.raw, lastInteractionDate = now)
                    syncManager.pushMediaUpdate(item.id, item.type, state.raw, item.taste)

                    // Watch history lives outside the state column, so a status change has to
                    // log it too (this path bypasses TrackingLogic's toggle).
                    val historyDao = container.database.watchHistoryDao()
                    if (isCompleted && !wasCompleted) {
                        if (!isTV) {
                            WatchHistoryRecorder.recordWatch(
                                dao = historyDao,
                                mediaId = item.id,
                                kind = WatchHistoryRecorder.KIND_MOVIE,
                                episodeId = null,
                                watchedAt = now,
                                runtimeMinutes = null
                            )
                        }
                        WatchHistoryRecorder.completeCycle(historyDao, item.id, now)
                    } else if (!isCompleted && wasCompleted) {
                        if (!isTV) WatchHistoryRecorder.voidWatch(historyDao, item.id, null, now)
                        WatchHistoryRecorder.reopenCycle(historyDao, item.id, now)
                    }

                    AppToast.show("Marked as ${state.label}")
                }
            },
            onRemove = {
                statusPickerOpen = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    db.mediaDao().softDelete(item.id, isoNow())
                    syncManager.pushMediaDeletes(listOf(item))
                    AppToast.show("Removed \"${item.title}\"")
                }
                // Close the sheet first, then leave the detail — dismissing in the same frame
                // as the content change is the jitter the rest of the app avoids.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onBack() }, 250)
            },
            onDismiss = { statusPickerOpen = false }
        )
    }

    if (collectionPickerOpen) {
        DetailCollectionPickerSheet(
            container = container,
            onPick = { collectionId ->
                collectionPickerOpen = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    val rows = listOf(CollectionItemEntity(collectionId, item.id, false, isoNow()))
                    container.database.collectionDao().upsertCollectionItems(rows)
                    container.syncManager().pushCollectionItems(rows)
                    AppToast.show("Added to collection")
                }
            },
            onDismiss = { collectionPickerOpen = false }
        )
    }

    selectedActor?.let { actor ->
        ActorBioSheet(actor = actor, tmdbClient = container.tmdbClient(), onDismiss = { selectedActor = null })
    }
}

/** Sticky top bar: back, name + episode count, and share/web/collection buttons. */
@Composable
private fun DetailTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onAddToCollection: () -> Unit,
    onShare: () -> Unit,
    onOpenWeb: () -> Unit
) {
    var actionsOpen by remember { mutableStateOf(false) }
    // Close this sheet first, then run the action — several of them open another sheet, and
    // dismissing and presenting in the same frame makes both stutter.
    fun closeThen(action: () -> Unit) {
        actionsOpen = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, 220)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopBarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)

        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle.uppercase(Locale.US), style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }

        // One overflow instead of four 38dp buttons: four icon buttons cannot be 48dp touch
        // targets and still leave a readable centred title on a 360dp screen.
        TopBarIconButton(Icons.Default.MoreVert, "More actions", onClick = { actionsOpen = true })
    }

    if (actionsOpen) {
        ModalBottomSheet(onDismissRequest = { actionsOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetActionRow(Icons.Default.Bookmarks, "Add to collection", "Put \"$title\" in a collection") { closeThen(onAddToCollection) }
                SheetActionRow(Icons.Default.Share, "Share", "Share this title") { closeThen(onShare) }
                SheetActionRow(Icons.Default.Language, "Open on TMDB", "View the full page on themoviedb.org") { closeThen(onOpenWeb) }
            }
        }
    }
}

/** Top-bar action: compact 38dp visual with a full 48dp touch target. */
@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** One row in the top bar's actions sheet — generous height for thumb accuracy. */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    supporting: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Floating Up Next pill — same shape/border as an episode pill, elevated. Tap to track. */
@Composable
private fun FloatingUpNext(
    episode: EpisodeEntity,
    countdown: String?,
    aired: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = aired,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (aired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (aired) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (aired) Icons.Default.Check else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    if (aired) "UP NEXT" else "AIRING SOON",
                    style = AppTextStyles.MonoLabel,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "S${episode.seasonNumber}E${episode.episodeNumber} · ${episode.name.ifBlank { "Episode ${episode.episodeNumber}" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (countdown != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(countdown, style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Episode pill: bordered card with a check toggle, code + runtime, name, aired date, status. */
@Composable
fun EpisodeCard(
    episode: EpisodeEntity,
    onToggleWatched: (Boolean) -> Unit,
    serviceHint: String = "",
    timezone: String? = null,
    today: String = java.time.LocalDate.now().toString()
) {
    // Resolving the air instant walks the streaming-service rules; do it once per episode,
    // not on every recomposition.
    val airLabel = remember(episode.airDate, episode.airTime, episode.airstamp, serviceHint, timezone) {
        episodeAirLabel(episode, serviceHint, timezone)
    }
    val aired = !episode.airDate.isNullOrBlank() && episode.airDate!!.take(10) <= today

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (episode.isWatched) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (episode.isWatched) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (episode.isWatched) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (episode.isWatched) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .size(32.dp)
                    .semantics {
                        contentDescription = if (episode.isWatched) "Mark episode unwatched" else "Mark episode watched"
                        role = Role.Checkbox
                        toggleableState = if (episode.isWatched) ToggleableState.On else ToggleableState.Off
                    }
                    .springClickable { onToggleWatched(!episode.isWatched) }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (episode.isWatched) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S${episode.seasonNumber}E${episode.episodeNumber}", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    if (episode.runtime != null && episode.runtime > 0) {
                        Text("${episode.runtime}m", style = AppTextStyles.MonoValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = episode.name.ifBlank { "Episode ${episode.episodeNumber}" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (episode.isWatched) TextDecoration.LineThrough else null,
                    color = if (episode.isWatched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (airLabel != null) "Aired: $airLabel" else "Aired: TBA",
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        episode.isWatched -> StatusChip(label = "Watched", tone = ChipTone.Positive)
                        !aired -> StatusChip(label = "Locked", tone = ChipTone.Muted)
                        else -> StatusChip(label = "Upcoming", tone = ChipTone.Warning)
                    }
                }
            }
        }
    }
}

/** Episode airing in the device's local zone, with the year. */
private val EPISODE_AIR_FORMAT =
    DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.getDefault())

private fun episodeAirLabel(
    episode: EpisodeEntity,
    serviceHint: String,
    timezone: String?
): String? {
    val date = episode.airDate?.take(10) ?: return null
    val instant = AirTimeResolver.resolveAirInstant(date, episode.airTime, episode.airstamp, timezone, serviceHint)
        ?: return date
    return EPISODE_AIR_FORMAT.format(instant.toInstant().atZone(ZoneId.systemDefault()))
}

/** One reaction in the taste popover. */
@Composable
private fun ReactionButton(icon: ImageVector, label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.24f) else Color.Transparent,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Small metadata pill shown over the hero image. */
@Composable
private fun HeroMetaPill(
    text: String,
    icon: ImageVector? = null,
    overImage: Boolean = false,
    logoUrl: String? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (overImage) Color.Black.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (logoUrl != null) {
                // Network/studio wordmark instead of the name (see the "Network logo in
                // detail" setting).
                AsyncImage(
                    model = logoUrl,
                    contentDescription = text,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(14.dp).widthIn(max = 56.dp)
                )
            } else {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(text, style = AppTextStyles.MonoLabel, maxLines = 1)
            }
        }
    }
}

/** Breathable season chip with a watched/total badge. */
@Composable
private fun SeasonPill(
    seasonNumber: Int,
    watched: Int,
    total: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (seasonNumber == 0) "Specials" else "Season $seasonNumber", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = "$watched/$total",
                    style = AppTextStyles.MonoLabel,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** A label/value fact row for the Info tab. */
@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = AppTextStyles.MonoLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Related title poster (same 2:3 size as the library grid). */
@Composable
private fun RelatedPoster(
    item: com.vara.mediatracker.data.api.TMDBSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = ExpressiveShapes.AsymPoster,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.aspectRatio(2f / 3f)
    ) {
        val poster = TMDBClient.buildPosterUrl(item.poster_path)
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = item.title ?: item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Cast carousel item. */
@Composable
private fun CastMemberCard(
    actor: com.vara.mediatracker.data.api.TMDBCastMember,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp).clip(ExpressiveShapes.SquircleMd).clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            if (actor.profile_path != null) {
                AsyncImage(
                    model = TMDBClient.buildProfileUrl(actor.profile_path),
                    contentDescription = actor.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(ExpressiveShapes.AsymPoster).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), ExpressiveShapes.AsymPoster)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().clip(ExpressiveShapes.AsymPoster).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(11.dp)) }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(actor.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!actor.character.isNullOrBlank()) {
            Text(actor.character, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Cast bio sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActorBioSheet(
    actor: com.vara.mediatracker.data.api.TMDBCastMember,
    tmdbClient: TMDBClient,
    onDismiss: () -> Unit
) {
    var bio by remember(actor.id) { mutableStateOf<String?>(null) }
    var works by remember(actor.id) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(actor.id) { mutableStateOf(true) }

    LaunchedEffect(actor.id) {
        loading = true
        val detailsKey = "person:${actor.id}"
        val details = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBPersonDetailsResponse>(detailsKey)
            ?: tmdbClient.getPersonDetails(actor.id).getOrNull()?.also { TmdbResponseCache.put(detailsKey, it) }
        bio = details?.biography?.takeIf { it.isNotBlank() }

        val creditsKey = "personcredits:${actor.id}"
        val credits = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBCombinedCreditsResponse>(creditsKey)
            ?: tmdbClient.getPersonCombinedCredits(actor.id).getOrNull()?.also { TmdbResponseCache.put(creditsKey, it) }
        works = credits?.cast.orEmpty()
            .mapNotNull { c -> (c.title ?: c.name)?.let { it to c.id } }
            .distinctBy { it.second }
            .take(6)
            .map { it.first }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actor.profile_path != null) {
                    AsyncImage(model = TMDBClient.buildProfileUrl(actor.profile_path), contentDescription = actor.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(ExpressiveShapes.AsymPoster))
                } else {
                    Box(modifier = Modifier.size(64.dp).clip(ExpressiveShapes.AsymPoster).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text((actor.character?.takeIf { it.isNotBlank() }?.let { "As $it" } ?: "Cast").uppercase(Locale.US), style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
                    Text(actor.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            when {
                loading -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                bio != null -> Text(bio!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (works.isNotEmpty()) {
                Text("KNOWN WORKS (TMDB)", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    works.forEach { title ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Animated shimmer brush. */
@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return Brush.linearGradient(colors = listOf(base, highlight, base), start = Offset(x * 400f, 0f), end = Offset((x + 1f) * 400f, 0f))
}

@Composable
private fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(modifier.clip(shape).background(shimmerBrush()))
}

@Composable
private fun CastSkeletonRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
                SkeletonBox(Modifier.size(64.dp), ExpressiveShapes.AsymPoster)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(10.dp), RoundedCornerShape(5.dp))
                Spacer(modifier = Modifier.height(5.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(9.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

@Composable
private fun EpisodeSkeletonList(count: Int = 5) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(32.dp), RoundedCornerShape(10.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.3f).height(10.dp), RoundedCornerShape(5.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.75f).height(12.dp), RoundedCornerShape(5.dp))
                }
            }
        }
    }
}

/** Rich status picker sheet: drag handle, header with close, and described option rows. */
@Composable
private fun StatusPickerSheet(
    states: List<TrackingState>,
    current: TrackingState,
    onSelect: (TrackingState) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CHANGE STATUS", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Select tracking state",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                states.forEach { state ->
                    val isSelected = state.raw == current.raw
                    val tint = state.tint ?: MaterialTheme.colorScheme.primary
                    Surface(
                        onClick = { onSelect(state) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.18f), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(state.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = state.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Destructive: tombstones the title. It is a soft-delete, so it propagates to
            // the other device and can be restored from the cloud.
            Surface(
                onClick = onRemove,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.18f), modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remove from library",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Hides this title everywhere and syncs to your other devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Bottom sheet listing collections so the open title can be added to one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailCollectionPickerSheet(
    container: AppContainer,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dao = remember(container) { container.database.collectionDao() }
    val collections by dao.observeCollections().collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("ADD TO COLLECTION", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(10.dp))
            if (collections.isEmpty()) {
                Text(
                    "No collections yet — create one in the Hub → Collections.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    collections.forEach { collection ->
                        Surface(
                            onClick = { onPick(collection.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    collection.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
