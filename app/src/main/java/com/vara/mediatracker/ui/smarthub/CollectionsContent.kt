package com.vara.mediatracker.ui.smarthub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.SmartRules
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.AppToast
import com.vara.mediatracker.ui.common.rememberHaptics
import com.vara.mediatracker.ui.theme.AppSpacing
import com.vara.mediatracker.ui.theme.ExpressiveShapes
import com.vara.mediatracker.util.WireTime
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The Hub's Collections tab: user-made collections, created here or synced from macOS.
 *
 * Smart collections are macOS-only for now (their rule engine isn't ported), so this lists
 * manual collections; tapping one opens its titles through the same list surface the Smart
 * tab uses.
 */
@Composable
fun CollectionsContent(
    container: AppContainer,
    items: List<MediaItemEntity>,
    onOpenCollection: (title: String, filtered: List<MediaItemEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    val dao = remember(container) { container.database.collectionDao() }
    val collections by dao.observeCollections().collectAsState(initial = emptyList())
    val membership by dao.observeAllItems().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val itemsById = remember(items) { items.associateBy { it.id } }
    val membersByCollection = remember(membership) { membership.groupBy { it.collectionId } }
    // Badge labels feed smart rules that match on badge.
    val badgeRows by container.database.badgeDao().observeBadges().collectAsState(initial = emptyList())
    val badgeLabels = remember(badgeRows) { badgeRows.associate { it.mediaId to it.badgeLabel } }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionEntity?>(null) }
    var deleting by remember { mutableStateOf<CollectionEntity?>(null) }

    fun stamp(): String = WireTime.now()

    if (creating || renaming != null) {
        CollectionNameDialog(
            title = if (renaming == null) "New collection" else "Rename collection",
            initialName = renaming?.name ?: "",
            onDismiss = { creating = false; renaming = null },
            onConfirm = { name ->
                val target = renaming
                scope.launch {
                    val now = stamp()
                    if (target == null) {
                        val created = CollectionEntity(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            systemImage = "folder",
                            updatedAt = now
                        )
                        dao.upsertCollection(created)
                        container.syncManager().pushCollections(listOf(created))
                        AppToast.show("Created \"$name\"")
                    } else {
                        val updated = target.copy(name = name, updatedAt = now)
                        dao.upsertCollection(updated)
                        container.syncManager().pushCollections(listOf(updated))
                        AppToast.show("Renamed to \"$name\"")
                    }
                }
                creating = false
                renaming = null
            }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = { Text("Removes the collection everywhere; the titles themselves stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    val now = stamp()
                    val tombstone = target.copy(isDeleted = true, updatedAt = now)
                    scope.launch {
                        dao.upsertCollection(tombstone)
                        container.syncManager().pushCollections(listOf(tombstone))
                        AppToast.show("Deleted \"${target.name}\"")
                    }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = AppSpacing.lg, top = 4.dp, end = AppSpacing.lg, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "new") {
            Surface(
                onClick = { creating = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "New collection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (collections.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Bookmarks, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No collections yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Create one here, or they'll appear from your other device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(collections, key = { it.id }) { collection ->
            // Smart collections store rules rather than members, so evaluate them against the
            // library the same way macOS does. Unconditional remembers keep composition stable
            // across the isSmart branch.
            val ruleSet = remember(collection.id, collection.isSmart, collection.smartRules) {
                if (collection.isSmart) SmartRules.decode(collection.smartRules) else null
            }
            val members = remember(items, badgeLabels, ruleSet, membersByCollection, itemsById, collection.id) {
                if (ruleSet != null) {
                    items.filter { SmartRules.matches(it, badgeLabels[it.id], ruleSet) }
                } else {
                    membersByCollection[collection.id].orEmpty().mapNotNull { itemsById[it.mediaId] }
                }
            }
            CollectionRow(
                collection = collection,
                memberCount = members.size,
                ruleSummary = ruleSet?.summary,
                onClick = { onOpenCollection(collection.name, members) },
                onLongClick = {
                    haptics.toggle()
                    renaming = collection
                },
                onDelete = { deleting = collection }
            )
        }
    }
}

@Composable
private fun CollectionRow(
    collection: CollectionEntity,
    memberCount: Int,
    ruleSummary: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = ExpressiveShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (collection.isSmart) Icons.Default.AutoAwesome else Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$memberCount ${if (memberCount == 1) "title" else "titles"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ruleSummary != null) {
                    Text(
                        ruleSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                onClick = onDelete,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete collection", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CollectionNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.trim().isNotEmpty(),
                onClick = { onConfirm(text.trim()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
