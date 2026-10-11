package com.vara.mediatracker.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import com.vara.mediatracker.data.model.EpisodeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    mediaId: String,
    db: AppDatabase,
    prefs: PreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mediaItem by remember { mutableStateOf<MediaItemEntity?>(null) }
    val showIdKey = if (mediaId.startsWith("tv_")) mediaId else "tv_" + mediaId.substringAfterLast("_")
    val episodes by db.mediaDao().getEpisodesForShow(showIdKey)
        .collectAsState(initial = emptyList())
    var isLoadingEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        val item = db.mediaDao().getMediaById(mediaId)
        mediaItem = item

        // If it's a TV show, fetch full episodes and episode names from TMDB
        if (item != null && item.type.contains("TV", ignoreCase = true) && prefs.tmdbApiKey.isNotBlank()) {
            val tmdbId = item.id.substringAfterLast("_").toIntOrNull() ?: 0
            if (tmdbId > 0) {
                isLoadingEpisodes = true
                kotlinx.coroutines.Dispatchers.IO.let { ioDispatcher ->
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        try {
                            val tmdbClient = com.vara.mediatracker.data.api.TMDBClient(prefs.tmdbApiKey)
                            val tvDetailsRes = tmdbClient.getTVDetails(tmdbId)
                            val tvDetails = tvDetailsRes.getOrNull()
                            val seasons = tvDetails?.seasons ?: emptyList()

                            val currentLocalEps = db.mediaDao().getEpisodesListForShow(showIdKey).associateBy { 
                                "${it.seasonNumber}_${it.episodeNumber}" 
                            }

                            val newEpisodesList = mutableListOf<EpisodeEntity>()
                            for (season in seasons) {
                                // Skip "specials" (season 0) if desired or keep regular seasons >= 1
                                if (season.season_number < 1) continue
                                val seasonDetailsRes = tmdbClient.getSeasonDetails(tmdbId, season.season_number)
                                val seasonDetails = seasonDetailsRes.getOrNull()
                                val eps = seasonDetails?.episodes ?: emptyList()

                                for (ep in eps) {
                                    val key = "${ep.season_number}_${ep.episode_number}"
                                    val existing = currentLocalEps[key]
                                    val uniqueId = "${tmdbId}_${ep.season_number}_${ep.episode_number}"
                                    newEpisodesList.add(
                                        EpisodeEntity(
                                            uniqueId = existing?.uniqueId ?: uniqueId,
                                            showId = showIdKey,
                                            seasonNumber = ep.season_number,
                                            episodeNumber = ep.episode_number,
                                            name = ep.name ?: existing?.name ?: "",
                                            isWatched = existing?.isWatched ?: false,
                                            watchedDate = existing?.watchedDate,
                                            airDate = ep.air_date ?: existing?.airDate,
                                            runtime = ep.runtime ?: existing?.runtime,
                                            updatedAt = existing?.updatedAt
                                        )
                                    )
                                }
                            }
                            if (newEpisodesList.isNotEmpty()) {
                                db.mediaDao().upsertEpisodes(newEpisodesList)
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
    val syncManager = remember {
        CloudSyncManager(
            mediaDao = db.mediaDao(),
            supabaseUrl = prefs.supabaseUrl,
            anonKey = prefs.supabaseAnonKey,
            syncKey = prefs.syncKey,
            tmdbApiKey = prefs.tmdbApiKey
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
            // Hero Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    if (item.posterUrl != null) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                                        )
                                    )
                                )
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = item.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = item.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Controls: State & Taste Ratings
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Affinity & Taste", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TasteButton(
                                label = "Loved",
                                icon = Icons.Default.Favorite,
                                isSelected = item.taste == "Loved" || item.taste == "Love",
                                color = Color(0xFFE91E63)
                            ) {
                                val next = if (item.taste == "Loved") "None" else "Loved"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                }
                            }
                            TasteButton(
                                label = "Liked",
                                icon = Icons.Default.ThumbUp,
                                isSelected = item.taste == "Liked" || item.taste == "Like",
                                color = Color(0xFF4CAF50)
                            ) {
                                val next = if (item.taste == "Liked") "None" else "Liked"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                }
                            }
                            TasteButton(
                                label = "Disliked",
                                icon = Icons.Default.ThumbDown,
                                isSelected = item.taste == "Disliked",
                                color = Color(0xFFFF9800)
                            ) {
                                val next = if (item.taste == "Disliked") "None" else "Disliked"
                                scope.launch {
                                    db.mediaDao().updateTaste(item.id, next)
                                    mediaItem = item.copy(taste = next)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("Wishlist", "Watching", "Completed", "Dropped").forEach { stateName ->
                                FilterChip(
                                    selected = item.state.equals(stateName, ignoreCase = true),
                                    onClick = {
                                        scope.launch {
                                            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                                            db.mediaDao().updateState(item.id, stateName, now)
                                            mediaItem = item.copy(state = stateName)
                                            Toast.makeText(context, "Marked as $stateName", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    label = { Text(stateName, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // Overview Section
            if (item.overview.isNotBlank()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // TV Show Episodes
            if (isTV) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Episodes (${episodes.count { it.isWatched }}/${episodes.size} Watched)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isLoadingEpisodes) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                if (episodes.isEmpty()) {
                    item {
                        Text(
                            text = if (isLoadingEpisodes) "Loading episodes from TMDB..." else "No episodes available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    items(episodes, key = { it.uniqueId }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            onToggleWatched = { watched ->
                                scope.launch {
                                    val now = if (watched) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()) else null
                                    db.mediaDao().setEpisodeWatched(ep.uniqueId, watched, now)
                                    val showTmdbId = ep.showId.substringAfterLast("_")
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
fun EpisodeRow(
    episode: EpisodeEntity,
    onToggleWatched: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "S${episode.seasonNumber} E${episode.episodeNumber}" + if (episode.name.isNotBlank()) " • ${episode.name}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (episode.watchedDate != null) {
                Text(
                    text = "Watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50)
                )
            }
        }

        Checkbox(
            checked = episode.isWatched,
            onCheckedChange = { onToggleWatched(it) }
        )
    }
}
