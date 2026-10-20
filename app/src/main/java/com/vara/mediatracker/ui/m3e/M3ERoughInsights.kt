package com.vara.mediatracker.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vara.mediatracker.di.AppContainer
import com.vara.mediatracker.ui.common.LibraryCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The library in numbers.
 *
 * Deliberately not a copy of the Mac's Insights screen, which can afford four charts and a
 * scoped drill-down. This is what fits a phone without becoming a spreadsheet: the totals that
 * answer "how much", one year of activity, and the three facets the Hub already browses by.
 */
@Composable
internal fun M3ERoughInsights(
    container: AppContainer,
    core: LibraryCore
) {
    val insights by produceState(InsightsCalculator.compute(emptyList(), emptyList()), core.items) {
        value = withContext(Dispatchers.Default) {
            val events = runCatching { container.database.watchHistoryDao().getAllEvents() }
                .getOrDefault(emptyList())
            InsightsCalculator.compute(core.items, events)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(M3EColor.page())
            .statusBarsPadding()
            .padding(horizontal = M3ESpacing.page),
        verticalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)
    ) {
        item {
            Spacer(Modifier.height(M3ESpacing.pageVertical))
            M3EScreenHeader(
                eyebrow = "${insights.titles} titles",
                title = "Insights"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(M3ESpacing.cardGap)) {
                MetricTile("Hours", "${insights.hours}", Modifier.weight(1f))
                MetricTile("Episodes", "${insights.episodesWatched}", Modifier.weight(1f))
                MetricTile("Finished", "${insights.completed}", Modifier.weight(1f))
            }
        }

        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text("Last 12 months", style = M3EType.rowTitleStrong(), color = M3EColor.text())
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    ActivityChart(insights.byMonth)
                    insights.busiest?.let { busiest ->
                        Spacer(Modifier.height(M3ESpacing.tightGap))
                        Text(
                            text = "Busiest month: ${busiest.month} · ${busiest.watches} watched",
                            style = M3EType.rowSupporting(),
                            color = M3EColor.textMuted()
                        )
                    }
                }
            }
        }

        item {
            M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
                    Text("The shelf", style = M3EType.rowTitleStrong(), color = M3EColor.text())
                    Spacer(Modifier.height(M3ESpacing.tightGap))
                    StatLine("Movies", "${insights.movies}")
                    StatLine("Shows", "${insights.shows}")
                    StatLine("In progress", "${insights.inProgress}")
                    StatLine("Wishlist", "${insights.wishlist}")
                }
            }
        }

        item { FacetCard("Top genres", Icons.Default.Category, M3EColor.warning(), insights.topGenres) }
        item { FacetCard("Networks", Icons.Default.LiveTv, M3EColor.positive(), insights.topNetworks) }
        item { FacetCard("Languages", Icons.Default.Schedule, M3EColor.accent(), insights.topLanguages) }

        item { Spacer(Modifier.height(LocalNavClearance.current)) }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    M3ETonalCard(modifier = modifier) {
        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
            Text(value, style = M3EType.metric(), color = M3EColor.text())
            Text(label, style = M3EType.rowSupporting(), color = M3EColor.textMuted())
        }
    }
}

@Composable
private fun ActivityChart(buckets: List<MonthBucket>) {
    val peak = (buckets.maxOfOrNull { it.watches } ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        buckets.forEach { bucket ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(bucket.watches.toFloat() / peak)
                        .clip(M3EShape.chip())
                        .background(
                            if (bucket.watches == 0) {
                                M3EColor.card()
                            } else {
                                M3EColor.accent().copy(alpha = 0.35f + 0.65f * bucket.watches / peak)
                            }
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = bucket.label(),
                    style = M3EType.rowSupporting(),
                    color = M3EColor.textMuted()
                )
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = M3EType.rowTitle(), color = M3EColor.text())
        Text(value, style = M3EType.rowTitleStrong(), color = M3EColor.accent())
    }
}

@Composable
private fun FacetCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    facets: List<FacetCount>
) {
    if (facets.isEmpty()) return
    M3ETonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(M3ESpacing.cardGap)) {
            M3ESectionHeader(title, icon = icon, tint = tint)
            Spacer(Modifier.height(M3ESpacing.tightGap))
            facets.forEachIndexed { index, facet ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialThemeOutline())
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = facet.name,
                        style = M3EType.rowTitle(),
                        color = M3EColor.text(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${facet.count}",
                        style = M3EType.rowTitleStrong(),
                        color = tint
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialThemeOutline() =
    androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
