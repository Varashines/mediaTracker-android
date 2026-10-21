package com.vara.mediatracker.ui.m3e

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.vara.mediatracker.data.sync.ReleaseReminderPlanner
import com.vara.mediatracker.data.sync.CloudSyncManager
import com.vara.mediatracker.data.sync.DigestScheduler
import com.vara.mediatracker.data.sync.NotificationScheduler
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.LocalNowMillis
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.settings.ProviderRegions
import com.vara.mediatracker.ui.settings.regionDisplayName
import com.vara.mediatracker.ui.theme.ThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings, as the shape of the things it configures.
 *
 * A settings screen is usually a list of label/control pairs, and that is exactly what this was.
 * This one is built the other way round: each block is the shape of the thing it configures. The
 * palette is a strip of swatches because a palette is something you look at; the notification
 * channels are tiles that light up because they are on/off states; sync is an action with a status
 * rather than a switch with a label.
 *
 * Everything is symbol-led. An icon says what a row is about before the words do, and the words are
 * then only needed where a symbol cannot carry the meaning — a region code, a clock time, a key.
 * The four credential fields stay behind one key symbol rather than sitting on the page, because
 * they are set once and never read.
 *
 * No functionality was dropped from the shipping screen: sync now previews its impact first and
 * confirms, the discovery region is chosen here, the network logo toggle lives with the palette,
 * and the notification permission is requested from the block it affects.
 */
