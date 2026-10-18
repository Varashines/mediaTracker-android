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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.CollectionEntity
import com.vara.mediatracker.data.model.CollectionItemEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import com.vara.mediatracker.data.model.SmartRules
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import com.vara.mediatracker.ui.theme.AppTextStyles

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
    val library = core.items
    val collections by produceState(initialValue = emptyList<CollectionEntity>()) {
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
    fun membersOf(collection: CollectionEntity): List<MediaItemEntity> =
        if (collection.isSmart) {
            val rules = SmartRules.decode(collection.smartRules)
            library.filter { SmartRules.matches(it, badgeLabels[it.id], rules) }
        } else {
            members
                .filter { it.collectionId == collection.id && !it.isDeleted }
                .mapNotNull { itemsById[it.mediaId] }
        }

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
                M3ESectionHeader("Smart playlists (${smart.size})")
                Text(
                    text = "Kept up to date from your ratings and progress.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
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
            items(smart, key = { it.id }) { collection ->
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
                M3ESectionHeader("Your collections (${mine.size})")
                Text(
                    text = "Made by hand, on either device.",
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
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
            items(mine, key = { it.id }) { collection ->
                val titles = membersOf(collection)
                CollectionCard(
                    name = collection.name,
                    count = titles.size,
                    covers = titles.take(3).map { it.posterUrl },
                    isSmart = false,
                    onClick = { onOpenList(collection.name, titles, ListSubtitle.None, ListLayout.Grid) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(112.dp))
        }
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
                style = M3EType.rowTitle(),
                fontWeight = FontWeight.SemiBold,
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
