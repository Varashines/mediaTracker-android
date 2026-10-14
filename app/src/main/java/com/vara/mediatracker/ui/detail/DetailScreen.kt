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
import com.vara.mediatracker.data.api.TmdbResponseCache
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.ChipTone
import com.vara.mediatracker.ui.common.ProgressLine
import com.vara.mediatracker.ui.common.SectionHeader
import com.vara.mediatracker.ui.common.StatusChip
import com.vara.mediatracker.ui.common.parseAirInstant
import com.vara.mediatracker.ui.common.rememberCountdownText
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
    onBack: () -> Unit
) {
    val db = container.database
    val prefs = container.preferences
    val scope = rememberCoroutineScope()
    val sem = LocalSemanticColors.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    var mediaItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    var statusPickerOpen by remember { mutableStateOf(false) }
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

                        if (!isMovie) {
                            isLoadingEpisodes = true
                            val seasons = details?.seasons ?: emptyList()
                            val currentLocalEps = db.mediaDao().getEpisodesListForShow(showIdKey)
                                .associateBy { "${it.seasonNumber}_${it.episodeNumber}" }

                            val newEpisodesList = mutableListOf<EpisodeEntity>()
                            val seasonRows = mutableListOf<TVSeasonEntity>()
                            for (season in seasons) {
                                if (season.season_number < 1) continue
                                val seasonKey = TmdbResponseCache.seasonKey(loaded.id, season.season_number)
                                val seasonDetails = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBSeasonDetailsResponse>(seasonKey)
                                    ?: tmdbClient.getSeasonDetails(tmdbId, season.season_number).getOrNull()
                                        ?.also { TmdbResponseCache.put(seasonKey, it) }
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

    val nextUpInstant = remember(nextUpEpisode, item.network, item.studios, item.genres, item.timezone) {
        nextUpEpisode?.let {
            parseAirInstant(it.airDate, it.airTime, it.airstamp, item.timezone, AirTimeResolver.serviceHint(item.network, item.studios, item.genres))
        }
    }
    val nextUpCountdown = rememberCountdownText(nextUpInstant)

    fun markWatched(episode: EpisodeEntity, watched: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            db.mediaDao().setEpisodeWatched(episode.uniqueId, watched, if (watched) isoNow() else null)
            syncManager.pushEpisodeWatch(MediaIds.tmdbIdOf(episode.showId).toString(), episode.seasonNumber, episode.episodeNumber, watched)
        }
    }

    fun setTaste(next: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            db.mediaDao().updateTaste(item.id, next)
            mediaItem = item.copy(taste = next)
            syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
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
            onCopyTitle = {
                clipboard.setText(AnnotatedString(item.title))
                AppToast.show("Title copied")
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Hero: poster, genre + rating + release pill, wordmark logo.
                item {
                    val backdropUrl = remember(item.backdropUrl, tmdbDetails?.backdrop_path) {
                        TMDBClient.buildBackdropUrl(tmdbDetails?.backdrop_path) ?: item.backdropUrl
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

                            // Small metadata pills, over the image.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                HeroMetaPill(text = ratingLabel)
                                HeroMetaPill(text = timeLabel, icon = Icons.Default.Schedule)
                                HeroMetaPill(text = displayNetwork)
                            }
                        }
                    }
                }

                // Tracking card: status trigger + taste reaction button.
                item {
                    Surface(
                        shape = ExpressiveShapes.AsymCard,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { statusPickerOpen = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(currentMatchedState.icon, contentDescription = null, modifier = Modifier.size(19.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("TRACKING STATUS", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                    Text(currentMatchedState.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change status", tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Box {
                                Surface(
                                    onClick = { tasteMenuOpen = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(tasteIcon, contentDescription = "Taste", tint = tasteTint, modifier = Modifier.size(20.dp))
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
                                                ReactionButton(Icons.Default.Favorite, isLoved, sem.loved) {
                                                    tasteMenuOpen = false
                                                    setTaste(if (isLoved) "None" else "Loved")
                                                }
                                                ReactionButton(Icons.Default.ThumbUp, isLiked, sem.liked) {
                                                    tasteMenuOpen = false
                                                    setTaste(if (isLiked) "None" else "Liked")
                                                }
                                                ReactionButton(Icons.Default.ThumbDown, isDisliked, sem.disliked) {
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
                }

                if (isTV) {
                    item {
                        SectionHeader(title = "Episodes", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }

                    if (seasonsList.size > 1) {
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(seasonsList) { sNum ->
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

                    // Season progress — only while the selected season is unfinished.
                    if (currentSeasonEpisodes.isNotEmpty()) {
                        val seasonWatched = currentSeasonEpisodes.count { it.isWatched }
                        val seasonTotal = currentSeasonEpisodes.size
                        if (seasonWatched < seasonTotal) {
                            item {
                                ProgressLine(
                                    fraction = seasonWatched.toFloat() / seasonTotal,
                                    label = "$seasonWatched/$seasonTotal",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
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
                            EpisodeCard(
                                episode = ep,
                                serviceHint = AirTimeResolver.serviceHint(item.network, item.studios, item.genres),
                                timezone = item.timezone,
                                onToggleWatched = { markWatched(ep, it) }
                            )
                        }
                    }
                } else {
                    item {
                        val completed = item.state.equals("Completed", ignoreCase = true)
                        Button(
                            onClick = {
                                val next = if (completed) "Active" else "Completed"
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    db.mediaDao().updateState(item.id, next, isoNow())
                                    mediaItem = item.copy(state = next)
                                    syncManager.pushMediaUpdate(item.id, item.type, next, item.taste)
                                    AppToast.show(if (completed) "Marked as watching" else "Marked as watched")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(if (completed) Icons.Default.Refresh else Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (completed) "Mark Unwatched" else "Mark Watched", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Synopsis, clamped.
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

                // Cast.
                if (castMembers.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
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
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            SectionHeader(title = "Cast & Characters", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            CastSkeletonRow()
                        }
                    }
                }
            }

            // Floating Up Next action (wide).
            if (isTV && nextUpEpisode != null) {
                FloatingUpNext(
                    episode = nextUpEpisode,
                    countdown = nextUpCountdown,
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
                    db.mediaDao().updateState(item.id, state.raw, isoNow())
                    mediaItem = item.copy(state = state.raw)
                    syncManager.pushMediaUpdate(item.id, item.type, state.raw, item.taste)
                    AppToast.show("Marked as ${state.label}")
                }
            },
            onDismiss = { statusPickerOpen = false }
        )
    }

    selectedActor?.let { actor ->
        ActorBioSheet(actor = actor, tmdbClient = container.tmdbClient(), onDismiss = { selectedActor = null })
    }
}

/** Sticky top bar: back, name + episode count, and a copy-title button. */
@Composable
private fun DetailTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onCopyTitle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(onClick = onBack, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle.uppercase(Locale.US), style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }

        Surface(onClick = onCopyTitle, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy title", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Floating Up Next pill — same shape/border as an episode pill, elevated. Tap to track. */
@Composable
private fun FloatingUpNext(
    episode: EpisodeEntity,
    countdown: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
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
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("UP NEXT", style = AppTextStyles.MonoLabel, color = MaterialTheme.colorScheme.primary)
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
    timezone: String? = null
) {
    val airLabel = episodeAirLabel(episode, serviceHint, timezone)
    val today = java.time.LocalDate.now().toString()
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
private fun episodeAirLabel(
    episode: EpisodeEntity,
    serviceHint: String,
    timezone: String?
): String? {
    val date = episode.airDate?.take(10) ?: return null
    val instant = AirTimeResolver.resolveAirInstant(date, episode.airTime, episode.airstamp, timezone, serviceHint)
        ?: return date
    return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(instant)
}

/** One reaction in the taste popover. */
@Composable
private fun ReactionButton(icon: ImageVector, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.24f) else Color.Transparent,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Small metadata pill shown over the hero image. */
@Composable
private fun HeroMetaPill(text: String, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text, style = AppTextStyles.MonoLabel, maxLines = 1)
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
        }
    }
}
