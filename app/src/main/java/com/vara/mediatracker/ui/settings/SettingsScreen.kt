package com.vara.mediatracker.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vara.mediatracker.data.db.AppDatabase
import com.vara.mediatracker.data.prefs.PreferencesManager
import com.vara.mediatracker.data.sync.CloudSyncManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    prefs: PreferencesManager,
    db: AppDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var supabaseUrl by remember { mutableStateOf(prefs.supabaseUrl) }
    var supabaseAnonKey by remember { mutableStateOf(prefs.supabaseAnonKey) }
    var syncKey by remember { mutableStateOf(prefs.syncKey) }
    var tmdbApiKey by remember { mutableStateOf(prefs.tmdbApiKey) }
    var showAnonKey by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncTime by remember { mutableLongStateOf(prefs.lastSyncTimestamp) }

    val lastSyncLabel = remember(lastSyncTime) {
        if (lastSyncTime == 0L) "Never synced"
        else "Last synced: " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncTime))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Supabase Cloud Sync Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cross-Device Cloud Sync",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Pair this device with your macOS MediaTracker app via your personal Supabase project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Project URL
                OutlinedTextField(
                    value = supabaseUrl,
                    onValueChange = {
                        supabaseUrl = it
                        prefs.supabaseUrl = it
                    },
                    label = { Text("Supabase Project URL") },
                    placeholder = { Text("https://xyz.supabase.co") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Anon Key
                OutlinedTextField(
                    value = supabaseAnonKey,
                    onValueChange = {
                        supabaseAnonKey = it
                        prefs.supabaseAnonKey = it
                    },
                    label = { Text("Supabase Anon Key") },
                    placeholder = { Text("sb_publishable_...") },
                    singleLine = true,
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
                    value = syncKey,
                    onValueChange = {
                        syncKey = it
                        prefs.syncKey = it
                    },
                    label = { Text("Secret Sync Key (from Mac Settings)") },
                    placeholder = { Text("mt_sync_...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Sync Now Button
                Button(
                    onClick = {
                        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank() || syncKey.isBlank()) {
                            Toast.makeText(context, "Please fill in all sync credentials", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSyncing = true
                        scope.launch {
                            val manager = CloudSyncManager(
                                mediaDao = db.mediaDao(),
                                supabaseUrl = supabaseUrl,
                                anonKey = supabaseAnonKey,
                                syncKey = syncKey,
                                tmdbApiKey = tmdbApiKey
                            )
                            val result = manager.pullFromCloud()
                            isSyncing = false
                            if (result.isSuccess) {
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing with Cloud...")
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now")
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
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TMDB Metadata & Artwork",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Used to fetch official posters, backdrops, season episodes, and air dates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                OutlinedTextField(
                    value = tmdbApiKey,
                    onValueChange = {
                        tmdbApiKey = it
                        prefs.tmdbApiKey = it
                    },
                    label = { Text("TMDB API Key (v3 Key or v4 Read Token)") },
                    placeholder = { Text("Enter v3 key or v4 Bearer token (ey...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
