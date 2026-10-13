package com.vara.mediatracker.ui.detail

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaIds
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.TVSeasonEntity
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import kotlinx.coroutines.launch
import com.vara.mediatracker.data.api.TMDBClient
import com.vara.mediatracker.data.api.TmdbResponseCache
import com.vara.mediatracker.ui.common.ChipTone
import com.vara.mediatracker.ui.common.FlatCard
import com.vara.mediatracker.ui.common.ProgressLine
import com.vara.mediatracker.ui.common.SectionHeader
import com.vara.mediatracker.ui.theme.AppTextStyles
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.StatusChip
import com.vara.mediatracker.ui.common.ExpressiveToggleGroup
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    mediaId: String,
    container: AppContainer,
    onBack: () -> Unit
) {
    val db = container.database
    val prefs = container.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalSemanticColors.current
    var mediaItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    var markMenuExpanded by remember { mutableStateOf(false) }
    var statusPickerOpen by remember { mutableStateOf(false) }
    // TMDB title-treatment logo (the stylized wordmark), shown in the hero when available.
    var titleLogoUrl by remember { mutableStateOf<String?>(null) }
    val showIdKey = MediaIds.tvShowKey(mediaId)
    val episodes by db.mediaDao().getEpisodesForShow(showIdKey)
        .collectAsState(initial = emptyList())
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    // True until the TMDB enrichment pass (details / credits / logo / seasons) finishes;
    // drives the skeletons for sections that do not render immediately.
    var detailsLoading by remember { mutableStateOf(true) }

    val seasonsList = remember(episodes) {
        episodes.map { it.seasonNumber }.distinct().sorted()
    }
    var selectedSeason by remember(seasonsList) {
        mutableStateOf(seasonsList.firstOrNull { s -> episodes.any { it.seasonNumber == s && !it.isWatched } } ?: seasonsList.firstOrNull() ?: 1)
    }
    val currentSeasonEpisodes = remember(episodes, selectedSeason) {
        episodes.filter { it.seasonNumber == selectedSeason }
    }
    val nextUpEpisode = remember(episodes) {
        episodes.firstOrNull { !it.isWatched }
    }

    var tmdbDetails by remember { mutableStateOf<com.vara.mediatracker.data.api.TMDBDetailsResponse?>(null) }
    var castMembers by remember { mutableStateOf<List<com.vara.mediatracker.data.api.TMDBCastMember>>(emptyList()) }
    // US content rating (TV-MA / R / PG-13), fetched once and cached with the details.
    var contentRating by remember { mutableStateOf<String?>(null) }
    // Cast member whose bio sheet is open (null = closed).
    var selectedActor by remember { mutableStateOf<com.vara.mediatracker.data.api.TMDBCastMember?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(mediaId, reloadTrigger) {
        loadFailed = false
        val item = runCatching { db.mediaDao().getMediaById(mediaId) }.getOrNull()
        if (item == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        mediaItem = item

        if (prefs.tmdbApiKey.isNotBlank()) {
            val tmdbId = MediaIds.tmdbIdOf(item.id)
            if (tmdbId > 0) {
                kotlinx.coroutines.Dispatchers.IO.let { ioDispatcher ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        try {
                            val tmdbClient = container.tmdbClient()
                            val isMovie = item.type.contains("Movie", ignoreCase = true)
                            
                            // Fetch Details (Genres, Ratings, Runtime) — cached for a few
                            // hours so re-opening a title does not re-hit TMDB.
                            val detailsKey = TmdbResponseCache.detailsKey(item.id)
                            val details = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBDetailsResponse>(detailsKey)
                                ?: (if (isMovie) tmdbClient.getMovieDetails(tmdbId) else tmdbClient.getTVDetails(tmdbId))
                                    .getOrNull()?.also { TmdbResponseCache.put(detailsKey, it) }
                            tmdbDetails = details

                            // Persist backdrop and poster if updated from TMDB. The
                            // backdrop is fetched at the backdrop size (w1280), not the
                            // poster size — a w780 backdrop upscaled to a full-bleed hero
                            // is visibly soft.
                            val tmdbBackdrop = TMDBClient.buildBackdropUrl(details?.backdrop_path)
                            val tmdbPoster = TMDBClient.buildPosterUrl(details?.poster_path)
                            if (tmdbBackdrop != null && item.backdropUrl != tmdbBackdrop) {
                                val updatedItem = item.copy(
                                    backdropUrl = tmdbBackdrop,
                                    posterUrl = item.posterUrl ?: tmdbPoster
                                )
                                db.mediaDao().upsertMedia(listOf(updatedItem))
                                mediaItem = updatedItem
                            }

                            // Cache resolved network or production company
                            val net = if (isMovie) {
                                details?.production_companies?.firstOrNull()?.name
                            } else {
                                details?.networks?.firstOrNull()?.name
                            }
                            if (!net.isNullOrBlank()) {
                                prefs.setCachedNetwork(item.id, net)
                                // Persist the network on the entity too so the badge
                                // engine can apply the streaming drop-time rules without
                                // reading preferences.
                                val current = mediaItem ?: item
                                if (current.network != net) {
                                    val withNet = current.copy(network = net)
                                    db.mediaDao().upsertMedia(listOf(withNet))
                                    mediaItem = withNet
                                }
                            }

                            // Cache runtime, genres, and vote average
                            val rt = if (isMovie) {
                                details?.runtime
                            } else {
                                details?.episode_run_time?.firstOrNull { it > 0 } ?: details?.runtime
                            }
                            if (rt != null && rt > 0) {
                                prefs.setCachedRuntime(item.id, rt)
                            }

                            val genreStr = details?.genres?.map { it.name }?.joinToString(", ")
                            if (!genreStr.isNullOrBlank()) {
                                prefs.setCachedGenres(item.id, genreStr)
                            }

                            val voteAvg = details?.vote_average
                            if (voteAvg != null && voteAvg > 0) {
                                prefs.setCachedVoteAverage(item.id, voteAvg.toFloat())
                            }

                            // US content rating (TV-MA / R / PG-13). Same TTL cache as the
                            // rest of the detail payload so re-opening costs nothing.
                            val ratingKey = "contentrating:${item.id}"
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

                            // Fetch Credits (Cast), cached alongside the details.
                            val creditsKey = TmdbResponseCache.creditsKey(item.id)
                            val credits = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBCreditsResponse>(creditsKey)
                                ?: (if (isMovie) tmdbClient.getMovieCredits(tmdbId) else tmdbClient.getTVCredits(tmdbId))
                                    .getOrNull()?.also { TmdbResponseCache.put(creditsKey, it) }
                            castMembers = credits?.cast?.take(15) ?: emptyList()

                            // Title-treatment logo (the stylized wordmark), cached by id.
                            val logoKey = "titlelogo:${item.id}"
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

                            // If it's a TV show, fetch full episodes and episode names from TMDB
                            if (!isMovie) {
                                isLoadingEpisodes = true
                                val seasons = details?.seasons ?: emptyList()
                                val currentLocalEps = db.mediaDao().getEpisodesListForShow(showIdKey).associateBy { 
                                    "${it.seasonNumber}_${it.episodeNumber}" 
                                }

                                val newEpisodesList = mutableListOf<EpisodeEntity>()
                                val seasonRows = mutableListOf<TVSeasonEntity>()
                                for (season in seasons) {
                                    if (season.season_number < 1) continue
                                    val seasonKey = TmdbResponseCache.seasonKey(item.id, season.season_number)
                                    val seasonDetails = TmdbResponseCache.get<com.vara.mediatracker.data.api.TMDBSeasonDetailsResponse>(seasonKey)
                                        ?: tmdbClient.getSeasonDetails(tmdbId, season.season_number).getOrNull()
                                            ?.also { TmdbResponseCache.put(seasonKey, it) }
                                    val eps = seasonDetails?.episodes ?: emptyList()

                                    // Recorded from the season payload we already fetched,
                                    // so this costs no extra request. BadgeEngine's FINALE
                                    // rule reads episodeCount instead of counting local rows.
                                    seasonRows.add(
                                        TVSeasonEntity(
                                            showId = showIdKey,
                                            seasonNumber = season.season_number,
                                            episodeCount = season.episode_count?.takeIf { it > 0 }
                                                ?: eps.size,
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
                                if (newEpisodesList.isNotEmpty()) {
                                    db.mediaDao().upsertEpisodes(newEpisodesList)
                                }
                                if (seasonRows.isNotEmpty()) {
                                    db.seasonDao().upsertSeasons(seasonRows)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isLoadingEpisodes = false
                        }
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
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Couldn't load this title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "It may have been removed, or the library is still syncing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) {
                            Text("Back")
                        }
                        Button(onClick = { reloadTrigger++ }, shape = RoundedCornerShape(12.dp)) {
                            Text("Retry")
                        }
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

    // Translucent (no blur) sticky bar with a title that fades in once the hero scrolls
    // away, matching the M3E prototype's collapsing header without reintroducing Haze.
    val listState = rememberLazyListState()
    val barElevated by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 }
    }
    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120 }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DetailTopBar(
            title = item.title,
            subtitle = if (isTV && episodes.isNotEmpty()) {
                "Season $selectedSeason • ${episodes.size} Episodes"
            } else {
                null
            },
            logoUrl = titleLogoUrl,
            logoLoading = detailsLoading,
            showTitle = showCollapsedTitle,
            elevated = barElevated,
            onBack = onBack,
            onShare = {
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, "Check out ${item.title} on MediaTracker")
                    type = "text/plain"
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, null))
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Full-bleed backdrop hero with the title overlaid, per the prototype.
            item {
                val backdropUrl = remember(item.backdropUrl, tmdbDetails?.backdrop_path) {
                    TMDBClient.buildBackdropUrl(tmdbDetails?.backdrop_path) ?: item.backdropUrl
                }
                val vote = tmdbDetails?.vote_average ?: prefs.getCachedVoteAverage(item.id)?.toDouble()
                val genres = tmdbDetails?.genres?.map { it.name }
                    ?: prefs.getCachedGenres(item.id)?.split(",")?.map { it.trim() }
                    ?: emptyList()
                val heroPoster = item.posterUrl ?: TMDBClient.buildPosterUrl(tmdbDetails?.poster_path)

                // Hero metadata strip: content rating, median episode time, network.
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
                    if (!tmdbNetName.isNullOrBlank()) {
                        NetworkResolver.resolveByName(tmdbNetName, isTV = isTV)
                    } else {
                        NetworkResolver.resolve(item)
                    }
                }
                val resolvedName = networkInfo.name
                val displayNetwork = if (resolvedName.isNotBlank() &&
                    !resolvedName.equals("Theatrical", ignoreCase = true) &&
                    !resolvedName.equals("FILM", ignoreCase = true) &&
                    !resolvedName.equals("Digital", ignoreCase = true)) {
                    resolvedName
                } else if (tmdbNetName?.isNotBlank() == true) {
                    tmdbNetName
                } else {
                    "NA"
                }
                var networkLogo by remember(displayNetwork) { mutableStateOf<String?>(null) }
                LaunchedEffect(displayNetwork) {
                    networkLogo = if (displayNetwork != "NA") db.facetLogoDao().logoFor(displayNetwork) else null
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(256.dp)
                ) {
                    if (backdropUrl != null) {
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                    }

                    // Multi-stop scrim: a light top darkening for the bar area, then a
                    // heavier always-dark wash so the overlaid white text stays legible in
                    // both themes (a surface-tinted fade would vanish in light mode).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            // Asymmetrical squircle poster, per the prototype.
                            Surface(
                                shape = ExpressiveShapes.AsymPoster,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.20f)),
                                modifier = Modifier.size(width = 80.dp, height = 112.dp)
                            ) {
                                if (heroPoster != null) {
                                    AsyncImage(
                                        model = heroPoster,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = ExpressiveShapes.AsymTag,
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(
                                            text = (genres.firstOrNull() ?: if (isTV) "TV SHOW" else "MOVIE").uppercase(Locale.US),
                                            style = AppTextStyles.MonoLabel,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            maxLines = 1
                                        )
                                    }
                                    if (vote != null && vote > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = sem.gold,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = String.format(Locale.US, "%.1f", vote),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Title name (the wordmark logo now lives in the top bar).
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Metadata strip where the logo used to sit (skeleton while loading).
                        if (detailsLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                repeat(3) {
                                    SkeletonBox(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(64.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BigPill(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ratingLabel,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (ratingLabel == "NA") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                        maxLines = 1
                                    )
                                }
                                BigPill(modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = timeLabel,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (timeLabel == "NA") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                                BigPill(modifier = Modifier.weight(1f)) {
                                    when {
                                        prefs.detailNetworkLogo && networkLogo != null -> AsyncImage(
                                            model = TMDBClient.buildLogoUrl(networkLogo),
                                            contentDescription = displayNetwork,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .height(26.dp)
                                                .widthIn(max = 110.dp)
                                        )
                                        displayNetwork != "NA" -> Text(
                                            text = displayNetwork,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        else -> Text(
                                            text = "NA",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Unified macOS-Style Action Bar: Status Menu + Taste Toggle
            item {
                val allStates = listOf(
                    Triple("Wishlist", "Watchlist", Icons.Default.Schedule),
                    Triple("Active", "In Progress", Icons.Default.PlayArrow),
                    Triple("Completed", "Completed", Icons.Default.Check),
                    Triple("On Hold", "On Hold", Icons.Default.Pause),
                    Triple("Dropped", "Dropped", Icons.Default.Close),
                    Triple("Re-watching", "Re-watching", Icons.Default.Refresh)
                )

                val currentMatchedState = allStates.firstOrNull { 
                    it.first.equals(item.state, ignoreCase = true) || 
                    it.second.equals(item.state, ignoreCase = true) ||
                    (it.first == "Active" && item.state.equals("Watching", ignoreCase = true))
                } ?: allStates[0]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = ExpressiveShapes.AsymCard,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status trigger (opens the picker sheet) — icon, name, chevron.
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { statusPickerOpen = true }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = currentMatchedState.third,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = currentMatchedState.second,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Change status",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Taste toggles (Loved / Liked / Disliked) on the same row.
                        val isLoved = item.taste.equals("Loved", ignoreCase = true) || item.taste.equals("Love", ignoreCase = true)
                        val isLiked = item.taste.equals("Liked", ignoreCase = true) || item.taste.equals("Like", ignoreCase = true)
                        val isDisliked = item.taste.equals("Disliked", ignoreCase = true)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TasteCircle(
                                selected = isLoved,
                                accent = sem.loved,
                                icon = Icons.Default.Favorite,
                                contentDescription = "Loved",
                                onClick = {
                                    val next = if (isLoved) "None" else "Loved"
                                    scope.launch {
                                        db.mediaDao().updateTaste(item.id, next)
                                        mediaItem = item.copy(taste = next)
                                        syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                    }
                                }
                            )
                            TasteCircle(
                                selected = isLiked,
                                accent = sem.liked,
                                icon = Icons.Default.ThumbUp,
                                contentDescription = "Liked",
                                onClick = {
                                    val next = if (isLiked) "None" else "Liked"
                                    scope.launch {
                                        db.mediaDao().updateTaste(item.id, next)
                                        mediaItem = item.copy(taste = next)
                                        syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                    }
                                }
                            )
                            TasteCircle(
                                selected = isDisliked,
                                accent = sem.disliked,
                                icon = Icons.Default.ThumbDown,
                                contentDescription = "Disliked",
                                onClick = {
                                    val next = if (isDisliked) "None" else "Disliked"
                                    scope.launch {
                                        db.mediaDao().updateTaste(item.id, next)
                                        mediaItem = item.copy(taste = next)
                                        syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                    }
                                }
                            )
                        }
                    }
                }

                // Expressive status picker sheet (opened by the STATUS picker above).
                if (statusPickerOpen) {
                    StatusPickerSheet(
                        states = allStates,
                        current = currentMatchedState,
                        onSelect = { (rawState, displayName, _) ->
                            statusPickerOpen = false
                            scope.launch {
                                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                db.mediaDao().updateState(item.id, rawState, now)
                                mediaItem = item.copy(state = rawState)
                                syncManager.pushMediaUpdate(item.id, item.type, rawState, item.taste)
                                AppToast.show("Marked as $displayName")
                            }
                        },
                        onDismiss = { statusPickerOpen = false }
                    )
                }
            }

            // Genre tags.
            item {
                val genres = tmdbDetails?.genres?.map { it.name }
                    ?: prefs.getCachedGenres(item.id)?.split(",")?.map { it.trim() }
                    ?: emptyList()
                if (genres.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        genres.take(6).forEach { g ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = g,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }



            // Overview Section — clamped to 3 lines with a See more / See less toggle.
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
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                if (!expanded) hasOverflow = result.hasVisualOverflow
                            }
                        )
                        if (hasOverflow || expanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (expanded) "See less" else "See more",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { expanded = !expanded }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // TV Show Episodes Section
            if (isTV) {
                // Up Next Hero Banner
                if (nextUpEpisode != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "UP NEXT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "S${nextUpEpisode.seasonNumber} E${nextUpEpisode.episodeNumber} • ${nextUpEpisode.name.ifBlank { "Episode ${nextUpEpisode.episodeNumber}" }}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box {
                                    // M3 Expressive split button: the primary action marks
                                    // the next episode; the trailing half opens bulk actions.
                                    SplitButtonLayout(
                                        leadingButton = {
                                            SplitButtonDefaults.LeadingButton(
                                                onClick = {
                                                    scope.launch {
                                                        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                        db.mediaDao().setEpisodeWatched(nextUpEpisode.uniqueId, true, now)
                                                        val showTmdbId = MediaIds.tmdbIdOf(nextUpEpisode.showId).toString()
                                                        syncManager.pushEpisodeWatch(showTmdbId, nextUpEpisode.seasonNumber, nextUpEpisode.episodeNumber, true)
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Mark Watched", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        trailingButton = {
                                            SplitButtonDefaults.TrailingButton(
                                                onClick = { markMenuExpanded = true }
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = "More watch options",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = markMenuExpanded,
                                        onDismissRequest = { markMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Mark season watched") },
                                            leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                                            onClick = {
                                                markMenuExpanded = false
                                                scope.launch {
                                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                    val showTmdbId = MediaIds.tmdbIdOf(mediaId).toString()
                                                    currentSeasonEpisodes.forEach { ep ->
                                                        db.mediaDao().setEpisodeWatched(ep.uniqueId, true, now)
                                                        syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, true)
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Mark all watched") },
                                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                                            onClick = {
                                                markMenuExpanded = false
                                                scope.launch {
                                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                    val showTmdbId = MediaIds.tmdbIdOf(mediaId).toString()
                                                    episodes.forEach { ep ->
                                                        db.mediaDao().setEpisodeWatched(ep.uniqueId, true, now)
                                                        syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, true)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Season & Episodes header with the inline batch action.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "Season & Episodes",
                            modifier = Modifier.weight(1f)
                        )
                        if (isLoadingEpisodes) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                        } else if (currentSeasonEpisodes.isNotEmpty()) {
                            val allSeasonWatched = currentSeasonEpisodes.all { it.isWatched }
                            Surface(
                                onClick = {
                                    scope.launch {
                                        val targetWatched = !allSeasonWatched
                                        val now = if (targetWatched) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()) else null
                                        val showTmdbId = MediaIds.tmdbIdOf(mediaId).toString()
                                        currentSeasonEpisodes.forEach { ep ->
                                            db.mediaDao().setEpisodeWatched(ep.uniqueId, targetWatched, now)
                                            syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, targetWatched)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (allSeasonWatched) Icons.Default.Close else Icons.Default.DoneAll,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (allSeasonWatched) "Unmark Season" else "Mark Season Seen",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Breathable, scrollable season pills with per-season progress.
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

                // Watch progress metric card for the selected season.
                if (currentSeasonEpisodes.isNotEmpty()) {
                    item {
                        val watchedCount = currentSeasonEpisodes.count { it.isWatched }
                        val total = currentSeasonEpisodes.size
                        val pct = if (total == 0) 0 else (watchedCount * 100) / total
                        FlatCard(
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Season $selectedSeason Progress",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "$watchedCount of $total watched ($pct%)",
                                        style = AppTextStyles.MonoValue,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                ProgressLine(fraction = if (total == 0) 0f else watchedCount.toFloat() / total)
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
                        EpisodeCard(
                            episode = ep,
                            serviceHint = AirTimeResolver.serviceHint(item.network, item.studios, item.genres),
                            timezone = item.timezone,
                            onToggleWatched = { watched ->
                                scope.launch {
                                    val now = if (watched) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()) else null
                                    db.mediaDao().setEpisodeWatched(ep.uniqueId, watched, now)
                                    val showTmdbId = MediaIds.tmdbIdOf(ep.showId).toString()
                                    syncManager.pushEpisodeWatch(showTmdbId, ep.seasonNumber, ep.episodeNumber, watched)
                                }
                            }
                        )
                    }
                }
            }

            // Cast & Characters — prototype carousel: asym avatars, info affordance, bio on tap.
            if (castMembers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "Cast & Characters",
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Tap for bio",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(castMembers, key = { it.id }) { actor ->
                                CastMemberCard(
                                    actor = actor,
                                    onClick = { selectedActor = actor }
                                )
                            }
                        }
                    }
                }
            } else if (detailsLoading) {
                // Credits are still loading — hold the section with a skeleton.
                item {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        SectionHeader(
                            title = "Cast & Characters",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CastSkeletonRow()
                    }
                }
            }
        }

        // Cast bio sheet (overlay), opened by tapping a cast avatar.
        selectedActor?.let { actor ->
            ActorBioSheet(
                actor = actor,
                tmdbClient = container.tmdbClient(),
                onDismiss = { selectedActor = null }
            )
        }
    }
}

/**
 * Translucent sticky top bar. At rest the centre shows the title logo (or the name when
 * there is no logo); once the hero scrolls away it crossfades to the name + episode count.
 */
@Composable
private fun DetailTopBar(
    title: String,
    subtitle: String?,
    logoUrl: String?,
    logoLoading: Boolean,
    showTitle: Boolean,
    elevated: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, label = "detailTitleAlpha")
    val logoAlpha by animateFloatAsState(if (showTitle) 0f else 1f, label = "detailLogoAlpha")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.background.copy(alpha = if (elevated) 0.94f else 0.85f)
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onBack,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Logo is still being fetched — hold the space with a shimmer bar.
                logoLoading && logoUrl == null -> SkeletonBox(
                    modifier = Modifier
                        .width(140.dp)
                        .height(20.dp)
                        .alpha(logoAlpha),
                    shape = RoundedCornerShape(6.dp)
                )
                logoUrl != null -> AsyncImage(
                    model = logoUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(28.dp)
                        .widthIn(max = 190.dp)
                        .alpha(logoAlpha)
                )
            }

            // Name + episode count — shown on scroll, or always when there is no logo.
            Column(
                modifier = Modifier.alpha(if (logoUrl == null && !logoLoading) 1f else titleAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle.uppercase(Locale.US),
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }

        Surface(
            onClick = onShare,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Cast bio bottom sheet: avatar, name/role, biography, and a few known works. The bio
 * and filmography are fetched lazily on open and cached alongside the detail payload.
 */
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actor.profile_path != null) {
                    AsyncImage(
                        model = TMDBClient.buildProfileUrl(actor.profile_path),
                        contentDescription = actor.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(ExpressiveShapes.AsymPoster)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(ExpressiveShapes.AsymPoster)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = (actor.character?.takeIf { it.isNotBlank() }?.let { "As $it" } ?: "Cast").uppercase(Locale.US),
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = actor.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }
                bio != null -> Text(
                    text = bio!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            if (works.isNotEmpty()) {
                Text(
                    text = "KNOWN WORKS (TMDB)",
                    style = AppTextStyles.MonoLabel,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    works.forEach { title ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episode airing shown in the device's local zone (IST here), no "ET"/"IST" suffix.
 * The date is derived from the resolved instant, so a Marvel drop at 9 PM ET on Oct 13
 * reads as "Oct 14, 6:30 AM" locally.
 */
private fun episodeAirLabel(
    episode: EpisodeEntity,
    serviceHint: String,
    timezone: String?
): String? {
    val date = episode.airDate?.take(10) ?: return null
    val instant = AirTimeResolver.resolveAirInstant(date, episode.airTime, episode.airstamp, timezone, serviceHint)
        ?: return date
    return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(instant)
}

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (episode.isWatched) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (episode.isWatched) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Tactile Squircle Check Button (M3E style)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (episode.isWatched) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (episode.isWatched) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .size(32.dp)
                    .semantics {
                        contentDescription = if (episode.isWatched) {
                            "Mark episode unwatched"
                        } else {
                            "Mark episode watched"
                        }
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

            // Episode Thumbnail if available
            if (!episode.stillPath.isNullOrBlank()) {
                AsyncImage(
                    model = if (episode.stillPath.startsWith("http")) episode.stillPath else "https://image.tmdb.org/t/p/w780${episode.stillPath}",
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 84.dp, height = 50.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "S${episode.seasonNumber}E${episode.episodeNumber}",
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (episode.runtime != null && episode.runtime > 0) {
                        Text(
                            text = "${episode.runtime}m",
                            style = AppTextStyles.MonoValue,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (airLabel != null) "Aired: $airLabel" else "Aired: TBA",
                        style = AppTextStyles.MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
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

/** Animated shimmer brush used by the loading skeletons. */
@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x * 400f, 0f),
        end = Offset((x + 1f) * 400f, 0f)
    )
}

/** A shimmering placeholder block. */
@Composable
private fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(modifier.clip(shape).background(shimmerBrush()))
}

/** Skeleton for the cast carousel while credits load. */
@Composable
private fun CastSkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(4) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(80.dp)
            ) {
                SkeletonBox(Modifier.size(64.dp), ExpressiveShapes.AsymPoster)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(Modifier.fillMaxWidth().height(10.dp), RoundedCornerShape(5.dp))
                Spacer(modifier = Modifier.height(5.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(9.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

/** Skeleton for the episode checklist while seasons load. */
@Composable
private fun EpisodeSkeletonList(count: Int = 4) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(Modifier.size(32.dp), RoundedCornerShape(12.dp))
                Spacer(modifier = Modifier.width(12.dp))
                SkeletonBox(Modifier.size(width = 84.dp, height = 50.dp), RoundedCornerShape(10.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.5f).height(11.dp), RoundedCornerShape(5.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.85f).height(10.dp), RoundedCornerShape(5.dp))
                }
            }
        }
    }
}

/** Cast carousel item: asym avatar with an info affordance, name, and character. */
@Composable
private fun CastMemberCard(
    actor: com.vara.mediatracker.data.api.TMDBCastMember,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clip(ExpressiveShapes.SquircleMd)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            if (actor.profile_path != null) {
                AsyncImage(
                    model = TMDBClient.buildProfileUrl(actor.profile_path),
                    contentDescription = actor.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(ExpressiveShapes.AsymPoster)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            ExpressiveShapes.AsymPoster
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(ExpressiveShapes.AsymPoster)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Info affordance badge (prototype's bottom-right chip).
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = actor.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (!actor.character.isNullOrBlank()) {
            Text(
                text = actor.character,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/** Large, breathable metadata pill (content rating / episode time / network logo). */
@Composable
private fun BigPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** Circular Loved / Liked / Disliked toggle used in the status card. */
@Composable
private fun TasteCircle(
    selected: Boolean,
    accent: Color,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        border = if (selected) BorderStroke(1.dp, accent) else null,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Checkbox
                toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
            }
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Breathable season chip with a per-season watched/total badge. */
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
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (seasonNumber == 0) "Specials" else "Season $seasonNumber",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
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

/** M3 Expressive single-select sheet for the watch status. */
@Composable
private fun StatusPickerSheet(
    states: List<Triple<String, String, ImageVector>>,
    current: Triple<String, String, ImageVector>,
    onSelect: (Triple<String, String, ImageVector>) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Set status",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            states.forEach { state ->
                val isSelected = state.first == current.first
                Surface(
                    onClick = { onSelect(state) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = state.third,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = state.second,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