@Composable
fun M3ERoughSettings(
    container: AppContainer,
    onBack: () -> Unit,
    /** Opens the cycle/event history, which lives outside Settings but is reached from here. */
    onThemePresetChanged: (Int) -> Unit,
    onThemeModeChanged: (Int) -> Unit
) {
    val prefs = container.preferences
    val context = LocalContext.current
    val haptics = rememberHaptics()

    // ── Appearance ───────────────────────────────────────────────────────────────────────────
    var themeMode by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
    var preset by rememberSaveable { mutableIntStateOf(prefs.themePreset) }
    var detailNetworkLogo by rememberSaveable { mutableStateOf(prefs.detailNetworkLogo) }

    // ── Notifications ────────────────────────────────────────────────────────────────────────
    var notificationsEnabled by rememberSaveable { mutableStateOf(prefs.notificationsEnabled) }
    var notificationsMovies by rememberSaveable { mutableStateOf(prefs.notificationsMovies) }
    var notificationsTv by rememberSaveable { mutableStateOf(prefs.notificationsTv) }
    var deliverySeconds by rememberSaveable { mutableIntStateOf(prefs.notificationsTime) }
    var digestEnabled by rememberSaveable { mutableStateOf(prefs.digestEnabled) }
    var digestWeekday by rememberSaveable { mutableIntStateOf(prefs.digestWeekday) }
    var digestTime by rememberSaveable { mutableIntStateOf(prefs.digestTime) }
    var pickingWeekday by rememberSaveable { mutableStateOf(false) }
    // 0 = notification delivery, 1 = digest time, null = closed. One slot, like the overlay stack:
    // two pickers cannot be open at once by construction.
    var timePicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }

    // ── Sync ─────────────────────────────────────────────────────────────────────────────────
    var keysShown by rememberSaveable { mutableStateOf(false) }
    var backgroundSync by rememberSaveable { mutableStateOf(SyncScheduler.isEnabled(context)) }
    var syncing by remember { mutableStateOf(false) }
    var checkingImpact by remember { mutableStateOf(false) }
    var lastSyncedAt by remember { mutableLongStateOf(SyncScheduler.lastSyncTimestamp(context)) }
    var pendingImpact by remember { mutableStateOf<CloudSyncManager.SyncImpact?>(null) }
    var lastSummary by remember { mutableStateOf<CloudSyncManager.SyncSummary?>(null) }

    // ── Discovery ────────────────────────────────────────────────────────────────────────────
    var providerRegion by rememberSaveable { mutableStateOf(prefs.providerRegion) }
    var regionPickerShown by rememberSaveable { mutableStateOf(false) }

    // Credentials are staged locally and written on a debounce, so a key pasted or retyped commits
    // once instead of on every keystroke. Restarting the effect on each change is the debounce.
    var supabaseUrl by rememberSaveable { mutableStateOf(prefs.supabaseUrl) }
    var supabaseAnonKey by rememberSaveable { mutableStateOf(prefs.supabaseAnonKey) }
    var syncKey by rememberSaveable { mutableStateOf(prefs.syncKey) }
    var tmdbKey by rememberSaveable { mutableStateOf(prefs.tmdbApiKey) }
    LaunchedEffect(supabaseUrl, supabaseAnonKey, syncKey, tmdbKey) {
        delay(400)
        prefs.saveCredentials(supabaseUrl, supabaseAnonKey, syncKey, tmdbKey)
    }

    val scope = rememberCoroutineScope()

    // Read through the shared clock, so the wording ages on its own — "just now" becomes "5 minutes
    // ago" without the screen having to be reopened.
    val nowMillis = LocalNowMillis.current
    val lastSyncedLabel = remember(lastSyncedAt, nowMillis) {
        if (lastSyncedAt == 0L) {
            "Never synced"
        } else {
            "Last synced " + DateUtils.getRelativeTimeSpanString(
                lastSyncedAt,
                nowMillis,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }
    }
    val isCloudConnected = supabaseUrl.isNotBlank() && syncKey.isNotBlank()
    val isTmdbConnected = tmdbKey.isNotBlank()

    val reschedule: () -> Unit = {
        scope.launch {
            NotificationScheduler.syncAll(
                context.applicationContext,
                ReleaseReminderPlanner.plan(container)
            )
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        if (granted) reschedule()
    }

    // The digest is periodic work, so a change re-registers it rather than re-planning a list.
    // Values are passed in rather than read back from the state: a handler that has just changed
    // one would otherwise re-register the old value it captured before the change.
    fun applyDigest(enabled: Boolean, weekday: Int, time: Int) {
        val appContext = context.applicationContext
        if (enabled) {
            DigestScheduler.schedule(appContext, weekday, time)
        } else {
            DigestScheduler.cancel(appContext)
        }
    }

    // Preview the diff before syncing, the way the Mac does: the point of the sheet is that a sync
    // is two-way, and the counts are the only place that is visible before it runs.
    fun previewSync() {
        if (!isCloudConnected) {
            AppToast.show("Add your keys first")
            keysShown = true
            return
        }
        checkingImpact = true
        container.appScope.launch {
            val result = container.syncManager().computeImpact(since = prefs.syncWatermark)
            withContext(Dispatchers.Main) {
                checkingImpact = false
                if (result.isSuccess) {
                    pendingImpact = result.getOrNull()
                } else {
                    AppToast.show("Couldn't check: ${result.exceptionOrNull()?.localizedMessage}")
                }
            }
        }
    }

    fun runSync() {
        syncing = true
        container.appScope.launch {
            val result = container.syncManager().sync(since = prefs.syncWatermark)
            withContext(Dispatchers.Main) {
                syncing = false
                if (result.isSuccess) {
                    result.getOrNull()?.watermark?.let { prefs.syncWatermark = it }
                    val at = System.currentTimeMillis()
                    SyncScheduler.recordSyncCompletion(context, at)
                    lastSyncedAt = at
                    lastSummary = result.getOrNull()
                    val s = result.getOrNull()
                    AppToast.show("Synced — ↑ ${s?.outgoing ?: 0} sent, ↓ ${s?.incoming ?: 0} received")
                } else {
                    AppToast.show("Sync failed: ${result.exceptionOrNull()?.localizedMessage}")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                title = "Settings",
                eyebrow = "Preferences",
                // A symbol rather than a word: the colour is the whole message, and it is the same
                // message the Mac's row carries in its subtitle.
                trailing = { ConnectionMark(connected = isCloudConnected) }
            )
        }

        // ── Appearance ───────────────────────────────────────────────────────────────────────
        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    PaletteStrip(
                        selected = preset,
                        onSelect = { index ->
                            haptics.tap()
                            preset = index
                            prefs.themePreset = index
                            onThemePresetChanged(index)
                        }
                    )
                    Spacer(Modifier.height(M3ESpacing.cardGap))
                    ThemeSelector(
                        selected = themeMode,
                        onSelect = { mode ->
                            haptics.tap()
                            themeMode = mode
                            prefs.themeMode = mode
                            onThemeModeChanged(mode)
                        }
                    )
                    Spacer(Modifier.height(M3ESpacing.cardGap))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    IconSwitchRow(
                        icon = Icons.Default.Tv,
                        label = "Network logo in detail",
                        checked = detailNetworkLogo,
                        onToggle = {
                            detailNetworkLogo = it
                            prefs.detailNetworkLogo = it
                        }
                    )
                }
            }
        }

        // ── Notifications ────────────────────────────────────────────────────────────────────
        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                    ) {
                        ToggleTile(
                            icon = Icons.Default.Notifications,
                            label = "All",
                            checked = notificationsEnabled,
                            modifier = Modifier.weight(1f),
                            onToggle = { enabled ->
                                haptics.toggle()
                                notificationsEnabled = enabled
                                prefs.notificationsEnabled = enabled
                                if (enabled) {
                                    if (hasNotificationPermission(context)) {
                                        reschedule()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                } else {
                                    NotificationScheduler.cancelAll(context.applicationContext)
                                }
                            }
                        )
                        ToggleTile(
                            icon = Icons.Default.Movie,
                            label = "Films",
                            checked = notificationsEnabled && notificationsMovies,
                            enabled = notificationsEnabled,
                            modifier = Modifier.weight(1f),
                            onToggle = { on ->
                                haptics.toggle()
                                notificationsMovies = on
                                prefs.notificationsMovies = on
                                reschedule()
                            }
                        )
                        ToggleTile(
                            icon = Icons.Default.Tv,
                            label = "TV",
                            checked = notificationsEnabled && notificationsTv,
                            enabled = notificationsEnabled,
                            modifier = Modifier.weight(1f),
                            onToggle = { on ->
                                haptics.toggle()
                                notificationsTv = on
                                prefs.notificationsTv = on
                                reschedule()
                            }
                        )
                        ToggleTile(
                            icon = Icons.Default.Schedule,
                            label = "Digest",
                            checked = notificationsEnabled && digestEnabled,
                            enabled = notificationsEnabled,
                            modifier = Modifier.weight(1f),
                            onToggle = { on ->
                                haptics.toggle()
                                digestEnabled = on
                                prefs.digestEnabled = on
                                applyDigest(on, digestWeekday, digestTime)
                            }
                        )
                    }

                    // The permission is asked for from the block it affects, and only when the
                    // switch that needs it is on and Android has not already granted it.
                    if (notificationsEnabled && !notificationsAllowed) {
                        Spacer(Modifier.height(M3ESpacing.cardGap))
                        Button(
                            onClick = {
                                haptics.tap()
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            shape = M3EShape.chip(),
                            colors = tintedButtonColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(M3ESpacing.rowMinHeight)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(M3ESpacing.tightGap))
                            Text("Allow notifications", style = M3EType.action())
                        }
                    }

                    if (notificationsEnabled) {
                        Spacer(Modifier.height(M3ESpacing.cardGap))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        IconValueRow(
                            icon = Icons.Default.Schedule,
                            label = "Delivery time",
                            value = clockLabel(deliverySeconds)
                        ) {
                            timePicker = 0
                        }
                        if (digestEnabled) {
                            IconValueRow(
                                icon = Icons.Default.Notifications,
                                label = "Digest day",
                                value = weekdayLabel(digestWeekday)
                            ) {
                                haptics.tap()
                                pickingWeekday = true
                            }
                            IconValueRow(
                                icon = Icons.Default.Schedule,
                                label = "Digest time",
                                value = clockLabel(digestTime)
                            ) {
                                timePicker = 1
                            }
                        }
                    }
                }
            }
        }

        // ── Sync ─────────────────────────────────────────────────────────────────────────────
        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    // Symbol-led: the cloud carries the meaning, the word only says what pressing it
                    // does. It previews first, because a sync runs both ways and the counts are the
                    // only warning you get.
                    Button(
                        onClick = {
                            if (syncing || checkingImpact) return@Button
                            haptics.tap()
                            previewSync()
                        },
                        enabled = !syncing && !checkingImpact,
                        shape = M3EShape.chip(),
                        colors = tintedButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(M3ESpacing.rowMinHeight)
                    ) {
                        if (syncing || checkingImpact) {
                            // M3E's morphing indicator, not a ring. Same job, in the vocabulary the
                            // rest of the screen is written in.
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = M3EColor.accent()
                            )
                        } else {
                            Icon(
                                imageVector = if (isCloudConnected) {
                                    Icons.Default.CloudSync
                                } else {
                                    Icons.Default.CloudOff
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(M3ESpacing.tightGap))
                        Text(
                            text = when {
                                syncing -> "Syncing…"
                                checkingImpact -> "Checking…"
                                else -> "Sync now"
                            },
                            style = M3EType.action()
                        )
                    }

                    // The same stamp the background worker writes, so the line covers both without
                    // anyone having to know which one ran. The summary replaces it after a manual
                    // sync, because the counts are more useful than the clock for the next minute.
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    Text(
                        text = lastSummary?.let {
                            "↑ ${it.outgoing} sent · ↓ ${it.incoming} received"
                        } ?: lastSyncedLabel,
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted(),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(Modifier.height(M3ESpacing.cardGap))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    IconSwitchRow(
                        icon = Icons.Default.Schedule,
                        label = "Background sync",
                        subtitle = if (backgroundSync) "Every 30 minutes, while closed" else "Off",
                        checked = backgroundSync,
                        onToggle = {
                            backgroundSync = it
                            SyncScheduler.setEnabled(context, it)
                            if (it) SyncScheduler.syncNow(context)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Four fields behind one symbol: they are set once and never read, so they get a
                    // key rather than a page.
                    IconActionRow(
                        icon = Icons.Default.Key,
                        label = "Keys",
                        detail = when {
                            isCloudConnected && isTmdbConnected -> "Cloud and TMDB"
                            isCloudConnected -> "Cloud"
                            isTmdbConnected -> "TMDB only"
                            else -> "Not set"
                        }
                    ) {
                        haptics.tap()
                        keysShown = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // A button, not a row with a chevron: a chevron says "there is another screen
                    // behind this", and this does something where it stands.
                    Spacer(Modifier.height(M3ESpacing.cardGap))
                    Button(
                        onClick = {
                            haptics.tap()
                            SyncScheduler.enrichEpisodesNow(context, forceAll = true)
                            AppToast.show("Refreshing episodes…")
                        },
                        shape = M3EShape.chip(),
                        colors = tintedButtonColors(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(M3ESpacing.tightGap))
                        Text("Refresh episodes", style = M3EType.action())
                    }
                }
            }
        }

        // ── Discovery ────────────────────────────────────────────────────────────────────────
        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    IconValueRow(
                        icon = Icons.Default.Public,
                        label = "Discovery region",
                        value = if (providerRegion.isBlank()) {
                            "Auto"
                        } else {
                            regionDisplayName(providerRegion)
                        }
                    ) {
                        haptics.tap()
                        regionPickerShown = true
                    }
                }
            }
        }

        item {
            Text(
                text = "MediaTracker · ${ThemePresets.getPreset(preset).name}",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }

    if (keysShown) {
        AlertDialog(
            onDismissRequest = { keysShown = false },
            title = { Text("Keys") },
            text = {
                // Scrollable: four fields plus the note can outgrow a small window, and a dialog
                // that cannot scroll simply cuts the last one off.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "The four this app syncs with. Stored on this device only.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                    CredentialField(
                        label = "Supabase URL",
                        value = supabaseUrl,
                        onValueChange = { supabaseUrl = it }
                    )
                    CredentialField(
                        label = "Supabase anon key",
                        value = supabaseAnonKey,
                        onValueChange = { supabaseAnonKey = it },
                        masked = true
                    )
                    CredentialField(
                        label = "Sync key",
                        value = syncKey,
                        onValueChange = { syncKey = it },
                        masked = true
                    )
                    CredentialField(
                        label = "TMDB API key",
                        value = tmdbKey,
                        onValueChange = { tmdbKey = it },
                        masked = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { keysShown = false }) { Text("Done") }
            }
        )
    }

    timePicker?.let { which ->
        TimePickerPopup(
            title = if (which == 0) "Delivery time" else "Digest time",
            initialSeconds = if (which == 0) deliverySeconds else digestTime,
            onDismiss = { timePicker = null },
            onConfirm = { seconds ->
                if (which == 0) {
                    deliverySeconds = seconds
                    prefs.notificationsTime = seconds
                    reschedule()
                } else {
                    digestTime = seconds
                    prefs.digestTime = seconds
                    applyDigest(true, digestWeekday, seconds)
                }
            }
        )
    }

    if (pickingWeekday) {
        AlertDialog(
            onDismissRequest = { pickingWeekday = false },
            title = { Text("Digest day") },
            text = {
                Column {
                    (1..7).forEach { day ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(M3EShape.chip())
                                .clickable {
                                    haptics.tap()
                                    digestWeekday = day
                                    prefs.digestWeekday = day
                                    applyDigest(true, day, digestTime)
                                    pickingWeekday = false
                                }
                                .padding(vertical = M3ESpacing.rowGap),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (day == digestWeekday) {
                                    Icons.Default.Check
                                } else {
                                    Icons.Default.Schedule
                                },
                                contentDescription = null,
                                tint = if (day == digestWeekday) {
                                    M3EColor.accent()
                                } else {
                                    M3EColor.textMuted()
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(M3ESpacing.inlineGap))
                            Text(
                                text = weekdayLabel(day),
                                style = M3EType.rowTitle(),
                                color = if (day == digestWeekday) M3EColor.accent() else M3EColor.text()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingWeekday = false }) { Text("Done") }
            }
        )
    }

    if (regionPickerShown) {
        AlertDialog(
            onDismissRequest = { regionPickerShown = false },
            title = { Text("Discovery region") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RegionOption(
                        label = "Auto — follow this device",
                        selected = providerRegion.isBlank()
                    ) {
                        haptics.tap()
                        providerRegion = ""
                        prefs.providerRegion = ""
                        SyncScheduler.enrichMetadataNow(context)
                        regionPickerShown = false
                    }
                    ProviderRegions.forEach { code ->
                        RegionOption(
                            label = "${regionDisplayName(code)} ($code)",
                            selected = providerRegion == code
                        ) {
                            haptics.tap()
                            providerRegion = code
                            prefs.providerRegion = code
                            SyncScheduler.enrichMetadataNow(context)
                            regionPickerShown = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { regionPickerShown = false }) { Text("Done") }
            }
        )
    }

    pendingImpact?.let { impact ->
        ModalBottomSheet(onDismissRequest = { pendingImpact = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
                    .padding(bottom = M3ESpacing.sectionGap)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(M3EColor.accent().copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = M3EColor.accent(),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(M3ESpacing.inlineGap))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cloud sync", style = M3EType.rowTitle(), fontWeight = FontWeight.SemiBold, color = M3EColor.text())
                        Text(
                            text = if (impact.hasChanges) {
                                "↑ ${impact.totalOutgoing} out · ↓ ${impact.totalIncoming} in"
                            } else {
                                "No title changes — episodes and history still sync"
                            },
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                    }
                }

                ImpactSection(
                    icon = Icons.Default.WarningAmber,
                    title = "Conflicts",
                    count = impact.conflictTitles.size,
                    tint = M3EColor.warning(),
                    summary = "Kept by both sides but disagreeing. The newest edit wins.",
                    names = impact.conflictTitles,
                    initiallyExpanded = true
                )
                ImpactSection(
                    icon = Icons.Default.CloudDownload,
                    title = "Incoming",
                    count = impact.incomingTitles.size + impact.incomingEpisodes +
                        impact.incomingCollections.size + impact.incomingCollectionItems,
                    tint = M3EColor.accent(),
                    summary = "${impact.incomingTitles.size} titles · ${impact.incomingEpisodes} episodes · " +
                        "${impact.incomingCollections.size + impact.incomingCollectionItems} collection rows",
                    names = impact.incomingTitles + impact.incomingCollections
                )
                ImpactSection(
                    icon = Icons.Default.CloudUpload,
                    title = "Uploads",
                    count = impact.outgoingTitles.size + impact.outgoingEpisodes +
                        impact.outgoingCollections.size + impact.outgoingCollectionItems,
                    tint = M3EColor.positive(),
                    summary = "${impact.outgoingTitles.size} titles · ${impact.outgoingEpisodes} episodes · " +
                        "${impact.outgoingCollections.size + impact.outgoingCollectionItems} collection rows",
                    names = impact.outgoingTitles + impact.outgoingCollections
                )
                ImpactSection(
                    icon = Icons.Default.History,
                    title = "History",
                    count = impact.incomingCycles + impact.outgoingCycles +
                        impact.incomingEvents + impact.outgoingEvents,
                    tint = M3EColor.accent(),
                    summary = "${impact.outgoingCycles} cycles · ${impact.outgoingEvents} events up, " +
                        "${impact.incomingCycles} cycles · ${impact.incomingEvents} events down"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                ) {
                    OutlinedButton(
                        onClick = { pendingImpact = null },
                        shape = M3EShape.chip(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel", style = M3EType.action()) }

                    Button(
                        onClick = {
                            pendingImpact = null
                            runSync()
                        },
                        enabled = !syncing,
                        shape = M3EShape.chip(),
                        colors = tintedButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (syncing) {
                            LoadingIndicator(
                                modifier = Modifier.size(16.dp),
                                color = M3EColor.accent()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Sync now", style = M3EType.action())
                        }
                    }
                }
            }
        }
    }
}

/**
 * Connection state as a symbol.
 *
 * The shipping screen wrote CONNECTED or STANDALONE in a pill. The colour and the cloud already say
 * it, and a word in a header is the one place a label is pure decoration — the screen reader still
 * gets it from the description.
 */
@Composable
private fun ConnectionMark(connected: Boolean) {
    Surface(
        shape = CircleShape,
        color = M3EColor.card(),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (connected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = if (connected) "Cloud connected" else "Standalone — no cloud configured",
                tint = if (connected) M3EColor.positive() else M3EColor.warning(),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * The six palettes as a strip of swatches, with the one in use named under it.
 *
 * A palette is a thing you look at, so it is shown rather than listed: six circles at a size you
 * can actually judge, the chosen one ringed and checked, and its name underneath. The name moves
 * with the choice, which is the one piece of text this needs.
 */
@Composable
private fun PaletteStrip(selected: Int, onSelect: (Int) -> Unit) {
    val presets = ThemePresets.presets
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
        ) {
            presets.forEachIndexed { index, preset ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        // Clipped before the click, so the ripple follows the shape. Unclipped it
                        // was a sharp rectangle around a round swatch.
                        .clip(CircleShape)
                        .clickable { onSelect(index) }
                        .background(preset.accentColor, CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                M3EColor.text()
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            // Contrast against the swatch, not the page: the presets run from
                            // near-white to near-black.
                            tint = if (preset.accentColor.luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(M3ESpacing.tightGap))
        Text(
            text = presets.getOrNull(selected)?.name ?: "Custom",
            style = M3EType.rowTitle(),
            color = M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Auto / Dark / Light as one control: they are one choice, not three actions. */
@Composable
private fun ThemeSelector(selected: Int, onSelect: (Int) -> Unit) {
    val modes = listOf(
        Triple(0, "Auto", Icons.Default.BrightnessAuto),
        Triple(1, "Dark", Icons.Default.DarkMode),
        Triple(2, "Light", Icons.Default.LightMode)
    )
    Surface(
        shape = M3EShape.chip(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            modes.forEach { (mode, label, icon) ->
                val isSelected = selected == mode
                Surface(
                    onClick = { onSelect(mode) },
                    shape = M3EShape.chip(),
                    color = if (isSelected) M3EColor.accent() else Color.Transparent,
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        M3EColor.textMuted()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = M3ESpacing.rowGap),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = label, style = M3EType.action())
                    }
                }
            }
        }
    }
}

/**
 * One notification channel as a tile that lights up.
 *
 * A switch says "this is on"; a tile says it too, and says which channel it is at the same time —
 * and four of them side by side read as the set they are. Tinted with the accent when on, and
 * dimmed when the master switch is off, because then the channel is not in play whatever it says.
 */
@Composable
private fun ToggleTile(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    val tint = if (checked) M3EColor.accent() else M3EColor.textMuted()
    Surface(
        onClick = { onToggle(!checked) },
        shape = M3EShape.card(),
        color = if (checked) {
            M3EColor.accent().copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier
            .height(84.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(M3ESpacing.tightGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = M3EType.rowSupporting(),
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (checked) M3EColor.text() else M3EColor.textMuted(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A row that is a symbol and a switch: the icon says what, the switch says whether. */
@Composable
private fun IconSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) M3EColor.accent() else M3EColor.textMuted(),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = M3EType.rowTitle(), color = M3EColor.text())
            if (subtitle != null) {
                Text(text = subtitle, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** A row that is a symbol, a name, and a value you tap to change. */
@Composable
private fun IconValueRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.chip())
            .clickable { onClick() }
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = M3EColor.textMuted(),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Text(
            text = label,
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted(),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = M3EType.rowTitleStrong(),
            color = M3EColor.accent()
        )
    }
}

/** A row that is a symbol, a name, a small state, and a chevron: it opens something. */
@Composable
private fun IconActionRow(
    icon: ImageVector,
    label: String,
    detail: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.chip())
            .clickable { onClick() }
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = M3EColor.accent(),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = M3EType.rowTitle(), color = M3EColor.text())
            if (detail != null) {
                Text(text = detail, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
            }
        }
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = M3EColor.textMuted(),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** One category of the sync preview: symbol, name, count, and a tap-to-expand body. */
@Composable
private fun ImpactSection(
    icon: ImageVector,
    title: String,
    count: Int,
    tint: Color,
    summary: String,
    names: List<String> = emptyList(),
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded && count > 0) }
    val idle = M3EColor.textMuted()
    val colour = if (count > 0) tint else idle

    Surface(
        shape = M3EShape.card(),
        color = M3EColor.card(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(M3EShape.chip())
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(M3ESpacing.inlineGap))
                Text(
                    text = title,
                    style = M3EType.rowTitleStrong(),
                    color = M3EColor.text()
                )
                Spacer(Modifier.weight(1f))
                // The count as a badge rather than a word: "3" reads faster than "3 conflicts", and
                // the title already said what it is counting.
                Text(
                    text = count.toString(),
                    style = M3EType.action(),
                    fontWeight = FontWeight.Bold,
                    color = colour,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colour.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(M3ESpacing.tightGap))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = idle,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                Text(summary, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
                if (names.isNotEmpty()) {
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    names.take(8).forEach { name ->
                        Text(
                            text = "· $name",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (names.size > 8) {
                        Text(
                            text = "+ ${names.size - 8} more",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.accent()
                        )
                    }
                }
            }
        }
    }
}

/** One row in the region dialog: a tick for the chosen one, a globe for the rest. */
@Composable
private fun RegionOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.chip())
            .clickable { onClick() }
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.Check else Icons.Default.Public,
            contentDescription = null,
            tint = if (selected) M3EColor.accent() else M3EColor.textMuted(),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Text(
            text = label,
            style = M3EType.rowTitle(),
            color = if (selected) M3EColor.accent() else M3EColor.text(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One credential. Masked fields get a reveal, because a key is pasted as often as it is read. */
@Composable
private fun CredentialField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    masked: Boolean = false
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = M3EShape.chip(),
        visualTransformation = if (masked && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (masked) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (revealed) "Hide $label" else "Show $label"
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = M3ESpacing.tightGap)
    )
}

/**
 * The M3 time picker, in a dialog.
 *
 * It was the platform `TimePickerDialog` — the one thing in this screen that was not Material at all,
 * in the screen that is otherwise the most deliberately M3E. The platform dialog is a 2014 widget: no
 * theme, no shape, no relation to anything around it.
 */
@Composable
private fun TimePickerPopup(
    title: String,
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialSeconds / 3600,
        initialMinute = (initialSeconds % 3600) / 60,
        is24Hour = true
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = M3EShape.hero(), color = M3EColor.card()) {
            Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                Text(title, style = M3EType.rowTitle(), fontWeight = FontWeight.SemiBold, color = M3EColor.text())
                Spacer(Modifier.height(M3ESpacing.tightGap))
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", style = M3EType.action()) }
                    TextButton(
                        onClick = {
                            onConfirm(state.hour * 3600 + state.minute * 60)
                            onDismiss()
                        }
                    ) { Text("Set", style = M3EType.action(), color = M3EColor.accent()) }
                }
            }
        }
    }
}

/** The Mac stores weekdays 1..7 starting at Sunday; this reads them in that order. */
private val WEEKDAY_NAMES = listOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
)

private fun weekdayLabel(macWeekday: Int): String =
    WEEKDAY_NAMES.getOrElse(macWeekday - 1) { "Sunday" }

private fun clockLabel(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d", seconds / 3600, (seconds % 3600) / 60)

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
