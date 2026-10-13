package com.vara.mediatracker.ui.discovery

import com.vara.mediatracker.data.model.MediaBadgeEntity
import com.vara.mediatracker.data.model.MediaItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryAggregatorTest {

    private fun item(
        id: String,
        type: String = "TV Show",
        network: String? = null,
        genres: String? = null,
        studios: String? = null,
        providers: String? = null,
        language: String? = null,
        softDeleted: Boolean = false
    ) = MediaItemEntity(
        id = id,
        title = id,
        type = type,
        network = network,
        genres = genres,
        studios = studios,
        providers = providers,
        language = language,
        isSoftDeleted = softDeleted
    )

    @Test
    fun `networks are counted and sorted by count`() {
        val items = listOf(
            item("a", network = "Netflix"),
            item("b", network = "Netflix"),
            item("c", network = "HBO"),
            item("d", network = "HBO"),
            item("e", network = "HBO"),
            item("f", network = null)
        )
        val nets = DiscoveryAggregator.networks(items)
        assertEquals(listOf("HBO", "Netflix"), nets.map { it.name })
        assertEquals(listOf(3, 2), nets.map { it.count })
    }

    @Test
    fun `networks below the threshold are hidden`() {
        val items = listOf(item("a", network = "Solo"))
        assertTrue(DiscoveryAggregator.networks(items).isEmpty())
    }

    @Test
    fun `comma-joined facets are split and counted`() {
        val items = listOf(
            item("a", genres = "Drama, Thriller"),
            item("b", genres = "Drama"),
            item("c", providers = "Netflix, Hulu")
        )
        val genres = DiscoveryAggregator.genres(items)
        assertEquals(listOf("Drama", "Thriller"), genres.map { it.name })
        assertEquals(listOf(2, 1), genres.map { it.count })

        val providers = DiscoveryAggregator.providers(items)
        assertEquals(listOf("Hulu", "Netflix"), providers.map { it.name }.sorted())
    }

    @Test
    fun `languages are shown by display name and keep the code`() {
        val items = listOf(item("a", language = "en"), item("b", language = "en"), item("c", language = "ko"))
        val langs = DiscoveryAggregator.languages(items)
        val english = langs.first { it.code == "en" }
        assertEquals("English", english.name)
        assertEquals(2, english.count)
    }

    @Test
    fun `badges are aggregated from persisted rows`() {
        val rows = listOf(
            MediaBadgeEntity("a", "NEW", false, null, 0),
            MediaBadgeEntity("b", "NEW", false, null, 0),
            MediaBadgeEntity("c", "PREMIERE", true, null, 0)
        )
        val badges = DiscoveryAggregator.badges(rows)
        assertEquals(listOf("NEW", "PREMIERE"), badges.map { it.name })
        assertEquals(listOf(2, 1), badges.map { it.count })
    }

    @Test
    fun `matches filters by the right facet`() {
        val it = item("a", network = "Netflix", genres = "Drama, Thriller", providers = "Netflix", language = "en")
        assertTrue(DiscoveryAggregator.matches(DiscoveryFacet.NETWORK, it, "netflix"))
        assertFalse(DiscoveryAggregator.matches(DiscoveryFacet.NETWORK, it, "HBO"))
        assertTrue(DiscoveryAggregator.matches(DiscoveryFacet.GENRE, it, "Thriller"))
        assertFalse(DiscoveryAggregator.matches(DiscoveryFacet.GENRE, it, "Comedy"))
        assertTrue(DiscoveryAggregator.matches(DiscoveryFacet.PROVIDER, it, "Netflix"))
        assertTrue(DiscoveryAggregator.matches(DiscoveryFacet.LANGUAGE, it, "en"))
    }

    @Test
    fun `badge facet matches on the item badge label`() {
        val it = item("a")
        assertTrue(DiscoveryAggregator.matches(DiscoveryFacet.BADGE, it, "NEW", badgeLabel = "NEW"))
        assertFalse(DiscoveryAggregator.matches(DiscoveryFacet.BADGE, it, "NEW", badgeLabel = "SOON"))
    }

    @Test
    fun `soft deleted items never match`() {
        val it = item("a", network = "Netflix", softDeleted = true)
        assertFalse(DiscoveryAggregator.matches(DiscoveryFacet.NETWORK, it, "Netflix"))
    }
}
