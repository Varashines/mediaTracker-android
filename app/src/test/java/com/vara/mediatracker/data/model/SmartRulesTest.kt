package com.vara.mediatracker.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the smart-rule port to the wire format macOS actually writes
 * (`SmartRules.swift`: `{"matchAny":…, "rules":[{"genre":{"_0":[…]}}]}`) and to its
 * evaluation semantics (`MediaFilterActor.applySmartRule`).
 */
class SmartRulesTest {

    private fun item(
        id: String = "movie_1",
        type: String = "Movie",
        state: String = "Wishlist",
        taste: String = "None",
        genres: String? = "Action, Comedy",
        network: String? = "Netflix",
        language: String? = "en",
        releaseDate: String? = "2021-05-01"
    ) = MediaItemEntity(
        id = id,
        title = "Title $id",
        type = type,
        state = state,
        taste = taste,
        genres = genres,
        network = network,
        language = language,
        releaseDate = releaseDate
    )

    @Test
    fun `decodes the swift associated-value format`() {
        val set = SmartRules.decode(
            """{"matchAny":false,"rules":[
                {"genre":{"_0":["Action"]}},
                {"releaseYear":{"_0":2020,"_1":"after"}},
                {"state":{"_0":["Completed"]}},
                {"releaseYearRange":{"_0":2000,"_1":2010}}
            ]}"""
        )
        assertEquals(4, set.rules.size)
        assertEquals(SmartRule.Genre(listOf("Action")), set.rules[0])
        assertEquals(SmartRule.ReleaseYear(2020, "after"), set.rules[1])
        assertEquals(SmartRule.State(listOf("Completed")), set.rules[2])
        assertEquals(SmartRule.ReleaseYearRange(2000, 2010), set.rules[3])
        assertFalse(set.matchAny)
    }

    @Test
    fun `decodes legacy shapes`() {
        // Bare array of rules, direct payload, and a single (unwrapped) value.
        assertEquals(
            SmartRule.Genre(listOf("Action")),
            SmartRules.decode("""[{"genre":["Action"]}]""").rules.single()
        )
        assertEquals(
            SmartRule.Badge(listOf("NEW")),
            SmartRules.decode("""{"badge":"NEW"}""").rules.single()
        )
    }

    @Test
    fun `empty or broken rule sets match everything`() {
        assertTrue(SmartRules.matches(item(), null, SmartRules.decode(null)))
        assertTrue(SmartRules.matches(item(), null, SmartRules.decode("{}")))
        assertEquals("Everything in your library", SmartRules.decode("").summary)
    }

    @Test
    fun `match all requires every rule, match any requires one`() {
        val genre = SmartRule.Genre(listOf("Action"))
        val completed = SmartRule.State(listOf("Completed"))

        // Item is Action + Wishlist.
        assertFalse(SmartRules.matches(item(), null, SmartRuleSet(matchAny = false, rules = listOf(genre, completed))))
        assertTrue(SmartRules.matches(item(), null, SmartRuleSet(matchAny = true, rules = listOf(genre, completed))))
        assertTrue(SmartRules.matches(item(), null, SmartRuleSet(matchAny = false, rules = listOf(genre))))
    }

    @Test
    fun `genre uses the comma-joined substring test macOS uses`() {
        assertTrue(SmartRules.matches(item(genres = "Action, Comedy"), null, SmartRuleSet(rules = listOf(SmartRule.Genre(listOf("Action"))))))
        assertFalse(SmartRules.matches(item(genres = "Drama"), null, SmartRuleSet(rules = listOf(SmartRule.Genre(listOf("Action"))))))
        assertFalse(SmartRules.matches(item(genres = null), null, SmartRuleSet(rules = listOf(SmartRule.Genre(listOf("Action"))))))
    }

    @Test
    fun `network matches token-wise, language and type case-insensitively`() {
        assertTrue(SmartRules.matches(item(network = "Netflix, HBO"), null, SmartRuleSet(rules = listOf(SmartRule.Network(listOf("netflix"))))))
        assertFalse(SmartRules.matches(item(network = "Cinemax"), null, SmartRuleSet(rules = listOf(SmartRule.Network(listOf("max"))))))
        assertTrue(SmartRules.matches(item(language = "en", type = "TV Show"), null, SmartRuleSet(rules = listOf(SmartRule.Language(listOf("EN")), SmartRule.MediaType(listOf("tv show"))))))
    }

    @Test
    fun `release year comparisons`() {
        val i = item(releaseDate = "2021-05-01")
        assertTrue(SmartRules.matches(i, null, SmartRuleSet(rules = listOf(SmartRule.ReleaseYear(2020, "after")))))
        assertFalse(SmartRules.matches(i, null, SmartRuleSet(rules = listOf(SmartRule.ReleaseYear(2021, "after")))))
        assertTrue(SmartRules.matches(i, null, SmartRuleSet(rules = listOf(SmartRule.ReleaseYearRange(2020, 2022)))))
        assertFalse(SmartRules.matches(i, null, SmartRuleSet(rules = listOf(SmartRule.ReleaseYearRange(1990, 2000)))))
    }

    @Test
    fun `badge rules use the supplied badge label`() {
        val ruleSet = SmartRuleSet(rules = listOf(SmartRule.Badge(listOf("NEW"))))
        assertTrue(SmartRules.matches(item(), "NEW", ruleSet))
        assertFalse(SmartRules.matches(item(), null, ruleSet))
    }
}
