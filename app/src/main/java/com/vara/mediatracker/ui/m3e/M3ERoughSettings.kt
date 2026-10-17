package com.vara.mediatracker.ui.m3e

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vara.mediatracker.data.sync.NotificationScheduler
import com.vara.mediatracker.data.sync.ReleaseReminderPlanner
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.theme.ThemePresets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings, in the [M3E] vocabulary: one tonal card per group, one row per setting.
 *
 * Unlike the other sketch screens this one is wired for real. Preferences, credentials, the
 * palette and the background-sync worker are the app's own — the same
 * [com.vara.mediatracker.data.prefs.PreferencesManager] fields and the same [SyncScheduler] the
 * shipping screen drives, reached through [container]. Theme changes go back up through the
 * callbacks so the activity can re-theme without a restart, which is the one thing a settings
 * screen cannot do for itself.
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

    var themeMode by rememberSaveable { mutableIntStateOf(prefs.themeMode) }
    var preset by rememberSaveable { mutableIntStateOf(prefs.themePreset) }
    var backgroundSync by rememberSaveable { mutableStateOf(SyncScheduler.isEnabled(context)) }
    var networkLogo by rememberSaveable { mutableStateOf(prefs.detailNetworkLogo) }
    var notificationsEnabled by rememberSaveable { mutableStateOf(prefs.notificationsEnabled) }
    var notificationsMovies by rememberSaveable { mutableStateOf(prefs.notificationsMovies) }
    var notificationsTv by rememberSaveable { mutableStateOf(prefs.notificationsTv) }
    var deliverySeconds by rememberSaveable { mutableIntStateOf(prefs.notificationsTime) }

    // The queue is derived from prefs, the library and the clock, so anything that changes one of
    // those re-plans it rather than trusting what was scheduled earlier.
    val scope = rememberCoroutineScope()
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
    ) { granted -> if (granted) reschedule() }

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.sectionGap)
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                M3EScreenHeader(eyebrow = "Preferences", title = "Settings")
            }
        }

        // ── Appearance ────────────────────────────────────────────────────────────────────────
        item {
            Column {
                M3ESectionHeader("Appearance")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(M3ESpacing.cardGap),
                        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                    ) {
                        LabelledSetting(
                            title = "Theme",
                            supporting = when (themeMode) {
                                2 -> "Light"
                                1 -> "Dark"
                                else -> "Follow the system"
                            }
                        )
                        SegmentedModes(
                            selected = themeMode,
                            onSelect = { mode ->
                                haptics.tap()
                                themeMode = mode
                                prefs.themeMode = mode
                                onThemeModeChanged(mode)
                            }
                        )

                        SettingDivider()

                        LabelledSetting(
                            title = "Palette",
                            supporting = "Six accents, from Mac to Sunset"
                        )
                        PaletteGrid(
                            selected = preset,
                            onSelect = { index ->
                                haptics.tap()
                                preset = index
                                prefs.themePreset = index
                                onThemePresetChanged(index)
                            }
                        )

                        SettingDivider()

                        SettingSwitchRow(
                            icon = Icons.Default.Cloud,
                            title = "Network logo on detail",
                            supporting = "Show the studio mark instead of its name",
                            checked = networkLogo,
                            onCheckedChange = {
                                haptics.toggle()
                                networkLogo = it
                                prefs.detailNetworkLogo = it
                            }
                        )
                    }
                }
            }
        }

        // ── Notifications ─────────────────────────────────────────────────────────────────────
        item {
            Column {
                M3ESectionHeader("Notifications")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(M3ESpacing.cardGap),
                        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                    ) {
                        SettingSwitchRow(
                            icon = Icons.Default.Notifications,
                            title = "Enable notifications",
                            supporting = "New episodes and releases in your library",
                            checked = notificationsEnabled,
                            onCheckedChange = { enabled ->
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

                        if (notificationsEnabled) {
                            SettingDivider()
                            SettingSwitchRow(
                                icon = Icons.Default.Movie,
                                title = "Movies",
                                supporting = "Announce a film on its release day",
                                checked = notificationsMovies,
                                onCheckedChange = { on ->
                                    haptics.toggle()
                                    notificationsMovies = on
                                    prefs.notificationsMovies = on
                                    reschedule()
                                }
                            )
                            SettingSwitchRow(
                                icon = Icons.Default.Tv,
                                title = "TV shows",
                                supporting = "Announce a new episode on its air day",
                                checked = notificationsTv,
                                onCheckedChange = { on ->
                                    haptics.toggle()
                                    notificationsTv = on
                                    prefs.notificationsTv = on
                                    reschedule()
                                }
                            )
                            SettingDivider()
                            SettingValueRow(
                                title = "Delivery time",
                                supporting = "When a release day's reminder arrives",
                                value = deliveryLabel(deliverySeconds),
                                onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            val seconds = hour * 3600 + minute * 60
                                            deliverySeconds = seconds
                                            prefs.notificationsTime = seconds
                                            reschedule()
                                        },
                                        deliverySeconds / 3600,
                                        (deliverySeconds % 3600) / 60,
                                        false
                                    ).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Cloud sync ────────────────────────────────────────────────────────────────────────
        item {
            Column {
                M3ESectionHeader("Cloud sync")
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(M3ESpacing.cardGap),
                        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
                    ) {
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

                        SettingDivider()

                        SettingSwitchRow(
                            icon = Icons.Default.Sync,
                            title = "Background sync",
                            supporting = if (backgroundSync) {
                                "Every 30 minutes, while the app is closed"
                            } else {
                                "Off"
                            },
                            checked = backgroundSync,
                            onCheckedChange = {
                                backgroundSync = it
                                SyncScheduler.setEnabled(context, it)
                                if (it) SyncScheduler.syncNow(context)
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = M3EColor.textMuted(),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(M3ESpacing.inlineGap))
                            Text(
                                text = "Keys are stored on this device only. The same four make the " +
                                    "Mac app talk to the same library.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(M3ESpacing.pageVertical)) }
    }
}

/** A title over its supporting line, with no trailing control — for rows that own a whole card. */
@Composable
private fun LabelledSetting(title: String, supporting: String) {
    Column {
        Text(
            text = title,
            style = M3EType.rowTitle(),
            color = M3EColor.text()
        )
        Text(
            text = supporting,
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted()
        )
    }
}

/**
 * Auto / Dark / Light as one control rather than three switches.
 *
 * Stacked under its label instead of beside it: three pills plus a label in one row is exactly the
 * squeeze that wraps label text on a narrow screen, so the control gets its own line and each pill
 * an equal share of it.
 */
@Composable
private fun SegmentedModes(selected: Int, onSelect: (Int) -> Unit) {
    val modes = listOf(
        Triple(0, "Auto", Icons.Default.BrightnessAuto),
        Triple(1, "Dark", Icons.Default.DarkMode),
        Triple(2, "Light", Icons.Default.LightMode)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
    ) {
        modes.forEach { (mode, label, icon) ->
            val isSelected = selected == mode
            Surface(
                onClick = { onSelect(mode) },
                shape = M3EShape.chip(),
                color = if (isSelected) M3EColor.accent() else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else M3EColor.textMuted(),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = M3ESpacing.rowGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = label, style = M3EType.rowSupporting())
                }
            }
        }
    }
}

/** The six presets as swatches, three to a row, each showing its own accent. */
@Composable
private fun PaletteGrid(selected: Int, onSelect: (Int) -> Unit) {
    val presets = ThemePresets.presets
    Column(verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)) {
        presets.indices.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap)
            ) {
                row.forEach { index ->
                    val preset = presets[index]
                    val isSelected = selected == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(index) }
                            .padding(vertical = M3ESpacing.tightGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(preset.accentColor, CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) {
                                        M3EColor.accent()
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = preset.name,
                            style = M3EType.rowSupporting(),
                            color = if (isSelected) M3EColor.text() else M3EColor.textMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Leading icon, title over supporting text, switch trailing. */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
            tint = M3EColor.textMuted(),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(M3ESpacing.inlineGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = M3EType.rowTitle(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supporting,
                style = M3EType.rowSupporting(),
                color = M3EColor.textMuted(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                        imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Hide $label" else "Show $label"
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** A setting whose value is edited elsewhere, by tapping the row. */
@Composable
private fun SettingValueRow(
    title: String,
    supporting: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = M3EType.rowTitle(), color = M3EColor.text())
            Text(text = supporting, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
        }
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Text(text = value, style = M3EType.rowTitle(), color = M3EColor.accent())
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun deliveryLabel(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d", seconds / 3600, (seconds % 3600) / 60)

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = M3ESpacing.tightGap),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
