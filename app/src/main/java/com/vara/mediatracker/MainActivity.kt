package com.vara.mediatracker

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import com.vara.mediatracker.ui.common.AppDestinations
import com.vara.mediatracker.ui.common.ExpressiveNavigationBar
import com.vara.mediatracker.ui.theme.AppMotion as motion
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
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.calendar.ReleaseCalendarScreen
import com.vara.mediatracker.ui.detail.DetailScreen
import com.vara.mediatracker.ui.home.HomeScreen
import com.vara.mediatracker.ui.library.LibraryScreen
import com.vara.mediatracker.ui.settings.SettingsScreen
import com.vara.mediatracker.ui.theme.MediaTrackerTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onStart() {
        super.onStart()
        // Background sync keeps the library fresh while the app is closed; this covers
        // the gap when the periodic run has not landed yet.
        SyncScheduler.syncOnForeground(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(container: AppContainer) {
    val mediaItems by container.mediaDao.getAllActiveMedia().collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeDetailMediaId by remember { mutableStateOf<String?>(null) }

    // Hardware / gesture back closes an open detail before it exits the tab.
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
                        text = AppDestinations[selectedTab].label,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            ExpressiveNavigationBar(
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            // Direction-aware slide so moving between tabs feels spatial rather than a
            // crossfade, which made the whole screen feel like it was reloading.
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    val offset = if (forward) 1 else -1
                    (slideInHorizontally(tween(motion.EMPHASIZED, easing = motion.emphasizedEase)) { width ->
                        offset * width / 6
                    } + fadeIn(tween(motion.STANDARD)))
                        .togetherWith(
                            slideOutHorizontally(tween(motion.EMPHASIZED, easing = motion.emphasizedEase)) { width ->
                                -offset * width / 6
                            } + fadeOut(tween(motion.FAST))
                        )
                },
                label = "tabTransition"
            ) { tab ->
                when (tab) {
                    0 -> HomeScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                    1 -> ReleaseCalendarScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                    2 -> LibraryScreen(items = mediaItems, container = container, onSelectMedia = { activeDetailMediaId = it })
                    else -> SettingsScreen(container = container)
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
