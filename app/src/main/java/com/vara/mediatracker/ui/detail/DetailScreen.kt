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
import com.vara.mediatracker.ui.theme.AppColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    LaunchedEffect(mediaId) {
        val item = db.mediaDao().getMediaById(mediaId)
        mediaItem = item

        if (item != null && prefs.tmdbApiKey.isNotBlank()) {
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

                            // Fetch Credits (Cast)
                            val creditsRes = if (isMovie) tmdbClient.getMovieCredits(tmdbId) else tmdbClient.getTVCredits(tmdbId)
                            castMembers = creditsRes.getOrNull()?.cast?.take(15) ?: emptyList()

                            // If it's a TV show, fetch full episodes and episode names from TMDB
                            if (!isMovie) {
                                isLoadingEpisodes = true
                                val seasons = tmdbDetails?.seasons ?: emptyList()
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
            CircularProgressIndicator()
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
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(18.dp)
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
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(18.dp)
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
            // M3E Hero Backdrop Artwork with Asymmetric Edge Scrim
            item {
                val coverBackgroundUrl = remember(item.backdropUrl, nextUpEpisode?.stillPath, tmdbDetails?.backdrop_path) {
                    item.backdropUrl
                        ?: TMDBClient.buildPosterUrl(tmdbDetails?.backdrop_path)
                        ?: nextUpEpisode?.stillPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w780$it" }
                        ?: item.posterUrl
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    if (coverBackgroundUrl != null) {
                        AsyncImage(
                            model = coverBackgroundUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    }

                    // Multi-layer Scrim Gradient
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.20f),
                                        Color.Black.copy(alpha = 0.60f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )

                    // Bottom info block inside hero
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Category Tag Pill
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(
                                    text = if (isTV) "SEASON 2 RETURNING" else "FEATURE FILM",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )

                            val genres = tmdbDetails?.genres?.map { it.name } ?: emptyList()
                            val releaseYear = item.releaseDate?.take(4) ?: "2026"
                            val subtitleText = if (genres.isNotEmpty()) {
                                "${genres.take(2).joinToString(", ")} • $releaseYear"
                            } else {
                                if (isTV) "TV Series • $releaseYear" else "Movie • $releaseYear"
                            }

                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                        }

                        val vote = tmdbDetails?.vote_average
                        if (vote != null && vote > 0) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.70f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = AppColors.Gold,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.1f", vote),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = AppColors.Gold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // M3E Connected Status Bar (Watching / Plan / Done)
            item {
                val statusList = listOf("Watching", "Wishlist", "Completed")
                val matched = statusList.firstOrNull { it.equals(item.state, ignoreCase = true) } ?: "Watching"

                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    ExpressiveSegmentedControl(
                        items = statusList,
                        selectedItem = matched,
                        onItemSelected = { stateName ->
                            scope.launch {
                                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                db.mediaDao().updateState(item.id, stateName, now)
                                mediaItem = item.copy(state = stateName)
                                syncManager.pushMediaUpdate(item.id, item.type, stateName, item.taste)
                                Toast.makeText(context, "Marked as $stateName", Toast.LENGTH_SHORT).show()
                            }
                        },
                        labelProvider = { state ->
                            when (state) {
                                "Watching" -> "Watching"
                                "Wishlist" -> "Plan"
                                "Completed" -> "Done"
                                else -> state
                            }
                        },
                        iconProvider = { state ->
                            when (state) {
                                "Watching" -> Icons.Default.PlayArrow
                                "Wishlist" -> Icons.Default.Bookmark
                                "Completed" -> Icons.Default.Check
                                else -> null
                            }
                        }
                    )
                }
            }

            // Quick Stats 3-Pill Grid (EPISODES / RUN TIME / NETWORK)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val watchedEpCount = episodes.count { it.isWatched }
                    val totalEpCount = episodes.size
                    val runtimeMin = tmdbDetails?.runtime ?: 52
                    val networkName = if (isTV) "HBO Max" else "Theatrical"

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
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (totalEpCount > 0) "$watchedEpCount / $totalEpCount" else "1 / 1",
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
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${runtimeMin}m / ep",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Pill 3: NETWORK / TYPE
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
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = networkName,
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

            // Rich Metadata Strip (Year, Rating, Runtime, Genres)
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rating Pill
                        val vote = tmdbDetails?.vote_average
                        if (vote != null && vote > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = com.vara.mediatracker.ui.theme.AppColors.Gold.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = com.vara.mediatracker.ui.theme.AppColors.Gold,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.1f", vote),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = com.vara.mediatracker.ui.theme.AppColors.Gold
                                    )
                                }
                            }
                        }

                        // Release Year Pill
                        val date = item.releaseDate ?: tmdbDetails?.release_date ?: tmdbDetails?.first_air_date
                        if (!date.isNullOrBlank()) {
                            val year = date.take(4)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = year,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Runtime or Seasons
                        val runtime = tmdbDetails?.runtime
                        val numSeasons = tmdbDetails?.number_of_seasons
                        val durationText = when {
                            runtime != null && runtime > 0 -> "${runtime / 60}h ${runtime % 60}m"
                            numSeasons != null && numSeasons > 0 -> "$numSeasons Season" + if (numSeasons > 1) "s" else ""
                            else -> null
                        }
                        if (durationText != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = durationText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Genres Row
                    val genres = tmdbDetails?.genres
                    if (!genres.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = genres.joinToString(" • ") { it.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Controls: State & Taste Ratings with Instant Cloud Push
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Affinity & Taste", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TasteButton(
                                label = "Loved",
                                icon = Icons.Default.Favorite,
                                isSelected = item.taste == "Loved" || item.taste == "Love",
                                color = com.vara.mediatracker.ui.theme.AppColors.Loved
                            ) {
                                val next = if (item.taste == "Loved") "None" else "Loved"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                    syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                }
                            }
                            TasteButton(
                                label = "Liked",
                                icon = Icons.Default.ThumbUp,
                                isSelected = item.taste == "Liked" || item.taste == "Like",
                                color = com.vara.mediatracker.ui.theme.AppColors.Liked
                            ) {
                                val next = if (item.taste == "Liked") "None" else "Liked"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                    syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                }
                            }
                            TasteButton(
                                label = "Disliked",
                                icon = Icons.Default.ThumbDown,
                                isSelected = item.taste == "Disliked",
                                color = com.vara.mediatracker.ui.theme.AppColors.Disliked
                            ) {
                                val next = if (item.taste == "Disliked") "None" else "Disliked"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                    syncManager.pushMediaUpdate(item.id, item.type, item.state, next)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val statusList = listOf("Wishlist", "Watching", "Completed", "Dropped")
                        val currentMatchedState = statusList.firstOrNull { it.equals(item.state, ignoreCase = true) } ?: "Wishlist"

                        ExpressiveSegmentedControl(
                            items = statusList,
                            selectedItem = currentMatchedState,
                            onItemSelected = { stateName ->
                                scope.launch {
                                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                    db.mediaDao().updateState(item.id, stateName, now)
                                    mediaItem = item.copy(state = stateName)
                                    syncManager.pushMediaUpdate(item.id, item.type, stateName, item.taste)
                                    Toast.makeText(context, "Marked as $stateName", Toast.LENGTH_SHORT).show()
                                }
                            },
                            labelProvider = { it },
                            iconProvider = { stateName ->
                                when (stateName) {
                                    "Wishlist" -> Icons.Default.Bookmark
                                    "Watching" -> Icons.Default.PlayArrow
                                    "Completed" -> Icons.Default.Check
                                    "Dropped" -> Icons.Default.Close
                                    else -> null
                                }
                            }
                        )
                    }
                }
            }

            // Overview Section
            if (item.overview.isNotBlank()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

            // Top Cast Gallery Section
            if (castMembers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            text = "Top Cast",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (!actor.character.isNullOrBlank()) {
                                        Text(
                                            text = actor.character,
                                            fontSize = 9.sp,
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
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "S${nextUpEpisode.seasonNumber} E${nextUpEpisode.episodeNumber} • ${nextUpEpisode.name.ifBlank { "Episode ${nextUpEpisode.episodeNumber}" }}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
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
                                    Text("Mark Watched", fontSize = 12.sp)
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
                            fontWeight = FontWeight.Bold
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
                                        Text("Season $sNum ($watchedCount/${sEps.size})", fontSize = 12.sp)
                                    },
                                    leadingIcon = if (watchedCount == sEps.size && sEps.isNotEmpty()) {
                                        { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF4CAF50)) }
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
                                    fontWeight = FontWeight.SemiBold
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
        }
    }
}

@Composable
fun TasteButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    ElevatedButton(
        onClick = onClick,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = if (isSelected) color else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun EpisodeCard(
    episode: EpisodeEntity,
    onToggleWatched: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

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
                    onClick = { onToggleWatched(!episode.isWatched) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (episode.isWatched) MaterialTheme.colorScheme.primary else Color.Transparent,
                    border = if (episode.isWatched) null else androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .size(36.dp)
                        .springClickable { onToggleWatched(!episode.isWatched) }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (episode.isWatched) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Mark unwatched",
                                tint = MaterialTheme.colorScheme.onPrimary,
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
                        if (!episode.airDate.isNullOrBlank()) {
                            Text(
                                text = episode.airDate,
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
                        color = Color(0xFF4CAF50).copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = "Watched",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Expandable Overview
            if (expanded && episode.overview.isNotBlank()) {
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
