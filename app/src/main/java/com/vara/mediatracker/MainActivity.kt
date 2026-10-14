package com.vara.mediatracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
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
import com.vara.mediatracker.ui.common.AppToastHost
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.ui.common.LocalOverlayOpen
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
import androidx.compose.runtime.saveable.rememberSaveable
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
        // Badges expire on a timer, so refresh them when the app opens.
        SyncScheduler.refreshBadgesOnForeground(this)
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


/**
 * System-back handling for the detail/search overlay that is immune to registration order.
 *
 * `OnBackPressedDispatcher` invokes the *most recently added enabled* callback. A screen's
 * `BackHandler` is added when that screen appears, so it always outranks an overlay handler
 * that was added once at startup. Here the callback is (re)added whenever [overlay] changes,
 * so while an overlay is open it is the newest callback in the deque — the first one asked
 * to handle back — no matter what the tab tree has registered underneath.
 *
 * When [overlay] is `None` the callback is not registered at all, so the root back gesture
 * falls through to the system (back-to-home).
 */
@Composable
private fun OverlayBackHandler(overlay: Overlay, onBack: () -> Unit) {
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
        "No OnBackPressedDispatcherOwner was provided via LocalOnBackPressedDispatcherOwner"
    }.onBackPressedDispatcher
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = currentOnBack()
        }
    }
    DisposableEffect(dispatcher, overlay) {
        if (overlay != Overlay.None) {
            callback.isEnabled = true
            dispatcher.addCallback(callback)
        }
        onDispose { callback.remove() }
    }
}


/**
 * Full-screen screens pushed on top of the tab tree.
 *
 * [depth] orders them for the push/pop transition direction (a deeper screen slides in
 * from the right; popping slides back to the right).
 */
private sealed interface Overlay {
    data object None : Overlay
    data object Search : Overlay
    data class Detail(val id: String) : Overlay
}

private fun Overlay.depth(): Int = when (this) {
    Overlay.None -> 0
    Overlay.Search -> 1
    is Overlay.Detail -> 2
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainAppScreen(
    container: AppContainer,
    onThemePresetChanged: (Int) -> Unit = {},
    onThemeModeChanged: (Int) -> Unit = {}
) {
    // Shared, activity-scoped library state. Collecting it here (rather than a second
    // `getAllActiveMedia()` subscription) means Room runs the hot query once per DB
    // change instead of twice, and every screen reads the same off-main build.
    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val core by libraryVm.core.collectAsState()
    val mediaItems = core.items
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var activeDetailMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    val overlay: Overlay = when {
        activeDetailMediaId != null -> Overlay.Detail(activeDetailMediaId!!)
        isSearchActive -> Overlay.Search
        else -> Overlay.None
    }

    CompositionLocalProvider(LocalOverlayOpen provides (overlay != Overlay.None)) {
    Box(modifier = Modifier.fillMaxSize()) {
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
            // `paddingValues` is deliberately unused. The tab tree is edge-to-edge and each
            // screen applies its own insets (statusBarsPadding) while the floating nav bar
            // applies navigationBarsPadding. Applying it here would double up those insets,
            // and *consuming* it (the obvious-looking fix for the lint warning) zeroes the
            // status-bar inset for every descendant — which pulls the headers up under the
            // system status bar. Hence the suppression rather than a "use".
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
                        (slideInHorizontally(
                            animationSpec = tween(motion.STANDARD, easing = motion.emphasizedEase)
                        ) { width -> offset * width / 8 } + fadeIn(tween(motion.STANDARD)))
                            .togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(motion.FAST, easing = motion.standardEase)
                                ) { width -> -offset * width / 8 } + fadeOut(tween(motion.FAST))
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

        // Detail / Search are pushed as a full-screen overlay instead of replacing the tab
        // tree. Replacing it disposed the tab and every `remember`ed navigation value in
        // it, so returning from a detail reset the Hub to its Smart sub-tab and dropped an
        // open section list. Keeping the tab composed preserves all of that, and the
        // overlay naturally covers the bottom bar.
        AnimatedContent(
            targetState = overlay,
            transitionSpec = {
                val forward = targetState.depth() > initialState.depth()
                val incoming = if (forward) 1 else -1
                (slideInHorizontally(
                    animationSpec = tween(motion.STANDARD, easing = motion.emphasizedEase)
                ) { width -> incoming * width } + fadeIn(tween(motion.STANDARD)))
                    .togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(motion.STANDARD, easing = motion.standardEase)
                        ) { width -> -incoming * width } + fadeOut(tween(motion.FAST))
                    )
            },
            label = "overlayTransition",
            modifier = Modifier.fillMaxSize()
        ) { ov ->
            when (ov) {
                Overlay.None -> Box(modifier = Modifier.fillMaxSize())
                Overlay.Search -> com.vara.mediatracker.ui.search.SearchScreen(
                    items = mediaItems,
                    container = container,
                    onSelectMedia = {
                        activeDetailMediaId = it
                        isSearchActive = false
                    },
                    onBack = { isSearchActive = false }
                )
                is Overlay.Detail -> Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Surface (not a bare Box) so the pushed screen provides
                    // LocalContentColor = onBackground; without it, any Text that does
                    // not set an explicit colour falls back to black and vanishes in
                    // dark mode.
                    DetailScreen(
                        mediaId = ov.id,
                        container = container,
                        onBack = { activeDetailMediaId = null },
                        onOpenMedia = { activeDetailMediaId = it }
                    )
                }
            }
        }

        // Handles system back for the detail/search overlay.
        //
        // A plain `BackHandler` here is added to the dispatcher once at startup, so any
        // in-screen handler registered later (a section list, a collection list, …) sits
        // *above* it and receives the back event first — which is how the detail used to
        // get stuck while the list underneath closed invisibly. `OverlayBackHandler`
        // re-registers on every overlay change, so while an overlay is open its callback is
        // always the most recently added, hence the highest priority, regardless of what
        // the tab tree has registered.
        OverlayBackHandler(overlay) {
            when (overlay) {
                is Overlay.Detail -> activeDetailMediaId = null
                Overlay.Search -> isSearchActive = false
                Overlay.None -> Unit
            }
        }

        // Top toast overlay, above everything (tabs + detail/search).
        AppToastHost(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )
    }
    }
}
