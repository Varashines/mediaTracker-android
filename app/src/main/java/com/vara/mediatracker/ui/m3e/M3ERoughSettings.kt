package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings, in the [M3E] vocabulary: one tonal card per group, one row per setting, a Material
 * switch as the trailing control — the reference's list shape.
 *
 * The switches are local and inert: a sketch of the layout, not the wiring. The real screen reads
 * and writes the preferences that already exist.
 */
@Composable
fun M3ERoughSettings(onBack: () -> Unit) {
    val groups = remember {
        listOf(
            "Appearance" to listOf(
                SettingRow("Theme", "Follow the system or pick light or dark"),
                SettingRow("Palette", "Six accents, from Accent to Sunset")
            ),
            "Sync" to listOf(
                SettingRow("Sync with cloud", "Keep this device and the Mac in step"),
                SettingRow("Background sync", "Refresh while the app is closed")
            ),
            "Notifications" to listOf(
                SettingRow("Release alerts", "Tell me when a title I follow arrives"),
                SettingRow("Episode alerts", "Tell me when a new episode airs")
            )
        )
    }
    val icons = mapOf(
        "Theme" to Icons.Default.Palette,
        "Palette" to Icons.Default.Palette,
        "Sync with cloud" to Icons.Default.Sync,
        "Background sync" to Icons.Default.Cloud,
        "Release alerts" to Icons.Default.Notifications,
        "Episode alerts" to Icons.Default.Notifications
    )

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

        items(groups) { (group, rows) ->
            Column {
                M3ESectionHeader(group)
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = M3ESpacing.tightGap)) {
                        rows.forEachIndexed { index, row ->
                            SettingLine(
                                row = row,
                                icon = icons[row.title] ?: Icons.Default.Cloud,
                                divider = index < rows.lastIndex
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(M3ESpacing.pageVertical)) }
    }
}

private data class SettingRow(val title: String, val supporting: String)

/** One row: leading icon, title over supporting text, switch trailing. */
@Composable
private fun SettingLine(row: SettingRow, icon: ImageVector, divider: Boolean) {
    var checked by remember { mutableStateOf(true) }
    Column {
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
                    text = row.title,
                    style = M3EType.rowTitle(),
                    color = M3EColor.text(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.supporting,
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(M3ESpacing.tightGap))
            Switch(checked = checked, onCheckedChange = { checked = it })
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = M3ESpacing.cardGap),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
