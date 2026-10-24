package com.vara.mediatracker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.vara.mediatracker.ui.common.AppToastHost
import com.vara.mediatracker.ui.common.LibraryViewModel
import com.vara.mediatracker.ui.m3e.M3ERoughScreen
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
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.db.EpisodeKeyReconciler
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.common.rememberNowMillis
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.theme.MediaTrackerTheme

private const val EXTRA_SHORTCUT = "shortcut"

/** Sent by a notification (and once by a widget) to open one title. */
internal const val EXTRA_OPEN_MEDIA = "com.vara.mediatracker.extra.OPEN_MEDIA"

private const val CalendarTab = 4

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { AppContainer(applicationContext) }

    /**
     * A title a notification asked us to open. Held as state rather than read once, because a tap
     * while the app is already open arrives through [onNewIntent] and has to move the screen that
     * is on top.
     */
    private var pendingOpenMedia by mutableStateOf<String?>(null)

    /** A launcher shortcut ("calendar", "search") that asked for a particular screen. */
    private var pendingShortcut by mutableStateOf<String?>(null)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenMedia = intent.getStringExtra(EXTRA_OPEN_MEDIA)
        pendingShortcut = intent.getStringExtra(EXTRA_SHORTCUT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the theme swap has to happen while the window is still being
        // created, or the splash is dismissed into a frame of the wrong background.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingOpenMedia = intent?.getStringExtra(EXTRA_OPEN_MEDIA)
        pendingShortcut = intent?.getStringExtra(EXTRA_SHORTCUT)
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
                    M3ERoughScreen(
                        container = container,
                        initialMediaId = pendingOpenMedia,
                        onInitialMediaOpened = { pendingOpenMedia = null },
                        initialShortcut = pendingShortcut,
                        onInitialShortcutHandled = { pendingShortcut = null },
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
