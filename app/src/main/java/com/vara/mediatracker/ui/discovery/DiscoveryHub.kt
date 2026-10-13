package com.vara.mediatracker.ui.discovery

import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import java.util.Locale

/** A facet value with its library count, e.g. "Netflix · 12". */
data class DiscoveryNode(
    val name: String,
    val code: String? = null,
    val count: Int
)

/** The facets the Discovery Hub can aggregate and filter by. */
enum class DiscoveryFacet {
    NETWORK, STUDIO, GENRE, LANGUAGE, PROVIDER, BADGE
}

/**
 * Aggregates the library into Discovery Hub facets.
 *
 * macOS builds dedicated Network/Genre/Language/Provider entities in
 * `DiscoverySyncService`; Android stores the facets on the item and groups them here at
 * read time, so this is a pure function over the loaded library and needs no database.
 */
object DiscoveryAggregator {

    /** Networks with at least this many titles are worth surfacing. */
    private const val NETWORK_MIN = 2

    /**
     * Broadcast/streaming networks, from TV shows only. A movie's `network` column
     * holds its production company (used for the streaming drop-time rule), which is a
     * studio, not a network — so movies are excluded here and surface under Studios.
     */
    fun networks(items: List<MediaItemEntity>): List<DiscoveryNode> =
        aggregate(
            items.filter { it.type.contains("TV", ignoreCase = true) }.mapNotNull { it.network }
        ).filter { it.count >= NETWORK_MIN }

    /**
     * Production companies, from movies only. TV networks are surfaced via the network
     * facet, so a show never contributes to Studios (matching macOS, where a studio is a
     * movie's production company and a network is a show's broadcaster).
     */
    fun studios(items: List<MediaItemEntity>): List<DiscoveryNode> =
        aggregateList(
            items.filter { !it.type.contains("TV", ignoreCase = true) }.mapNotNull { it.studios }
        ).filter { it.count >= NETWORK_MIN }

    fun genres(items: List<MediaItemEntity>): List<DiscoveryNode> =
        aggregateList(items.mapNotNull { it.genres })

    fun providers(items: List<MediaItemEntity>): List<DiscoveryNode> =
        aggregateList(items.mapNotNull { it.providers })

    fun languages(items: List<MediaItemEntity>): List<DiscoveryNode> =
        aggregate(items.mapNotNull { it.language }).map { node ->
            DiscoveryNode(name = languageName(node.name), code = node.name, count = node.count)
        }

    fun badges(rows: List<MediaBadgeEntity>): List<DiscoveryNode> =
        rows.groupingBy { it.badgeLabel }
            .eachCount()
            .map { (label, count) -> DiscoveryNode(name = label, count = count) }
            .sortedWith(nodeOrder)

    /** True when [item] belongs to the facet value [nodeName] (or [badgeLabel] for BADGE). */
    fun matches(
        facet: DiscoveryFacet,
        item: MediaItemEntity,
        nodeName: String,
        badgeLabel: String? = null
    ): Boolean {
        if (item.isSoftDeleted) return false
        return when (facet) {
            DiscoveryFacet.NETWORK -> item.network.equals(nodeName, ignoreCase = true)
            DiscoveryFacet.STUDIO -> containsValue(item.studios, nodeName)
            DiscoveryFacet.GENRE -> containsValue(item.genres, nodeName)
            DiscoveryFacet.PROVIDER -> containsValue(item.providers, nodeName)
            DiscoveryFacet.LANGUAGE -> item.language.equals(nodeName, ignoreCase = true)
            DiscoveryFacet.BADGE -> badgeLabel.equals(nodeName, ignoreCase = true)
        }
    }

    private fun containsValue(csv: String?, value: String): Boolean =
        csv?.split(",")?.any { it.trim().equals(value, ignoreCase = true) } == true

    private fun aggregate(values: List<String>): List<DiscoveryNode> =
        values.filter { it.isNotBlank() }
            .groupingBy { it.trim() }
            .eachCount()
            .map { (name, count) -> DiscoveryNode(name = name, count = count) }
            .sortedWith(nodeOrder)

    private fun aggregateList(values: List<String>): List<DiscoveryNode> =
        values.filter { it.isNotBlank() }
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> DiscoveryNode(name = name, count = count) }
            .sortedWith(nodeOrder)

    private val nodeOrder =
        compareByDescending<DiscoveryNode> { it.count }.thenBy { it.name.lowercase(Locale.US) }

    private fun languageName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { code.uppercase(Locale.US) }
    }
}
