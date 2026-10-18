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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vara.mediatracker.data.sync.DigestScheduler
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
 * Settings, in the [M3E] vocabulary — one flat page.
 *
 * No groups, no panels, no labels above runs of rows. Everything is on the page in one order and a
 * divider is the only structure, which is all this needs: a settings screen you can read top to
 * bottom without deciding which part of it you are in. An earlier version put the groups behind
 * summary rows, which made you tap in to change one switch, and the one before that spent the page's
 * height on three headline labels for three words.
 *
 * Nothing is hidden except the credentials, which stay behind Show/Hide for the reason they always
 * were — set once, then never touched, so four text fields at the top were noise every other visit.
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
    var notificationsEnabled by rememberSaveable { mutableStateOf(prefs.notificationsEnabled) }
    var notificationsMovies by rememberSaveable { mutableStateOf(prefs.notificationsMovies) }
    var notificationsTv by rememberSaveable { mutableStateOf(prefs.notificationsTv) }
    var deliverySeconds by rememberSaveable { mutableIntStateOf(prefs.notificationsTime) }
    var digestEnabled by rememberSaveable { mutableStateOf(prefs.digestEnabled) }
    var digestWeekday by rememberSaveable { mutableIntStateOf(prefs.digestWeekday) }
    var digestTime by rememberSaveable { mutableIntStateOf(prefs.digestTime) }
    var pickingWeekday by rememberSaveable { mutableStateOf(false) }
    var keysShown by rememberSaveable { mutableStateOf(false) }
    var backgroundSync by rememberSaveable { mutableStateOf(SyncScheduler.isEnabled(context)) }

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
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "Preferences", title = "Settings")
        }

        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {

                    // ── Appearance ───────────────────────────────────────────────────────────
                    SettingBlock {
                        LabelledSetting(
                            title = "Theme",
                            supporting = when (themeMode) {
                                2 -> "Light"
                                1 -> "Dark"
                                else -> "Follow the system"
                            }
                        )
                        Spacer(Modifier.height(M3ESpacing.tightGap))
                        SegmentedModes(
                            selected = themeMode,
                            onSelect = { mode ->
                                haptics.tap()
                                themeMode = mode
                                prefs.themeMode = mode
                                onThemeModeChanged(mode)
                            }
                        )
                    }

                    SettingDivider()

                    SettingBlock {
                        LabelledSetting(
                            title = "Palette",
                            // Names the one in use rather than describing the set: the swatches
                            // already say what the options are.
                            supporting = ThemePresets.presets.getOrNull(preset)?.name ?: "Custom"
                        )
                        Spacer(Modifier.height(M3ESpacing.tightGap))
                        PaletteGrid(
                            selected = preset,
                            onSelect = { index ->
                                haptics.tap()
                                preset = index
                                prefs.themePreset = index
                                onThemePresetChanged(index)
                            }
                        )
                    }

                    SettingDivider()

                    // ── Notifications ────────────────────────────────────────────────────────
                    SettingSwitchRow(
                        icon = Icons.Default.Notifications,
                        title = "Enable notifications",
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
                        SettingSwitchRow(
                            icon = Icons.Default.Movie,
                            title = "Movies",
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
                            checked = notificationsTv,
                            onCheckedChange = { on ->
                                haptics.toggle()
                                notificationsTv = on
                                prefs.notificationsTv = on
                                reschedule()
                            }
                        )
                        SettingValueRow(
                            title = "Delivery time",
                            value = clockLabel(deliverySeconds)
                        ) {
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
                        SettingSwitchRow(
                            icon = Icons.Default.Schedule,
                            title = "Weekly digest",
                            checked = digestEnabled,
                            onCheckedChange = { on ->
                                haptics.toggle()
                                digestEnabled = on
                                prefs.digestEnabled = on
                                applyDigest(on, digestWeekday, digestTime)
                            }
                        )
                        if (digestEnabled) {
                            SettingValueRow(
                                title = "Digest day",
                                value = weekdayLabel(digestWeekday)
                            ) {
                                haptics.tap()
                                pickingWeekday = true
                            }
                            SettingValueRow(
                                title = "Digest time",
                                value = clockLabel(digestTime)
                            ) {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        val seconds = hour * 3600 + minute * 60
                                        digestTime = seconds
                                        prefs.digestTime = seconds
                                        applyDigest(true, digestWeekday, seconds)
                                    },
                                    digestTime / 3600,
                                    (digestTime % 3600) / 60,
                                    false
                                ).show()
                            }
                        }
                    }

                    SettingDivider()

                    // ── Cloud sync ───────────────────────────────────────────────────────────
                    SettingBlock {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                LabelledSetting(
                                    title = "Credentials",
                                    supporting = if (keysShown) {
                                        "The four keys this app syncs with"
                                    } else {
                                        "Hidden"
                                    }
                                )
                            }
                            TextButton(onClick = {
                                haptics.tap()
                                keysShown = !keysShown
                            }) {
                                Text(
                                    text = if (keysShown) "Hide" else "Show",
                                    style = M3EType.action(),
                                    color = M3EColor.accent()
                                )
                            }
                        }
                        if (keysShown) {
                            Spacer(Modifier.height(M3ESpacing.tightGap))
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
                    }

                    SettingSwitchRow(
                        icon = Icons.Default.Sync,
                        title = "Background sync",
                        checked = backgroundSync,
                        supporting = if (backgroundSync) {
                            "Every 30 minutes, while the app is closed"
                        } else {
                            "Off"
                        },
                        onCheckedChange = {
                            backgroundSync = it
                            SyncScheduler.setEnabled(context, it)
                            if (it) SyncScheduler.syncNow(context)
                        }
                    )

                    SettingBlock {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sync now", style = M3EType.rowTitle(), color = M3EColor.text())
                            }
                            Button(
                                onClick = {
                                    haptics.tap()
                                    SyncScheduler.syncNow(context)
                                },
                                shape = M3EShape.chip()
                            ) {
                                Text("Sync", style = M3EType.action())
                            }
                        }
                        Spacer(Modifier.height(M3ESpacing.rowGap))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = M3EColor.textMuted(),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(M3ESpacing.tightGap))
                            Text(
                                text = "Keys are stored on this device only.",
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(M3ESpacing.bottomInset)) }
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
}

