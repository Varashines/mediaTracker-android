package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.SmartRules
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.theme.AppTextStyles
import kotlinx.coroutines.launch

/**
 * Collections, in the [M3E] vocabulary.
 *
 * A two-column grid of tonal cards, each showing a cover strip from the titles it holds, its name
 * and how many it contains. Smart collections are marked, because Material's colour tactic is
 * about telling you something the label does not — and "this one keeps itself up to date" is
 * exactly that.
 *
 * Tapping a card opens what it holds. A manual collection lists its members; a smart one is
 * evaluated against its rules, which is also what its count has to be: counting stored member rows
 * for a smart collection counted nothing, because a smart collection has none.
 */
@Composable
internal fun M3ERoughCollections(
    container: AppContainer,
    core: LibraryCore,
    onOpenList: (String, List<MediaItemEntity>, ListSubtitle, ListLayout) -> Unit = { _, _, _, _ -> },
    /** False when embedded in the Hub, which supplies its own header. */
    showHeader: Boolean = true
) {
    // A cold start renders the empty state before Room answers — "0 titles", "Your library is up
    // to date" — which reads as an empty library rather than a loading one.
    if (!core.loaded) {
        M3ESkeleton()
        return
    }

    val library = core.items
    val scope = rememberCoroutineScope()
    // Bumped after every write, because the DAO's read is a one-shot rather than a Flow.
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionEntity?>(null) }
    var deleting by remember { mutableStateOf<CollectionEntity?>(null) }
    val collections by produceState(initialValue = emptyList<CollectionEntity>(), revision) {
        value = runCatching { container.database.collectionDao().getAllCollections() }.getOrDefault(emptyList())
    }
    val members by produceState(initialValue = emptyList<CollectionItemEntity>()) {
        value = runCatching { container.database.collectionDao().getAllCollectionItems() }.getOrDefault(emptyList())
    }

    val live = remember(collections) { collections.filter { !it.isDeleted } }
    val smart = remember(live) { live.filter { it.isSmart } }
    val mine = remember(live) { live.filterNot { it.isSmart } }

    val badgeLabels = remember(core) { core.badges.mapValues { it.value.badge.label } }
    val itemsById = remember(library) { library.associateBy { it.id } }

    // Smart membership is evaluated from the rule set the Mac wrote; manual membership is the
    // stored rows. Both end up as titles, so a card, its count and its covers all agree.
    //
    // Computed once for every collection, not per card. A smart collection runs its rules over the
    // whole library, and this used to be called from inside items(), so the list did
    // collections × titles of rule matching on every recomposition. Manual rows are grouped once
    // for the same reason.
    val membership: Map<String, List<MediaItemEntity>> = remember(live, library, badgeLabels, members, itemsById) {
        val rowsByCollection = members
            .filterNot { it.isDeleted }
            .groupBy({ it.collectionId }, { it.mediaId })
        live.associate { collection ->
            val titles = if (collection.isSmart) {
                val rules = SmartRules.decode(collection.smartRules)
                library.filter { SmartRules.matches(it, badgeLabels[it.id], rules) }
            } else {
                rowsByCollection[collection.id].orEmpty().mapNotNull { itemsById[it] }
            }
            collection.id to titles
        }
    }

    fun membersOf(collection: CollectionEntity): List<MediaItemEntity> =
        membership[collection.id].orEmpty()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .let { if (showHeader) it.statusBarsPadding() else it }
            .padding(horizontal = M3ESpacing.page),
        horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        if (showHeader) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(M3ESpacing.pageVertical))
                M3EScreenHeader(eyebrow = "${live.size} collections", title = "Collections")
                Spacer(Modifier.height(M3ESpacing.cardGap))
            }
        }

        if (live.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                        Text("No collections yet", style = M3EType.rowTitle(), color = M3EColor.text())
                        Text(
                            text = "Collections you make on either device appear here.",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                M3ESectionHeader(
                    "Smart playlists (${smart.size})",
                    icon = Icons.Default.AutoAwesome,
                    tint = M3EColor.accent()
                )
            }
        }
        if (smart.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "None yet.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        } else {
            items(smart, key = { "smart_${it.id}" }) { collection ->
                val titles = membersOf(collection)
                CollectionCard(
                    name = collection.name,
                    count = titles.size,
                    covers = titles.take(3).map { it.posterUrl },
                    isSmart = true,
                    onClick = { onOpenList(collection.name, titles, ListSubtitle.None, ListLayout.Grid) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(M3ESpacing.tightGap))
                M3ESectionHeader(
                    "Your collections (${mine.size})",
                    icon = Icons.Default.Bookmark,
                    tint = M3EColor.positive(),
                    trailing = {
                        TextButton(onClick = { creating = true }) {
                            Text("New", style = M3EType.action())
                        }
                    }
                )
            }
        }
        if (mine.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "None yet.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        } else {
            items(mine, key = { "mine_${it.id}" }) { collection ->
                val titles = membersOf(collection)
                CollectionCard(
                    name = collection.name,
                    count = titles.size,
                    covers = titles.take(3).map { it.posterUrl },
                    isSmart = false,
                    onMore = { renaming = collection },
                    onClick = { onOpenList(collection.name, titles, ListSubtitle.None, ListLayout.Grid) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(LocalNavClearance.current))
        }
    }

    if (creating || renaming != null || deleting != null) {
        var draft by remember(creating, renaming, deleting) {
            mutableStateOf(renaming?.name ?: "")
        }
        AlertDialog(
            onDismissRequest = { creating = false; renaming = null; deleting = null },
            title = {
                Text(
                    when {
                        deleting != null -> "Delete \"${deleting!!.name}\"?"
                        renaming != null -> "Rename collection"
                        else -> "New collection"
                    }
                )
            },
            text = {
                when {
                    deleting != null -> Text(
                        "It disappears here and on the other device. The titles stay in your library.",
                        style = M3EType.rowSupporting()
                    )
                    else -> OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deleting != null || draft.isNotBlank(),
                    onClick = {
                        val now = com.vara.mediatracker.data.TrackingLogic.isoNow()
                        val dao = container.database.collectionDao()
                        val target = renaming
                        val doomed = deleting
                        when {
                            doomed != null -> scope.launch { dao.softDelete(doomed.id, now); revision++ }
                            target != null -> scope.launch { dao.rename(target.id, draft.trim(), now); revision++ }
                            else -> scope.launch {
                                dao.upsertCollection(
                                    CollectionEntity(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = draft.trim(),
                                        updatedAt = now
                                    )
                                )
                                revision++
                            }
                        }
                        creating = false
                        renaming = null
                        deleting = null
                    }
                ) {
                    Text(if (deleting != null) "Delete" else "Save")
                }
            },
            dismissButton = {
                if (deleting == null) {
                    TextButton(onClick = { creating = false; renaming = null; deleting = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

/**
 * A collection card: a cover strip over the name and count.
 *
 * The strip is the sanctioned composition here — three rounded posters in a row read as one
 * object, which no single Material component expresses.
 */
@Composable
private fun CollectionCard(
    name: String,
    count: Int,
    covers: List<String?>,
    isSmart: Boolean,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    M3ETonalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(M3ESpacing.inlineGap)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(M3EShape.chip())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (covers.isEmpty()) {
                    Spacer(Modifier.fillMaxWidth())
                } else {
                    covers.forEach { poster ->
                        AsyncImage(
                            model = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxSize()
                        )
                    }
                }
            }
            Spacer(Modifier.height(M3ESpacing.tightGap))
            Text(
                text = name,
                style = M3EType.rowTitleStrong(),
                color = M3EColor.text(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$count ${if (count == 1) "title" else "titles"}",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
                if (onMore != null) {
                    Spacer(Modifier.weight(1f))
                    // A collection that cannot be renamed is a collection you have to live with.
                    IconButton(onClick = onMore, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Collection actions",
                            tint = M3EColor.textMuted(),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (isSmart) {
                    Spacer(Modifier.width(M3ESpacing.tightGap))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = M3EColor.emphasis(),
                        contentColor = M3EColor.onEmphasis()
                    ) {
                        Text(
                            text = "SMART",
                            style = AppTextStyles.MonoLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
