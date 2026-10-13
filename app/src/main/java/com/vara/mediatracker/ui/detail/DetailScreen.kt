package com.vara.mediatracker.ui.detail

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
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
import com.vara.mediatracker.ui.common.ExpressiveSegmentedControl
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.util.AirTimeResolver
import com.vara.mediatracker.util.NetworkResolver
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
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
    val showIdKey = MediaIds.tvShowKey(mediaId)
    val episodes by db.mediaDao().getEpisodesForShow(showIdKey)
        .collectAsState(initial = emptyList())
    var isLoadingEpisodes by remember { mutableStateOf(false) }

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
                            
                            // Fetch Details (Genres, Ratings, Runtime)
                            val detailsRes = if (isMovie) tmdbClient.getMovieDetails(tmdbId) else tmdbClient.getTVDetails(tmdbId)
                            val details = detailsRes.getOrNull()
                            tmdbDetails = details

                            // Persist backdrop and poster if updated from TMDB
                            val tmdbBackdrop = TMDBClient.buildPosterUrl(details?.backdrop_path)
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

                            // Fetch Credits (Cast)
                            val creditsRes = if (isMovie) tmdbClient.getMovieCredits(tmdbId) else tmdbClient.getTVCredits(tmdbId)
                            castMembers = creditsRes.getOrNull()?.cast?.take(15) ?: emptyList()

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
                                    val seasonDetailsRes = tmdbClient.getSeasonDetails(tmdbId, season.season_number)
                                    val seasonDetails = seasonDetailsRes.getOrNull()
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
                CircularProgressIndicator()
            }
        }
        return
    }

    val isTV = item.type.contains("TV", ignoreCase = true)
    val syncManager = remember { container.syncManager() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.title,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "Check out ${item.title} on MediaTracker")
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Mac-style Atmospheric Hero Header with High-Res Poster Card
            item {
                val posterUrl = remember(item.posterUrl, tmdbDetails?.poster_path) {
                    item.posterUrl ?: TMDBClient.buildPosterUrl(tmdbDetails?.poster_path)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // High-Res Poster Card with Bloom & Rounded Corners
                        Surface(
                            modifier = Modifier
                                .width(135.dp)
                                .height(200.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            if (posterUrl != null) {
                                AsyncImage(
                                    model = posterUrl,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isTV) Icons.Default.Tv else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }

                        // Editorial Title, Badges, and Details
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Type Badge Pill
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = if (isTV) "TV SHOW" else "MOVIE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            // Title
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )

                            // Rating and Year Row
                            val releaseYear = item.releaseDate?.take(4) 
                                ?: tmdbDetails?.release_date?.take(4) 
                                ?: tmdbDetails?.first_air_date?.take(4) 
                                ?: "2026"
                            val vote = tmdbDetails?.vote_average ?: prefs.getCachedVoteAverage(item.id)?.toDouble()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (vote != null && vote > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = sem.gold,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = String.format(Locale.US, "%.1f", vote),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Text(
                                    text = releaseYear,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Genres
                            val genres = tmdbDetails?.genres?.map { it.name }
                                ?: prefs.getCachedGenres(item.id)?.split(",")?.map { it.trim() }
                                ?: emptyList()
                            if (genres.isNotEmpty()) {
                                Text(
                                    text = genres.take(3).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
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

                var statusMenuExpanded by remember { mutableStateOf(false) }
                val currentMatchedState = allStates.firstOrNull { 
                    it.first.equals(item.state, ignoreCase = true) || 
                    it.second.equals(item.state, ignoreCase = true) ||
                    (it.first == "Active" && item.state.equals("Watching", ignoreCase = true))
                } ?: allStates[0]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Status Picker Dropdown
                        Box {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { statusMenuExpanded = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = currentMatchedState.third,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = currentMatchedState.second,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = statusMenuExpanded,
                                onDismissRequest = { statusMenuExpanded = false }
                            ) {
                                allStates.forEach { (rawState, displayName, icon) ->
                                    val isSelected = currentMatchedState.first == rawState
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = displayName,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            statusMenuExpanded = false
                                            scope.launch {
                                                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                                db.mediaDao().updateState(item.id, rawState, now)
                                                mediaItem = item.copy(state = rawState)
                                                syncManager.pushMediaUpdate(item.id, item.type, rawState, item.taste)
                                                Toast.makeText(context, "Marked as $displayName", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Taste Toggles (Loved / Liked / Disliked)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isLoved = item.taste.equals("Loved", ignoreCase = true) || item.taste.equals("Love", ignoreCase = true)
                            val isLiked = item.taste.equals("Liked", ignoreCase = true) || item.taste.equals("Like", ignoreCase = true)
                            val isDisliked = item.taste.equals("Disliked", ignoreCase = true)

                            // Loved Pill
                            Surface(
                                shape = CircleShape,
                                color = if (isLoved) sem.loved.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isLoved) BorderStroke(1.dp, sem.loved) else null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .semantics {
                                        contentDescription = "Loved"
                                        role = Role.Checkbox
                                        toggleableState = if (isLoved) ToggleableState.On else ToggleableState.Off
                                    }
                                    .clickable {
                                        val next = if (isLoved) "None" else "Loved"
                                        scope.launch {
                                            db.mediaDao().updateTaste(item.id, next)
                                            mediaItem = item.copy(taste = next)
                                            syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = if (isLoved) sem.loved else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Liked Pill
                            Surface(
                                shape = CircleShape,
                                color = if (isLiked) sem.liked.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isLiked) BorderStroke(1.dp, sem.liked) else null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .semantics {
                                        contentDescription = "Liked"
                                        role = Role.Checkbox
                                        toggleableState = if (isLiked) ToggleableState.On else ToggleableState.Off
                                    }
                                    .clickable {
                                        val next = if (isLiked) "None" else "Liked"
                                        scope.launch {
                                            db.mediaDao().updateTaste(item.id, next)
                                            mediaItem = item.copy(taste = next)
                                            syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ThumbUp,
                                        contentDescription = null,
                                        tint = if (isLiked) sem.liked else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Disliked Pill
                            Surface(
                                shape = CircleShape,
                                color = if (isDisliked) sem.disliked.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isDisliked) BorderStroke(1.dp, sem.disliked) else null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .semantics {
                                        contentDescription = "Disliked"
                                        role = Role.Checkbox
                                        toggleableState = if (isDisliked) ToggleableState.On else ToggleableState.Off
                                    }
                                    .clickable {
                                        val next = if (isDisliked) "None" else "Disliked"
                                        scope.launch {
                                            db.mediaDao().updateTaste(item.id, next)
                                            mediaItem = item.copy(taste = next)
                                            syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ThumbDown,
                                        contentDescription = null,
                                        tint = if (isDisliked) sem.disliked else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Stats Grid: 3 pills for TV (EPISODES / RUN TIME / NETWORK); 2 pills for Movies (RUN TIME / PRODUCTION 2x size)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val watchedEpCount = episodes.count { it.isWatched }
                    val totalEpCount = episodes.size
                    
                    // Dynamic runtime with NA fallback
                    val runtimeMin: Int? = if (isTV) {
                        tmdbDetails?.episode_run_time?.firstOrNull { it > 0 }
                            ?: episodes.firstOrNull { (it.runtime ?: 0) > 0 }?.runtime
                            ?: tmdbDetails?.runtime
                            ?: prefs.getCachedRuntime(item.id)
                    } else {
                        tmdbDetails?.runtime ?: prefs.getCachedRuntime(item.id)
                    }

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

                    if (isTV) {
                        // Pill 1: EPISODES
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "EPISODES",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (totalEpCount > 0) "$watchedEpCount / $totalEpCount" else if (tmdbDetails?.number_of_episodes != null) "0 / ${tmdbDetails?.number_of_episodes}" else "NA",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Pill 2: RUN TIME
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "RUN TIME",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (runtimeMin != null && runtimeMin > 0) "${runtimeMin}m / ep" else "NA",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Pill 3: NETWORK
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "NETWORK",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = displayNetwork,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        // MOVIE LAYOUT: RUN TIME (1 tile) + PRODUCTION (2 tiles wide)
                        // Pill 1: RUN TIME
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "RUN TIME",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val runtimeText = if (runtimeMin != null && runtimeMin > 0) {
                                    if (runtimeMin > 60) "${runtimeMin / 60}h ${runtimeMin % 60}m" else "${runtimeMin}m"
                                } else {
                                    "NA"
                                }
                                Text(
                                    text = runtimeText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Pill 2: PRODUCTION (2 tiles wide)
                        Surface(
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "PRODUCTION",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = displayNetwork,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }



            // Overview Section
            if (item.overview.isNotBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 21.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                            db.mediaDao().setEpisodeWatched(nextUpEpisode.uniqueId, true, now)
                                            val showTmdbId = MediaIds.tmdbIdOf(nextUpEpisode.showId).toString()
                                            syncManager.pushEpisodeWatch(showTmdbId, nextUpEpisode.seasonNumber, nextUpEpisode.episodeNumber, true)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Mark Watched", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Section Header with Total Progress
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Episodes (${episodes.count { it.isWatched }}/${episodes.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (isLoadingEpisodes) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                // Horizontally Scrollable Season Pills
                if (seasonsList.size > 1) {
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasonsList) { sNum ->
                                val sEps = episodes.filter { it.seasonNumber == sNum }
                                val watchedCount = sEps.count { it.isWatched }
                                val isSelected = sNum == selectedSeason
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedSeason = sNum },
                                    label = {
                                        Text("Season $sNum ($watchedCount/${sEps.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    },
                                    leadingIcon = if (watchedCount == sEps.size && sEps.isNotEmpty()) {
                                        { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = sem.success) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                // Batch Season Action Row
                if (currentSeasonEpisodes.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val allSeasonWatched = currentSeasonEpisodes.all { it.isWatched }
                            Text(
                                text = "Season $selectedSeason",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
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
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (allSeasonWatched) "Mark Season Unwatched" else "Mark Season Watched",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (currentSeasonEpisodes.isEmpty()) {
                    item {
                        Text(
                            text = if (isLoadingEpisodes) "Loading episodes from TMDB..." else "No episodes available for Season $selectedSeason.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
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

            // Top Cast Gallery Section (Moved below episodes)
            if (castMembers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = "Top Cast",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(castMembers, key = { it.id }) { actor ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(72.dp)
                                ) {
                                    if (actor.profile_path != null) {
                                        AsyncImage(
                                            model = com.vara.mediatracker.data.api.TMDBClient.buildProfileUrl(actor.profile_path),
                                            contentDescription = actor.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(CircleShape)
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
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = actor.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
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
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episode date + the rule-based drop time, e.g. "Oct 13 • 9:00 PM ET". Uses the real
 * airstamp/airtime when present, otherwise the streaming rule — so a Marvel show on
 * Disney+ shows its 9 PM ET flagship drop rather than the bare date.
 */
private fun episodeAirLabel(
    episode: EpisodeEntity,
    serviceHint: String,
    timezone: String?
): String? {
    val date = episode.airDate?.take(10) ?: return null
    val dateLabel = runCatching {
        SimpleDateFormat("MMM d", Locale.getDefault())
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date))
    }.getOrNull() ?: date

    val hasRealTime = !episode.airTime.isNullOrBlank() || !episode.airstamp.isNullOrBlank()
    val timeLabel = if (hasRealTime) {
        AirTimeResolver.resolveAirInstant(date, episode.airTime, episode.airstamp, timezone, serviceHint)
            ?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(it) }
    } else {
        AirTimeResolver.formatAirTime(date, serviceHint)
    }
    return if (timeLabel != null) "$dateLabel • $timeLabel" else dateLabel
}

@Composable
fun EpisodeCard(
    episode: EpisodeEntity,
    onToggleWatched: (Boolean) -> Unit,
    serviceHint: String = "",
    timezone: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val sem = LocalSemanticColors.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (episode.isWatched) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (episode.isWatched) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Tactile Squircle Check Button (M3E style)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (episode.isWatched) MaterialTheme.colorScheme.primary else Color.Transparent,
                    border = if (episode.isWatched) null else androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .size(36.dp)
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
                        if (episode.isWatched) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Episode Thumbnail if available
                if (!episode.stillPath.isNullOrBlank()) {
                    AsyncImage(
                        model = if (episode.stillPath.startsWith("http")) episode.stillPath else "https://image.tmdb.org/t/p/w300${episode.stillPath}",
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 84.dp, height = 50.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "E${episode.episodeNumber} • ${episode.name.ifBlank { "Episode " + episode.episodeNumber }}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (episode.runtime != null && episode.runtime > 0) {
                            Text(
                                text = "${episode.runtime}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        episodeAirLabel(episode, serviceHint, timezone)?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Status Badge Chip (Watched vs Upcoming)
                if (episode.isWatched) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = sem.success.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = "Watched",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = sem.success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Expandable Overview
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded && episode.overview.isNotBlank(),
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Text(
                        text = episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
