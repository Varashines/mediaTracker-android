package com.vara.mediatracker.ui.m3e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
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
import androidx.core.content.FileProvider
import com.vara.mediatracker.data.backup.LibraryBackupService
import com.vara.mediatracker.ui.common.ManualSync
import com.vara.mediatracker.data.sync.CloudSyncManager
import com.vara.mediatracker.data.sync.DigestScheduler
import com.vara.mediatracker.data.sync.NotificationScheduler
import com.vara.mediatracker.data.sync.ReleaseReminderPlanner
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
 * Settings, as a grouped list.
 *
 * The screen is a small number of sections, each a label over one container of rows. A row is an
 * icon, a name, and — where the name alone is not the whole story — a supporting line that says
 * what it does or what it is currently set to. The control lives at the end of the row it belongs
 * to, so a switch or a value is read in the same glance as the thing it changes.
 *
 * This is deliberately the most conventional screen in the app. Every other screen is somewhere to
 * look; this one is somewhere to find a switch, and the fastest way to find a switch is for it to be
 * where the last forty apps put theirs.
 *
 * Nothing was dropped from the previous screen: the palette and theme mode moved into rows that
 * open a picker, the notification channels moved from four tiles into four rows, sync previews its
 * impact before it runs, and the four credentials stay behind one key row because they are set once
 * and never read.
 */
