package com.vara.mediatracker.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.data.prefs.PreferencesManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.data.sync.CloudSyncManager
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/** Debounce for credential writes: long enough to coalesce typing, short enough to feel instant. */
private const val SettingsScreenDebounceMs = 400L

/**
 * The four credential fields as one value.
 *
 * Held as a single object so `snapshotFlow` observes one coherent snapshot; four
 * independent states would emit an intermediate save per field while typing.
 */
private data class CredentialDraft(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val syncKey: String = "",
    val tmdbApiKey: String = ""
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun SettingsScreen(
    container: AppContainer
) {
    val prefs = container.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAnonKey by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncTime by remember { mutableLongStateOf(prefs.lastSyncTimestamp) }

    val lastSyncLabel = remember(lastSyncTime) {
        if (lastSyncTime == 0L) "Never synced"
        else "Last synced: " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncTime))
    }

    // Credentials were previously written to SharedPreferences on every keystroke,
    // costing a disk write per character. Stage them locally and commit once the user
    // pauses typing.
    val draft = remember {
        mutableStateOf(
            CredentialDraft(
                supabaseUrl = prefs.supabaseUrl,
                supabaseAnonKey = prefs.supabaseAnonKey,
                syncKey = prefs.syncKey,
                tmdbApiKey = prefs.tmdbApiKey
            )
        )
    }

    LaunchedEffect(Unit) {
        snapshotFlow { draft.value }
            .debounce(SettingsScreenDebounceMs)
            .collect { prefs.saveCredentials(it.supabaseUrl, it.supabaseAnonKey, it.syncKey, it.tmdbApiKey) }
    }

    val current = draft.value

    // --- Background sync / notification permission ---
    var backgroundSyncEnabled by remember { mutableStateOf(SyncScheduler.isEnabled(context)) }
    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        if (granted) SyncScheduler.syncNow(context)
    }

    // Reflects background runs, not just the manual button. Polled only while this
    // screen is on the back stack, so it costs nothing once the user leaves Settings.
    var lastSyncAt by remember { mutableLongStateOf(SyncScheduler.lastSyncTimestamp(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            val ts = SyncScheduler.lastSyncTimestamp(context)
            if (ts != lastSyncAt) lastSyncAt = ts
            delay(10_000)
        }
    }

    val syncStatusLabel = remember(lastSyncAt, current) {
        val base = if (lastSyncAt == 0L) "Not synced yet"
        else "Last synced " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
        if (current.supabaseUrl.isBlank() || current.syncKey.isBlank()) "$base — add credentials above"
        else base
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Sticky M3E Top Header matching HTML: "PREFERENCES" / "Settings & Sync"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PREFERENCES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE086D3))
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Settings & Sync",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Sync Status Indicator Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (current.supabaseUrl.isNotBlank() && current.syncKey.isNotBlank()) Color(0xFF6DDFB8) else Color(0xFFFFB300))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (current.supabaseUrl.isNotBlank() && current.syncKey.isNotBlank()) "CONNECTED" else "SETUP REQ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Supabase Cloud Sync Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.CloudSync,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Cross-Device Cloud Sync",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Supabase PostgreSQL bridge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = "Sync your active library, watches, and smart-badge history in real time with macOS MediaTracker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    // Project URL
                    OutlinedTextField(
                        value = current.supabaseUrl,
                        onValueChange = { draft.value = draft.value.copy(supabaseUrl = it) },
                        label = { Text("Supabase Project URL") },
                        placeholder = { Text("https://xyz.supabase.co") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Anon Key
                    OutlinedTextField(
                        value = current.supabaseAnonKey,
                        onValueChange = { draft.value = draft.value.copy(supabaseAnonKey = it) },
                        label = { Text("Supabase Anon Key") },
                        placeholder = { Text("sb_publishable_...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (showAnonKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showAnonKey = !showAnonKey }) {
                                Icon(
                                    imageVector = if (showAnonKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Secret Sync Key
                    OutlinedTextField(
                        value = current.syncKey,
                        onValueChange = { draft.value = draft.value.copy(syncKey = it) },
                        label = { Text("Secret Sync Key (from Mac Settings)") },
                        placeholder = { Text("mt_sync_...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Sync Now Button (Tactile M3E Plum Fill)
                    Button(
                        onClick = {
                            if (current.supabaseUrl.isBlank() || current.supabaseAnonKey.isBlank() || current.syncKey.isBlank()) {
                                Toast.makeText(context, "Please fill in all sync credentials", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSyncing = true
                            scope.launch {
                                val manager = container.syncManager()
                                val result = manager.pullFromCloud()
                                isSyncing = false
                                if (result.isSuccess) {
                                    SyncScheduler.recordSyncCompletion(context)
                                    val count = result.getOrNull() ?: 0
                                    val now = System.currentTimeMillis()
                                    prefs.lastSyncTimestamp = now
                                    lastSyncTime = now
                                    Toast.makeText(context, "Sync successful! Pulled $count items", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Sync failed: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing with Cloud...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Trigger Cloud Sync", fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = lastSyncLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // TMDB API Key Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "TMDB Metadata & Artwork",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "High-res posters, logos, and broadcasting networks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Used to fetch official posters, backdrops, season episodes, genuine broadcasting networks, and release air dates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = current.tmdbApiKey,
                        onValueChange = { draft.value = draft.value.copy(tmdbApiKey = it) },
                        label = { Text("TMDB API Key or v4 Read Token") },
                        placeholder = { Text("Enter v3 key or Bearer token...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Automatic Updates / Background Sync Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Automatic Background Sync",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Periodic workers & release radar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Keeps your library and smart badges current on a schedule so there's nothing to refresh by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Worker", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (SyncScheduler.isEnabled(context)) "Every 30 minutes on Wi-Fi or Cellular" else "Paused",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = backgroundSyncEnabled,
                            onCheckedChange = {
                                backgroundSyncEnabled = it
                                SyncScheduler.setEnabled(context, it)
                                if (it) SyncScheduler.syncNow(context)
                            }
                        )
                    }

                    if (backgroundSyncEnabled && !notificationsAllowed) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Text(
                            text = "Allow notifications to hear about live airings, premieres, and countdown reminders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Notification Access", fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        text = syncStatusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
