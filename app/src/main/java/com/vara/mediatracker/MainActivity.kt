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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.vara.mediatracker.ui.search.SearchScreen
import com.vara.mediatracker.ui.settings.SettingsScreen
import com.vara.mediatracker.ui.theme.MediaTrackerTheme

private const val CalendarTab = 4

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onStart() {
        super.onStart()
        // Background sync keeps the library fresh while the app is closed; this covers
        // the gap when the periodic run has not landed yet.
        SyncScheduler.syncOnForeground(this)
        // TVMaze air-time backfill, so the Radar/Coming Soon show real times promptly.
        SyncScheduler.enrichOnForeground(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var currentThemePreset by remember { mutableIntStateOf(container.preferences.themePreset) }
            var currentThemeMode by remember { mutableIntStateOf(container.preferences.themeMode) }

            MediaTrackerTheme(
                presetIndex = currentThemePreset,
                themeMode = currentThemeMode
            ) {
                // Single app-wide clock so countdown labels stay live without each
                // card owning its own ticker coroutine.
                CompositionLocalProvider(LocalNowMillis provides rememberNowMillis()) {
                    MainAppScreen(
                        container = container,
                        onThemePresetChanged = { newPreset ->
                            container.preferences.themePreset = newPreset
                            currentThemePreset = newPreset
                        },
                        onThemeModeChanged = { newMode ->
                            container.preferences.themeMode = newMode
                            currentThemeMode = newMode
                        }
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    container: AppContainer,
    onThemePresetChanged: (Int) -> Unit = {},
    onThemeModeChanged: (Int) -> Unit = {}
) {
    val mediaItems by container.mediaDao.getAllActiveMedia().collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeDetailMediaId by remember { mutableStateOf<String?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }

    // Hardware / gesture back closes an open detail or search before exiting tab.
    BackHandler(enabled = isSearchActive || activeDetailMediaId != null) {
        if (activeDetailMediaId != null) {
            activeDetailMediaId = null
        } else if (isSearchActive) {
            isSearchActive = false
        }
    }

    if (activeDetailMediaId != null) {
        DetailScreen(
            mediaId = activeDetailMediaId!!,
            container = container,
            onBack = { activeDetailMediaId = null }
        )
        return
    }

    if (isSearchActive) {
        com.vara.mediatracker.ui.search.SearchScreen(
            items = mediaItems,
            container = container,
            onSelectMedia = {
                activeDetailMediaId = it
                isSearchActive = false
            },
            onBack = { isSearchActive = false }
        )
        return
    }

    Scaffold(
        bottomBar = {
            ExpressiveNavigationBar(
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                    0 -> HomeScreen(
                        items = mediaItems,
                        container = container,
                        onSelectMedia = { activeDetailMediaId = it },
                        onOpenCalendar = { selectedTab = 2 },
                        onOpenSearch = { isSearchActive = true }
                    )
                    1 -> com.vara.mediatracker.ui.smarthub.SmartHubScreen(
                        items = mediaItems,
                        container = container,
                        onSelectMedia = { activeDetailMediaId = it },
                        onOpenSearch = { isSearchActive = true }
                    )
                    2 -> ReleaseCalendarScreen(
                        items = mediaItems,
                        container = container,
                        onSelectMedia = { activeDetailMediaId = it },
                        onOpenSearch = { isSearchActive = true }
                    )
                    3 -> LibraryScreen(
                        items = mediaItems,
                        container = container,
                        onSelectMedia = { activeDetailMediaId = it },
                        onOpenSearch = { isSearchActive = true }
                    )
                    else -> SettingsScreen(
                        container = container,
                        onThemePresetChanged = onThemePresetChanged,
                        onThemeModeChanged = onThemeModeChanged,
                        onOpenSearch = { isSearchActive = true }
                    )
                }
            }
        }
    }
}