@Composable
fun M3ERoughSettings(
    container: AppContainer,
    onBack: () -> Unit,
    onThemePresetChanged: (Int) -> Unit,
    onThemeModeChanged: (Int) -> Unit
) {
    val prefs = container.preferences
    val context = LocalContext.current
    val haptics = rememberHaptics()

    // ── Appearance ───────────────────────────────────────────────────────────────────────────
    var themeMode by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
    var preset by rememberSaveable { mutableIntStateOf(prefs.themePreset) }
    var maintenanceShown by rememberSaveable { mutableStateOf(false) }
    var backupShown by rememberSaveable { mutableStateOf(false) }

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
    var lastSyncedAt by remember { mutableLongStateOf(SyncScheduler.lastSyncTimestamp(context)) }
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

    // Backup import is a document picker rather than an extension check: a backup restored on a
    // new phone comes from wherever the user put it (Drive, email, a USB stick).
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching { LibraryBackupService.import(container, uri, context) }
            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = { summary ->
                        AppToast.show(
                            "Restored ${summary.media} titles and ${summary.episodes} episodes"
                        )
                    },
                    onFailure = { AppToast.show("Import failed: ${it.message ?: "bad file"}") }
                )
            }
        }
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

    fun exportBackup() {
        scope.launch {
            val outcome = runCatching { LibraryBackupService.export(container, context) }
            val file = outcome.getOrElse {
                withContext(Dispatchers.Main) { AppToast.show("Export failed: ${it.message ?: "unknown"}") }
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(share, "Save library backup"))
            }
        }
    }

    /** Pulls everything again. The only sync Settings offers now; the everyday one is on Home. */
    fun resync() {
        scope.launch {
            val summary = ManualSync.run(container, context, resetWatermark = true)
            withContext(Dispatchers.Main) {
                if (summary != null) {
                    lastSyncedAt = SyncScheduler.lastSyncTimestamp(context)
                    lastSummary = summary
                    AppToast.show("Synced — ↑ ${summary.outgoing} sent, ↓ ${summary.incoming} received")
                } else {
                    AppToast.show("Sync failed — check Keys")
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

            // A plain way out, then the title. The arrow sits where it does on every other Android
            // screen, and it goes where the system back gesture goes: home.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptics.tap()
                        onBack()
                    },
                    // Pulled left by its own touch padding, so the glyph — not the 48dp box — lines
                    // up with the page margin above the title and with the cards below it.
                    modifier = Modifier.offset(x = (-12).dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = M3EColor.text()
                    )
                }
                Spacer(Modifier.weight(1f))
                ConnectionMark(connected = isCloudConnected)
            }
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = "Settings",
                style = M3EType.screenTitle(),
                color = M3EColor.text()
            )
        }

        // ── Appearance ───────────────────────────────────────────────────────────────────────
        item {
            SettingsSection(
                label = "Appearance",
                rows = {
                    // The theme and the palette are the two things this screen exists to change, so
                    // they are the controls themselves rather than rows that open another surface.
                    // A choice you can see and tap beats a choice you have to open, and it removes
                    // two dialogs from the screen's vocabulary.
                    Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                        ThemeSegmented(
                            selected = themeMode,
                            onSelect = { mode ->
                                haptics.tap()
                                themeMode = mode
                                prefs.themeMode = mode
                                onThemeModeChanged(mode)
                            }
                        )
                        Spacer(Modifier.height(M3ESpacing.cardGap))
                        PaletteStrip(
                            selected = preset,
                            onSelect = { index ->
                                haptics.tap()
                                preset = index
                                prefs.themePreset = index
                                onThemePresetChanged(index)
                            }
                        )
                    }
                }
            )
        }

        // ── Notifications ────────────────────────────────────────────────────────────────────
        item {
            SettingsSection(
                label = "Notifications",
                rows = {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Release reminders",
                        subtitle = "Something on your list is out today",
                        checked = notificationsEnabled,
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

                    // The permission is asked for from the row it affects, and only while that row
                    // is on and Android has not already granted it.
                    if (notificationsEnabled && !notificationsAllowed) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Notifications,
                            title = "Allow notifications",
                            subtitle = "Android needs your permission",
                            emphasise = true,
                            onClick = {
                                haptics.tap()
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        )
                    }

                    SettingsDivider()
                    // Two channels, one row. They are the same kind of choice (which releases
                    // interrupt you) so they read as a pair, and as toggles their state is visible
                    // without a second line of explanation.
                    ChannelToggles(
                        movies = notificationsEnabled && notificationsMovies,
                        tv = notificationsEnabled && notificationsTv,
                        enabled = notificationsEnabled,
                        onMovies = { on ->
                            haptics.toggle()
                            notificationsMovies = on
                            prefs.notificationsMovies = on
                            reschedule()
                        },
                        onTv = { on ->
                            haptics.toggle()
                            notificationsTv = on
                            prefs.notificationsTv = on
                            reschedule()
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Schedule,
                        title = "Weekly digest",
                        subtitle = "One summary of the week, on a day you pick",
                        checked = notificationsEnabled && digestEnabled,
                        enabled = notificationsEnabled,
                        onToggle = { on ->
                            haptics.toggle()
                            digestEnabled = on
                            prefs.digestEnabled = on
                            applyDigest(on, digestWeekday, digestTime)
                        }
                    )

                    if (notificationsEnabled && digestEnabled) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Schedule,
                            title = "Digest day",
                            subtitle = weekdayLabel(digestWeekday),
                            onClick = {
                                haptics.tap()
                                pickingWeekday = true
                            }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Schedule,
                            title = "Digest time",
                            subtitle = clockLabel(digestTime),
                            onClick = { timePicker = 1 }
                        )
                    }

                    if (notificationsEnabled) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Schedule,
                            title = "Delivery time",
                            subtitle = clockLabel(deliverySeconds),
                            onClick = { timePicker = 0 }
                        )
                    }
                }
            )
        }

        // ── Cloud sync ───────────────────────────────────────────────────────────────────────
        item {
            SettingsSection(
                label = "Cloud sync",
                rows = {
                    // Sync itself lives on Home, where the library it acts on is. What stays here
                    // is the state: the last result, and whether this device is connected at all.
                    SettingsRow(
                        icon = if (isCloudConnected) {
                            Icons.Outlined.CloudSync
                        } else {
                            Icons.Outlined.CloudOff
                        },
                        title = if (isCloudConnected) "Last sync" else "Not connected",
                        subtitle = if (!isCloudConnected) {
                            "Add your keys to sync with the Mac"
                        } else {
                            lastSummary?.let {
                                "↑ ${it.outgoing} sent · ↓ ${it.incoming} received"
                            } ?: lastSyncedLabel
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Schedule,
                        title = "Background sync",
                        subtitle = if (backgroundSync) {
                            "Every 30 minutes, while closed"
                        } else {
                            "Off — sync only when you ask"
                        },
                        checked = backgroundSync,
                        onToggle = {
                            haptics.toggle()
                            backgroundSync = it
                            SyncScheduler.setEnabled(context, it)
                            if (it) SyncScheduler.syncNow(context)
                        }
                    )
                    SettingsDivider()
                    // Four fields behind one key: they are set once and never read, so they get a
                    // row rather than a page of their own.
                    SettingsRow(
                        icon = Icons.Outlined.Key,
                        title = "Keys",
                        subtitle = when {
                            isCloudConnected && isTmdbConnected -> "Cloud and TMDB"
                            isCloudConnected -> "Cloud only"
                            isTmdbConnected -> "TMDB only"
                            else -> "Not set — sync is off"
                        },
                        onClick = {
                            haptics.tap()
                            keysShown = true
                        }
                    )
                    SettingsDivider()
                    // Diagnostics, not decisions: pulling everything again or re-fetching episode
                    // data is something you do when something is wrong, so it lives one level down
                    // rather than taking two of the four rows on the everyday screen.
                    SettingsRow(
                        icon = Icons.Outlined.Tune,
                        title = "Maintenance",
                        subtitle = "Refresh episodes · resync · region",
                        onClick = {
                            haptics.tap()
                            maintenanceShown = true
                        }
                    )
                }
            )
        }

        // ── Data ─────────────────────────────────────────────────────────────────────────────
        item {
            SettingsSection(
                label = "Data",
                rows = {
                    // Cloud sync restores a library only while another device still holds it. This
                    // is the copy that survives an uninstall, a lost phone, or a bad sync.
                    SettingsRow(
                        icon = Icons.Outlined.Save,
                        title = "Backup",
                        subtitle = "Export a file, or restore one",
                        onClick = {
                            haptics.tap()
                            backupShown = true
                        }
                    )
                }
            )
        }

        item {
            Text(
                text = "MediaTracker · ${ThemePresets.getPreset(preset).name}",
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = M3ESpacing.tightGap)
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

    if (maintenanceShown) {
        ModalBottomSheet(onDismissRequest = { maintenanceShown = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
                    .padding(bottom = M3ESpacing.sectionGap)
            ) {
                Text("Maintenance", style = M3EType.sectionTitle(), color = M3EColor.text())
                Spacer(Modifier.height(M3ESpacing.tightGap))
                Text(
                    "For when something looks wrong, not for every day.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
                SettingsRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Refresh episodes",
                    subtitle = "Fetch episode data again",
                    onClick = {
                        haptics.tap()
                        maintenanceShown = false
                        SyncScheduler.enrichEpisodesNow(context, forceAll = true)
                        AppToast.show("Refreshing episodes…")
                    }
                )
                SettingsDivider()
                // The watermark makes a sync cheap and also makes a bad pull permanent: once it
                // covers every table, "nothing newer" means a wrong local state is never corrected.
                SettingsRow(
                    icon = Icons.Outlined.Sync,
                    title = "Resync from cloud",
                    subtitle = "Pull everything, ignoring what it already has",
                    onClick = {
                        haptics.tap()
                        maintenanceShown = false
                        AppToast.show("Pulling everything from the cloud…")
                        resync()
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Public,
                    title = "Discovery region",
                    subtitle = if (providerRegion.isBlank()) {
                        "Auto — follow this device"
                    } else {
                        regionDisplayName(providerRegion)
                    },
                    onClick = {
                        haptics.tap()
                        maintenanceShown = false
                        regionPickerShown = true
                    }
                )
            }
        }
    }

    if (backupShown) {
        ModalBottomSheet(onDismissRequest = { backupShown = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
                    .padding(bottom = M3ESpacing.sectionGap)
            ) {
                Text("Backup", style = M3EType.sectionTitle(), color = M3EColor.text())
                Spacer(Modifier.height(M3ESpacing.tightGap))
                Text(
                    "A file on your own storage is the copy that survives an uninstall or a lost phone.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
                Spacer(Modifier.height(M3ESpacing.cardGap))
                SettingsRow(
                    icon = Icons.Outlined.Save,
                    title = "Export library",
                    subtitle = "One JSON file you can keep anywhere",
                    onClick = {
                        haptics.tap()
                        backupShown = false
                        exportBackup()
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.UploadFile,
                    title = "Import backup",
                    subtitle = "Restore from a file — replaces matching titles",
                    onClick = {
                        haptics.tap()
                        backupShown = false
                        backupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                )
            }
        }
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
        ChoiceDialog(
            title = "Digest day",
            options = (1..7).map { day -> Choice(day, weekdayLabel(day)) },
            selected = digestWeekday,
            onSelect = { day ->
                haptics.tap()
                digestWeekday = day
                prefs.digestWeekday = day
                applyDigest(true, day, digestTime)
                pickingWeekday = false
            },
            onDismiss = { pickingWeekday = false }
        )
    }

    if (regionPickerShown) {
        ChoiceDialog(
            title = "Discovery region",
            // The region is keyed by a code rather than a position in the list, so this list is
            // built by hand instead of from the shared option shape.
            options = emptyList(),
            selected = -1,
            onSelect = { },
            onDismiss = { regionPickerShown = false },
            custom = {
                ChoiceRow(
                    label = "Auto — follow this device",
                    selected = providerRegion.isBlank(),
                    onClick = {
                        haptics.tap()
                        providerRegion = ""
                        prefs.providerRegion = ""
                        SyncScheduler.enrichMetadataNow(context)
                        regionPickerShown = false
                    }
                )
                ProviderRegions.forEach { code ->
                    ChoiceRow(
                        label = "${regionDisplayName(code)} ($code)",
                        selected = providerRegion == code,
                        onClick = {
                            haptics.tap()
                            providerRegion = code
                            prefs.providerRegion = code
                            SyncScheduler.enrichMetadataNow(context)
                            regionPickerShown = false
                        }
                    )
                }
            }
        )
    }

}

// ── The shape of the page ────────────────────────────────────────────────────────────────────────

/**
 * A section: a small label, then one container of rows.
 *
 * The label is outside the container so it belongs to the page rather than to the card, which is
 * what lets the card be a single continuous plane while the page still has headings.
 */
@Composable
private fun SettingsSection(
    label: String,
    rows: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = M3EType.groupLabel(),
            color = M3EColor.accent(),
            modifier = Modifier.padding(
                start = 4.dp,
                top = M3ESpacing.tightGap,
                bottom = M3ESpacing.tightGap
            )
        )
        Surface(
            shape = M3EShape.group(),
            color = M3EColor.card(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = rows)
        }
    }
}

/**
 * The hairline between two rows.
 *
 * Full width rather than inset past the icon: the icon column is the row's, not the page's, and a
 * divider that starts halfway across reads as a different list. Low alpha, because a divider's job
 * is to separate two things, not to become a third.
 */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/** The chevron that says a row opens something. */
@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = M3EColor.textMuted(),
        modifier = Modifier.size(20.dp)
    )
}

/**
 * One row: symbol, name, supporting line, and whatever the row does at the end of it.
 *
 * The trailing slot is the whole interaction model. A row with no trailing content and no `onClick`
 * is a statement; a row with a chevron opens something; a row with a value is a choice already
 * made. Nothing about the row changes shape depending on which it is, which is what makes the list
 * scannable.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    emphasise: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .heightIn(min = M3ESpacing.rowMinHeight)
            .padding(horizontal = M3ESpacing.cardGap, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // The glyph carries the row's meaning before the words do, so it takes the text
            // colour rather than the accent — the accent is reserved for the labels and for rows
            // that are asking for something.
            tint = if (emphasise) M3EColor.accent() else M3EColor.text(),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(M3ESpacing.cardGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = M3EType.rowTitle(),
                color = if (emphasise) M3EColor.accent() else M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(M3ESpacing.tightGap))
            if (trailing != null) trailing() else Chevron()
        } else if (trailing != null) {
            Spacer(Modifier.width(M3ESpacing.tightGap))
            trailing()
        }
    }
}

/** A row whose whole control is a switch. */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The label is part of the target: tapping "Films" flips Films. A switch is the one
            // control users aim at the row for, and asking them to hit 40dp when the row is 300 is
            // a needless test.
            .clickable(enabled = enabled) { onToggle(!checked) }
            .heightIn(min = M3ESpacing.rowMinHeight)
            .padding(horizontal = M3ESpacing.cardGap, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = M3EColor.text(),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(M3ESpacing.cardGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = M3EType.rowTitle(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onToggle else null,
            enabled = enabled
        )
    }
}

/** One option in a picker: a tick for the chosen one, a symbol for the rest. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.chip())
            .clickable { onClick() }
            .heightIn(min = M3ESpacing.rowMinHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The slot is always reserved, so the labels line up whether or not an option has a symbol
        // of its own.
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                selected -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = M3EColor.accent(),
                    modifier = Modifier.size(20.dp)
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = M3EColor.textMuted(),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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

/** One option in a [ChoiceDialog], named rather than positional so the call sites read as lists. */
private data class Choice(
    val value: Int,
    val label: String,
    val icon: ImageVector? = null
)

/**
 * A picker for a short list: the same rows as the page, in a dialog.
 *
 * `custom` is for the region list, whose options carry a code rather than an index — the shared
 * path is keyed by option value, and a region's value is a string.
 */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Choice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    custom: (@Composable ColumnScope.() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (custom != null) {
                    custom()
                } else {
                    options.forEach { option ->
                        ChoiceRow(
                            label = option.label,
                            selected = selected == option.value,
                            icon = option.icon
                        ) { onSelect(option.value) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * Connection state as a symbol.
 *
 * A dot with a cloud rather than a word: the colour and the glyph already say it, and a header is
 * the one place a label is pure decoration — the screen reader still gets the sentence.
 */
@Composable
private fun ConnectionMark(connected: Boolean) {
    Surface(
        shape = CircleShape,
        color = M3EColor.card(),
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = if (connected) "Cloud connected" else "Standalone — no cloud configured",
                tint = if (connected) M3EColor.positive() else M3EColor.textMuted(),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * "Films" and "TV" as two Material 3 Expressive toggle buttons.
 *
 * The pair used to be two full switch rows with a sentence each, which is three lines of type to
 * say "these two kinds of release can notify you". A toggle carries the same state in a control you
 * can hit, and the pair reads as one choice rather than two settings that happen to sit together.
 */
@Composable
private fun ChannelToggles(
    movies: Boolean,
    tv: Boolean,
    enabled: Boolean,
    onMovies: (Boolean) -> Unit,
    onTv: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.tightGap),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
    ) {
        ToggleButton(
            checked = movies,
            onCheckedChange = onMovies,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(16.dp)) },
            content = { Text("Films", style = M3EType.action()) }
        )
        ToggleButton(
            checked = tv,
            onCheckedChange = onTv,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Outlined.Tv, contentDescription = null, modifier = Modifier.size(16.dp)) },
            content = { Text("TV", style = M3EType.action()) }
        )
    }
}

/**
 * Auto / Dark / Light as one connected control.
 *
 * A segmented button rather than three rows or a dialog: the options are mutually exclusive and
 * always visible, which is exactly what a segmented control means. It also gives the screen one
 * piece of Material 3 Expressive's shape language — the row reads as a single object with a moving
 * selection, not three independent buttons.
 */
@Composable
private fun ThemeSegmented(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        Triple(0, "Auto", Icons.Outlined.BrightnessAuto),
        Triple(1, "Dark", Icons.Outlined.DarkMode),
        Triple(2, "Light", Icons.Outlined.LightMode)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label, icon) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(label, style = M3EType.action()) }
            )
        }
    }
}

