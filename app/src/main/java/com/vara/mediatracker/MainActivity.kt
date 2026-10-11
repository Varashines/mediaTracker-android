package com.vara.mediatracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import androidx.activity.compose.BackHandler
import com.vara.mediatracker.data.db.EpisodeKeyReconciler
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.common.rememberNowMillis
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.calendar.ReleaseCalendarScreen
import com.vara.mediatracker.ui.detail.DetailScreen
import com.vara.mediatracker.ui.home.HomeScreen
import com.vara.mediatracker.ui.library.LibraryScreen
import com.vara.mediatracker.ui.settings.SettingsScreen
import com.vara.mediatracker.ui.theme.MediaTrackerTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { AppContainer(applicationContext) }

    private val prefs get() = container.preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repairLegacyEpisodeKeys()
        setContent {
            MediaTrackerTheme {
                // Single app-wide clock so countdown labels stay live without each
                // card owning its own ticker coroutine.
                CompositionLocalProvider(LocalNowMillis provides rememberNowMillis()) {
                    MainAppScreen(container = container)
                }
            }
        }
    }

    /**
     * Rewrites pre-canonical episode primary keys once per install. Idempotent, so a
     * crash mid-migration simply retries on the next launch.
     */
    private fun repairLegacyEpisodeKeys() {
        if (prefs.episodeKeyMigrationDone) return
        lifecycleScope.launch {
            val migrated = EpisodeKeyReconciler(container.database).reconcile()
            prefs.episodeKeyMigrationDone = true
            if (migrated > 0) {
                Log.i(TAG, "Normalized $migrated legacy episode id(s) to canonical form")
            }
        }
    }

    companion object {
        private const val TAG = "MediaTracker"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(container: AppContainer) {
    val mediaItems by container.mediaDao.getAllActiveMedia().collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeDetailMediaId by remember { mutableStateOf<String?>(null) }

    // Hardware / Gesture Back navigation for Detail View
    BackHandler(enabled = activeDetailMediaId != null) {
        activeDetailMediaId = null
    }

    if (activeDetailMediaId != null) {
        DetailScreen(
            mediaId = activeDetailMediaId!!,
            container = container,
            onBack = { activeDetailMediaId = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "Dashboard"
                            1 -> "Calendar"
                            2 -> "Library"
                            else -> "Settings"
                        },
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            // Main Screen Content
            when (selectedTab) {
                0 -> HomeScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                1 -> ReleaseCalendarScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                2 -> LibraryScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                3 -> SettingsScreen(container = container)
            }

            // Floating Bottom Bar Pill
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val items = listOf(
                        Triple(0, Icons.Default.Home, "Home"),
                        Triple(1, Icons.Default.CalendarMonth, "Calendar"),
                        Triple(2, Icons.Default.VideoLibrary, "Library"),
                        Triple(3, Icons.Default.Settings, "Settings")
                    )

                    items.forEach { (tabIndex, icon, label) ->
                        val isSelected = selectedTab == tabIndex
                        Surface(
                            onClick = { selectedTab = tabIndex },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
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

@Composable
fun LibraryGrid(items: List<MediaItemEntity>) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No titles yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Pair your Sync Key in Settings to import your library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item ->
                MediaPosterCard(item = item)
            }
        }
    }
}

@Composable
fun MediaPosterCard(item: MediaItemEntity) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Color.DarkGray)
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Taste Badge
                if (item.taste == "Loved") {
                    Surface(
                        color = Color.Red.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(bottomStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Loved",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(14.dp)
                        )
                    }
                }
            }

            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun SettingsPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Cross-Device Cloud Sync Settings")
    }
}
