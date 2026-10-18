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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
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
 * Settings, in the [M3E] vocabulary — three purpose-built blocks, no rows.
 *
 * A settings screen is usually a list of label/control pairs, and that is exactly what this was.
 * This one is built the other way round: each block is the shape of the thing it configures. The
 * palette is a strip of swatches because a palette is something you look at, notifications are tiles
 * that light up because they are on/off states, and sync is an action with a status rather than a
 * switch with a label.
 *
 * One page, everything visible, no sub-sections — the structure is containment and colour, not
 * headings and disclosure.
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
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(eyebrow = "Preferences", title = "Settings")
        }

        // ── The look: a strip you can see, and a mode you can read ─────────────────────────────
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
                }
            }
        }

        // ── Notifications: states, so they are tiles that light up ─────────────────────────────
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

                    if (notificationsEnabled) {
                        Spacer(Modifier.height(M3ESpacing.cardGap))
                        ValueLine(
                            label = "Delivery time",
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
                        if (digestEnabled) {
                            ValueLine(label = "Digest day", value = weekdayLabel(digestWeekday)) {
                                haptics.tap()
                                pickingWeekday = true
                            }
                            ValueLine(label = "Digest time", value = clockLabel(digestTime)) {
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
                }
            }
        }

        // ── Sync: an action with a state, not a switch with a label ────────────────────────────
        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Button(
                        onClick = {
                            haptics.tap()
                            SyncScheduler.syncNow(context)
                        },
                        shape = M3EShape.chip(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(M3ESpacing.rowMinHeight)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(M3ESpacing.tightGap))
                        Text("Sync now", style = M3EType.action())
                    }

                    Spacer(Modifier.height(M3ESpacing.cardGap))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background sync",
                                style = M3EType.rowTitle(),
                                color = M3EColor.text()
                            )
                            Text(
                                text = if (backgroundSync) {
                                    "Every 30 minutes, while the app is closed"
                                } else {
                                    "Off"
                                },
                                style = M3EType.rowSupporting(),
                                color = M3EColor.textMuted()
                            )
                        }
                        Switch(
                            checked = backgroundSync,
                            onCheckedChange = {
                                backgroundSync = it
                                SyncScheduler.setEnabled(context, it)
                                if (it) SyncScheduler.syncNow(context)
                            }
                        )
                    }

                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(M3ESpacing.tightGap))

                    // Set once, then never touched — so it is a button that opens them rather than
                    // four fields sitting on the page on every visit that is not about them.
                    TextButton(
                        onClick = {
                            haptics.tap()
                            keysShown = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(M3ESpacing.tightGap))
                        Text("Keys", style = M3EType.action())
                    }
                }
            }
        }

        item { Spacer(Modifier.height(M3ESpacing.bottomInset)) }
    }

    if (keysShown) {
        AlertDialog(
            onDismissRequest = { keysShown = false },
            title = { Text("Keys") },
            text = {
                M3EGlassDialog()
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

    if (pickingWeekday) {
        AlertDialog(
            onDismissRequest = { pickingWeekday = false },
            title = { Text("Digest day") },
            text = {
                M3EGlassDialog()
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

/** A label and its value, where tapping the value changes it. */
@Composable
private fun ValueLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3EShape.chip())
            .clickable { onClick() }
            .padding(vertical = M3ESpacing.rowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = M3EType.rowSupporting(),
            color = M3EColor.textMuted(),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = M3EType.rowTitle(),
            fontWeight = FontWeight.SemiBold,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = M3ESpacing.tightGap)
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
