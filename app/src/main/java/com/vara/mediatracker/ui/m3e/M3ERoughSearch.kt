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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vara.mediatracker.data.model.MediaItemEntity

/**
 * Search, in the [M3E] vocabulary: a Material text field that is the first thing on the screen,
 * results in the same dense grid the library uses, and an empty state that says what to do.
 *
 * Matching is local and deliberately naive — this is a sketch of the language. Wiring it to
 * TMDB search is the migration.
 */
@Composable
fun M3ERoughSearch(library: List<MediaItemEntity>, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(library, query) {
        if (query.isBlank()) emptyList()
        else library.filter { it.title.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = M3ESpacing.tightGap, vertical = M3ESpacing.tightGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = M3EColor.text())
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search titles", style = M3EType.rowSupporting()) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = M3EShape.chip(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = M3EColor.card(),
                    unfocusedContainerColor = M3EColor.card()
                )
            )
        }
        Spacer(Modifier.height(M3ESpacing.cardGap))

        when {
            query.isBlank() -> M3ETonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
            ) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text("Search your library", style = M3EType.rowTitle(), color = M3EColor.text())
                    Text(
                        text = "Type a title to find it here.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                }
            }

            results.isEmpty() -> M3ETonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = M3ESpacing.page)
            ) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text("Nothing found", style = M3EType.rowTitle(), color = M3EColor.text())
                    Text(
                        text = "No title matches “$query”.",
                        style = M3EType.rowSupporting(),
                        color = M3EColor.textMuted()
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = M3ESpacing.page),
                horizontalArrangement = Arrangement.spacedBy(M3ESpacing.inlineGap),
                verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
            ) {
                items(results, key = { it.id }) { item ->
                    Column {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(M3EShape.chip())
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                        Spacer(Modifier.height(M3ESpacing.tightGap))
                        Text(
                            text = item.title,
                            style = M3EType.rowSupporting(),
                            color = M3EColor.text(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(M3ESpacing.sectionGap))
                }
            }
        }
    }
}
