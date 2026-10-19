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
                    M3ERoughScreen(
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