/** A run of rows inside the one card, with the horizontal inset they all share. */
@Composable
private fun SettingBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.tightGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.tightGap),
        content = content
    )
}

/** A title over its supporting line, with no trailing control. */
@Composable
private fun LabelledSetting(title: String, supporting: String) {
    Column {
        Text(text = title, style = M3EType.rowTitle(), color = M3EColor.text())
        Text(text = supporting, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
    }
}

/**
 * Auto / Dark / Light as one control rather than three switches.
 *
 * One container with three equal segments, not three floating pills: they are one choice, and three
 * buttons read as three unrelated actions. Stacked under its label rather than beside it — three
 * pills plus a label in one row is the squeeze that wraps label text on a narrow screen.
 */
@Composable
private fun SegmentedModes(selected: Int, onSelect: (Int) -> Unit) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = M3ESpacing.tightGap),
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
                            // Clipped before the click, so the ripple follows the shape. Without
                            // this the feedback is a sharp rectangle around a round swatch.
                            .clip(M3EShape.chip())
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
                                    // Contrast against the swatch itself, not the page: the presets
                                    // run from near-white to near-black.
                                    tint = if (preset.accentColor.luminance() > 0.5f) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
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

/** Leading icon, title over an optional supporting line, switch trailing. */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    supporting: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.rowGap),
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
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(M3ESpacing.tightGap))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A setting whose value is edited elsewhere, by tapping the row. */
@Composable
private fun SettingValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = M3ESpacing.cardGap, vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = M3EType.rowTitle(),
            color = M3EColor.text(),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = M3EType.rowTitle(),
            color = M3EColor.accent()
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
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = M3ESpacing.tightGap),
        color = MaterialTheme.colorScheme.outlineVariant
    )
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