/**
 * The six palettes as a strip of swatches, with the one in use named under it.
 *
 * A palette is a thing you look at, so it is shown rather than listed: six circles at a size you
 * can actually judge, the chosen one ringed and checked, and its name underneath.
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
                        // Clipped before the click, so the ripple follows the shape.
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
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            // Contrast against the swatch, not the page.
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
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
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
 * It was the platform `TimePickerDialog` — the one thing in this screen that was not Material at
 * all. The platform dialog is a 2014 widget: no theme, no shape, no relation to anything around it.
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
        // 12-hour, so the clock carries its own AM/PM selector. That is how a time is read on a
        // phone, and it is what the Mac's picker shows; 24-hour was the platform default leaking
        // through rather than a decision.
        is24Hour = false
    )
    // The expressive dialog has two faces — the dial and the keypad — and the toggle that swaps
    // them lives in the dialog's own header, next to the title.
    var displayMode by remember { mutableStateOf(TimePickerDisplayMode.Picker) }

    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(state.hour * 3600 + state.minute * 60)
                    onDismiss()
                }
            ) { Text("Set", style = M3EType.action()) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = M3EType.action()) }
        },
        title = {
            Text(
                text = title,
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
                color = M3EColor.text()
            )
        },
        modeToggleButton = {
            TimePickerDialogDefaults.DisplayModeToggle(
                // The toggle reports a tap, not a target mode; swapping here keeps the dialog the
                // only thing that knows there are exactly two faces.
                onDisplayModeChange = {
                    displayMode = if (displayMode == TimePickerDisplayMode.Input) {
                        TimePickerDisplayMode.Picker
                    } else {
                        TimePickerDisplayMode.Input
                    }
                },
                displayMode = displayMode
            )
        }
    ) {
        when (displayMode) {
            TimePickerDisplayMode.Input -> TimeInput(state = state)
            else -> TimePicker(state = state)
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
