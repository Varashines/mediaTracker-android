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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.common.springClickable
import com.vara.mediatracker.ui.theme.AppTextStyles
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

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalMaterial3Api::class)
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
    val haptics = rememberHaptics()
    val sem = LocalSemanticColors.current
    var currentPresetIndex by remember { mutableIntStateOf(prefs.themePreset) }
    var currentThemeMode by remember { mutableIntStateOf(prefs.themeMode) }
    var detailNetworkLogo by remember { mutableStateOf(prefs.detailNetworkLogo) }

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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
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
                                AppToast.show("Configure credentials below first")
                                return@Button
                            }
                            // Preview the diff first (macOS SyncImpact), then confirm.
                            isSyncing = true
                            container.appScope.launch {
                                val result = container.syncManager().computeImpact(since = prefs.syncWatermark)
                                withContext(Dispatchers.Main) {
                                    isSyncing = false
                                    if (result.isSuccess) {
                                        pendingImpact = result.getOrNull()
                                    } else {
                                        AppToast.show("Preview failed: ${result.exceptionOrNull()?.localizedMessage}")
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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .springClickable { isConfigExpanded = !isConfigExpanded },
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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .springClickable { isTmdbExpanded = !isTmdbExpanded },
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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
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
                shape = com.vara.mediatracker.ui.theme.ExpressiveShapes.AsymCard,
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
                                            haptics.tap()
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
                                    haptics.tap()
                                    currentPresetIndex = index
                                    onThemePresetChanged(index)
                                },
                                shape = CircleShape,
                                color = preset.accentColor,
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Network logo in detail",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Show the logo instead of the name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = detailNetworkLogo,
                            onCheckedChange = {
                                detailNetworkLogo = it
                                prefs.detailNetworkLogo = it
                            }
                        )
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

    // Pre-sync preview (mirrors the macOS SyncImpact sheet): a header, then one collapsible
    // card per category with an icon and a count. Cards start collapsed so the whole picture
    // fits a phone; tap one to see what is inside it.
    pendingImpact?.let { impact ->
        ModalBottomSheet(onDismissRequest = { pendingImpact = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SyncPreviewHeader(impact)

                SyncPreviewSection(
                    icon = Icons.Default.WarningAmber,
                    title = "Conflicts",
                    count = impact.conflictTitles.size,
                    accent = sem.warning,
                    summary = "Kept by both sides but disagreeing on status or taste. The newest edit wins.",
                    names = impact.conflictTitles,
                    initiallyExpanded = true
                )

                SyncPreviewSection(
                    icon = Icons.Default.CloudDownload,
                    title = "Incoming",
                    count = impact.incomingTitles.size + impact.incomingEpisodes +
                        impact.incomingCollections.size + impact.incomingCollectionItems,
                    accent = MaterialTheme.colorScheme.primary,
                    summary = "${impact.incomingTitles.size} titles · ${impact.incomingEpisodes} episodes · " +
                        "${impact.incomingCollections.size + impact.incomingCollectionItems} collection rows",
                    names = impact.incomingTitles + impact.incomingCollections
                )

                SyncPreviewSection(
                    icon = Icons.Default.CloudUpload,
                    title = "Uploads",
                    count = impact.outgoingTitles.size + impact.outgoingEpisodes +
                        impact.outgoingCollections.size + impact.outgoingCollectionItems,
                    accent = sem.success,
                    summary = "${impact.outgoingTitles.size} titles · ${impact.outgoingEpisodes} episodes · " +
                        "${impact.outgoingCollections.size + impact.outgoingCollectionItems} collection rows",
                    names = impact.outgoingTitles + impact.outgoingCollections
                )

                SyncPreviewSection(
                    icon = Icons.Default.History,
                    title = "History",
                    count = impact.incomingCycles + impact.outgoingCycles +
                        impact.incomingEvents + impact.outgoingEvents,
                    accent = MaterialTheme.colorScheme.primary,
                    summary = "${impact.outgoingCycles} cycles · ${impact.outgoingEvents} events up, " +
                        "${impact.incomingCycles} cycles · ${impact.incomingEvents} events down"
                )

                SyncPreviewActions(
                    isSyncing = isSyncing,
                    onCancel = { pendingImpact = null },
                    onSync = {
                        pendingImpact = null
                        isSyncing = true
                        container.appScope.launch {
                            val result = container.syncManager().sync(since = prefs.syncWatermark)
                            withContext(Dispatchers.Main) {
                                isSyncing = false
                                if (result.isSuccess) {
                                    SyncScheduler.recordSyncCompletion(context)
                                    lastSyncSummary = result.getOrNull()
                                    result.getOrNull()?.watermark?.let { prefs.syncWatermark = it }
                                    val now = System.currentTimeMillis()
                                    prefs.lastSyncTimestamp = now
                                    lastSyncTime = now
                                    val s = result.getOrNull()
                                    AppToast.show("Synced — ↑ ${s?.outgoing ?: 0} sent, ↓ ${s?.incoming ?: 0} received")
                                } else {
                                    AppToast.show("Sync failed: ${result.exceptionOrNull()?.localizedMessage}")
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Header of the sync preview: a tinted glyph plus the headline count in each direction. */
@Composable
private fun SyncPreviewHeader(impact: CloudSyncManager.SyncImpact) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Cloud Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (impact.hasChanges) {
                    "${impact.totalOutgoing + impact.totalIncoming} changes — " +
                        "↑ ${impact.totalOutgoing} · ↓ ${impact.totalIncoming}"
                } else {
                    "This device and the cloud already match"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One category: icon, title, count badge, and a tap-to-expand body. */
@Composable
private fun SyncPreviewSection(
    icon: ImageVector,
    title: String,
    count: Int,
    accent: Color,
    summary: String,
    names: List<String> = emptyList(),
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded && count > 0) }
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (count > 0) accent else idle

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = idle,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (names.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    names.take(8).forEach { name ->
                        Text(
                            "• $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (names.size > 8) {
                        Text(
                            "+ ${names.size - 8} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** Cancel / Sync Now, with the primary action disabled while a sync is running. */
@Composable
private fun SyncPreviewActions(
    isSyncing: Boolean,
    onCancel: () -> Unit,
    onSync: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f)
        ) { Text("Cancel") }

        Button(
            onClick = onSync,
            enabled = !isSyncing,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.weight(1f)
        ) { Text(if (isSyncing) "Syncing…" else "Sync Now", fontWeight = FontWeight.Bold) }
    }
}
