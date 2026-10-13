package com.vara.mediatracker.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.BuildConfig
import com.vara.mediatracker.data.prefs.PreferencesManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vara.mediatracker.data.sync.SyncScheduler
import com.vara.mediatracker.data.sync.CloudSyncManager
import com.vara.mediatracker.ui.theme.LocalSemanticColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/** Debounce for credential writes: long enough to coalesce typing, short enough to feel instant. */
private const val SettingsScreenDebounceMs = 400L

/** Curated provider regions offered in Settings (the common streaming markets). */
private val ProviderRegions = listOf(
    "US", "GB", "IN", "CA", "AU", "DE", "FR", "ES", "IT", "NL", "SE", "NO", "DK",
    "FI", "PL", "PT", "IE", "CH", "AT", "BE", "JP", "KR", "CN", "HK", "TW", "SG",
    "PH", "ID", "TH", "VN", "MY", "BR", "MX", "AR", "TR", "AE", "SA", "ZA", "NZ"
)

private fun regionDisplayName(code: String): String =
    java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)

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
    container: AppContainer,
    onThemePresetChanged: (Int) -> Unit = {},
    onThemeModeChanged: (Int) -> Unit = {},
    onOpenSearch: (() -> Unit)? = null
) {
    val prefs = container.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalSemanticColors.current
    var currentPresetIndex by remember { mutableIntStateOf(prefs.themePreset) }
    var currentThemeMode by remember { mutableIntStateOf(prefs.themeMode) }

    // Credentials staging
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

    var showAnonKey by remember { mutableStateOf(false) }
    var showSyncKey by remember { mutableStateOf(false) }
    var showTmdbKey by remember { mutableStateOf(false) }
    var providerRegion by remember { mutableStateOf(prefs.providerRegion) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncSummary by remember { mutableStateOf<CloudSyncManager.SyncSummary?>(null) }
    var pendingImpact by remember { mutableStateOf<CloudSyncManager.SyncImpact?>(null) }
    var isConfigExpanded by remember { mutableStateOf(false) }
    var isTmdbExpanded by remember { mutableStateOf(false) }
    var lastSyncTime by remember { mutableLongStateOf(prefs.lastSyncTimestamp) }

    val isCloudConnected = current.supabaseUrl.isNotBlank() && current.syncKey.isNotBlank()
    val isTmdbConnected = current.tmdbApiKey.isNotBlank()

    val lastSyncLabel = remember(lastSyncTime) {
        if (lastSyncTime == 0L) "Never synced"
        else "Last synced: " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncTime))
    }

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
        else "Synced " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
        if (!isCloudConnected) "$base • Credentials required"
        else base
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sticky Header: "PREFERENCES" / "Settings"
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
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onOpenSearch != null) {
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Status Pill
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
                                .background(if (isCloudConnected) sem.success else sem.warning)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isCloudConnected) "CONNECTED" else "STANDALONE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Minimal Quick Sync Action Card
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Cloud Library Sync",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = lastSyncSummary?.let {
                                    "Two-way sync · ↑ ${it.outgoing} sent · ↓ ${it.incoming} received"
                                } ?: lastSyncLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!isCloudConnected) {
                                isConfigExpanded = true
                                Toast.makeText(context, "Configure credentials below first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            // Preview the diff first (macOS SyncImpact), then confirm.
                            isSyncing = true
                            container.appScope.launch {
                                val result = container.syncManager().computeImpact()
                                withContext(Dispatchers.Main) {
                                    isSyncing = false
                                    if (result.isSuccess) {
                                        pendingImpact = result.getOrNull()
                                    } else {
                                        Toast.makeText(context, "Preview failed: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        if (isSyncing) {
                            LoadingIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. Minimal Collapsible Cloud Sync Credentials
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isConfigExpanded = !isConfigExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Supabase Credentials",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isCloudConnected) "Configured & hidden" else "Setup required",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCloudConnected) sem.success else sem.warning
                                )
                            }
                        }

                        IconButton(onClick = { isConfigExpanded = !isConfigExpanded }) {
                            Icon(
                                imageVector = if (isConfigExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isConfigExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isConfigExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Supabase Project URL
                            OutlinedTextField(
                                value = current.supabaseUrl,
                                onValueChange = { draft.value = draft.value.copy(supabaseUrl = it) },
                                label = { Text("Project URL", fontSize = 12.sp) },
                                placeholder = { Text("https://xyz.supabase.co", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Supabase Anon Key (Masked by default)
                            OutlinedTextField(
                                value = current.supabaseAnonKey,
                                onValueChange = { draft.value = draft.value.copy(supabaseAnonKey = it) },
                                label = { Text("Anon Key", fontSize = 12.sp) },
                                placeholder = { Text("sb_publishable_...", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                visualTransformation = if (showAnonKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showAnonKey = !showAnonKey }) {
                                        Icon(
                                            imageVector = if (showAnonKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle visibility",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Secret Sync Key (Masked by default)
                            OutlinedTextField(
                                value = current.syncKey,
                                onValueChange = { draft.value = draft.value.copy(syncKey = it) },
                                label = { Text("Sync Secret Key", fontSize = 12.sp) },
                                placeholder = { Text("mt_sync_...", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                visualTransformation = if (showSyncKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showSyncKey = !showSyncKey }) {
                                        Icon(
                                            imageVector = if (showSyncKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle visibility",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 3. Minimal Collapsible TMDB API Metadata
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isTmdbExpanded = !isTmdbExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "TMDB Artwork & Network API",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isTmdbConnected) "API key configured & masked" else "Optional key for higher limits",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTmdbConnected) sem.success else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(onClick = { isTmdbExpanded = !isTmdbExpanded }) {
                            Icon(
                                imageVector = if (isTmdbExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isTmdbExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isTmdbExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = current.tmdbApiKey,
                            onValueChange = { draft.value = draft.value.copy(tmdbApiKey = it) },
                            label = { Text("TMDB API Key / Token", fontSize = 12.sp) },
                            placeholder = { Text("Enter v3 key or Bearer token...", fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (showTmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showTmdbKey = !showTmdbKey }) {
                                    Icon(
                                        imageVector = if (showTmdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3b. Discovery Region — which country's streaming providers to show.
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Discovery Region",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (providerRegion.isBlank()) {
                                        "Auto — picks streaming providers from your region"
                                    } else {
                                        "${regionDisplayName(providerRegion)} providers"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Box {
                            Surface(
                                onClick = { regionMenuExpanded = true },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (providerRegion.isBlank()) "Auto" else providerRegion,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Choose region",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = regionMenuExpanded,
                                onDismissRequest = { regionMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Auto") },
                                    onClick = {
                                        regionMenuExpanded = false
                                        providerRegion = ""
                                        prefs.providerRegion = ""
                                        SyncScheduler.enrichMetadataNow(context)
                                    }
                                )
                                ProviderRegions.forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text("${regionDisplayName(code)} ($code)") },
                                        onClick = {
                                            regionMenuExpanded = false
                                            providerRegion = code
                                            prefs.providerRegion = code
                                            SyncScheduler.enrichMetadataNow(context)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Background Sync & Periodic Workers (Clean Toggle Card)
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Automatic Sync Worker",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (backgroundSyncEnabled) "Every 30m in background" else "Off",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                        Button(
                            onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Text("Allow Airing Notifications", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 5. Appearance & Theme Palette Selector (Same 6 presets as Mac App)
            val activePreset = com.vara.mediatracker.ui.theme.ThemePresets.getPreset(currentPresetIndex)
            Surface(
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.Card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Dark / Light / System Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = when (currentThemeMode) {
                                            2 -> Icons.Default.LightMode
                                            0 -> Icons.Default.BrightnessAuto
                                            else -> Icons.Default.DarkMode
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Appearance",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (currentThemeMode) {
                                        1 -> "Dark Mode"
                                        2 -> "Light Mode"
                                        else -> "System Mode"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Segmented control for Auto (0), Dark (1), Light (2)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                val modes = listOf(Triple(0, "Auto", Icons.Default.BrightnessAuto), Triple(1, "Dark", Icons.Default.DarkMode), Triple(2, "Light", Icons.Default.LightMode))
                                modes.forEach { (mode, label, icon) ->
                                    val isSelected = currentThemeMode == mode
                                    Surface(
                                        onClick = {
                                            currentThemeMode = mode
                                            prefs.themeMode = mode
                                            onThemeModeChanged(mode)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Theme Palette",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = activePreset.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Hex badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = activePreset.accentHex,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Row of 6 color dots matching Mac (Mac, Trippy, Chill, Epic, Emotional, Sunset)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.vara.mediatracker.ui.theme.ThemePresets.presets.forEachIndexed { index, preset ->
                            val isSelected = currentPresetIndex == index
                            Surface(
                                onClick = {
                                    currentPresetIndex = index
                                    onThemePresetChanged(index)
                                },
                                shape = CircleShape,
                                color = preset.primaryColor,
                                border = if (isSelected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .size(if (isSelected) 40.dp else 34.dp)
                                    .semantics {
                                        contentDescription = "${preset.name} theme"
                                        selected = isSelected
                                        role = Role.RadioButton
                                    }
                            ) {
                                if (isSelected) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Minimal M3E Spec Footer Badge
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MediaTracker • ${activePreset.name}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "macOS Palettes • v${BuildConfig.VERSION_NAME}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Pre-sync preview (macOS SyncImpact): show the incoming/outgoing diff, then apply.
    pendingImpact?.let { impact ->
        AlertDialog(
            onDismissRequest = { pendingImpact = null },
            title = { Text("Cloud Sync Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Review what will change between this device and the cloud.")
                    Text(
                        text = "↓ Incoming: ${impact.incomingTitles.size} titles · ${impact.incomingEpisodes} episodes",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "↑ Outgoing: ${impact.outgoingTitles.size} titles · ${impact.outgoingEpisodes} episodes",
                        fontWeight = FontWeight.Bold
                    )
                    if (impact.conflictTitles.isNotEmpty()) {
                        Text(
                            text = "${impact.conflictTitles.size} conflicts (newest wins)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val sample = (impact.incomingTitles + impact.outgoingTitles).distinct().take(6)
                    if (sample.isNotEmpty()) {
                        Text(
                            text = sample.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImpact = null
                    isSyncing = true
                    container.appScope.launch {
                        val result = container.syncManager().sync()
                        withContext(Dispatchers.Main) {
                            isSyncing = false
                            if (result.isSuccess) {
                                SyncScheduler.recordSyncCompletion(context)
                                lastSyncSummary = result.getOrNull()
                                val now = System.currentTimeMillis()
                                prefs.lastSyncTimestamp = now
                                lastSyncTime = now
                                val s = result.getOrNull()
                                Toast.makeText(context, "Synced — ↑ ${s?.outgoing ?: 0} sent, ↓ ${s?.incoming ?: 0} received", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Sync failed: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text("Sync Now") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImpact = null }) { Text("Cancel") }
            }
        )
    }
}
