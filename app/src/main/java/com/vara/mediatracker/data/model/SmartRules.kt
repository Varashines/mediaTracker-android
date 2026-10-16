package com.vara.mediatracker.data.model

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * macOS's `SmartRule` / `SmartRuleSet`, ported so smart collections created on the Mac can be
 * evaluated here too.
 *
 * Wire format (macOS `SmartRules.swift`): the set is `{"matchAny": Bool, "rules": [ … ]}` and
 * each rule is a single-key object holding Swift's associated-value wrapper —
 * `{"genre": {"_0": ["Action"]}}`, `{"releaseYear": {"_0": 2020, "_1": "is"}}`. The legacy
 * pre-wrapper forms (`{"genre": ["Action"]}`, a bare `[SmartRule]` array) are accepted as well,
 * matching macOS's own decoder.
 */
sealed interface SmartRule {
    data class Genre(val values: List<String>) : SmartRule
    data class ReleaseYear(val year: Int, val comparison: String) : SmartRule
    data class ReleaseYearRange(val start: Int, val end: Int) : SmartRule
    data class MediaType(val values: List<String>) : SmartRule
    data class State(val values: List<String>) : SmartRule
    data class Taste(val values: List<String>) : SmartRule
    data class Badge(val values: List<String>) : SmartRule
    data class Network(val values: List<String>) : SmartRule
    data class Language(val values: List<String>) : SmartRule
}

/** A smart collection's complete rule configuration (mirrors macOS `SmartRuleSet`). */
data class SmartRuleSet(
    /** false = every rule must hold (AND); true = any (OR). */
    val matchAny: Boolean = false,
    val rules: List<SmartRule> = emptyList()
) {
    /** "All: X · Y" / "Any of: X, Y", first 3 rules + overflow — same as macOS's `summary`. */
    val summary: String
        get() {
            if (rules.isEmpty()) return "Everything in your library"
            val prefix = rules.take(3).joinToString(if (matchAny) ", " else " · ") { labelFor(it) }
            val more = if (rules.size > 3) " …+${rules.size - 3} more" else ""
            return (if (matchAny) "Any of: " else "All: ") + prefix + more
        }

    private fun labelFor(rule: SmartRule): String = when (rule) {
        is SmartRule.Genre -> "Genre: ${rule.values.joinToString(", ")}"
        is SmartRule.ReleaseYear -> "Year ${rule.comparison} ${rule.year}"
        is SmartRule.ReleaseYearRange -> "Years: ${rule.start}–${rule.end}"
        is SmartRule.MediaType -> "Type: ${rule.values.joinToString(", ")}"
        is SmartRule.State -> "Status: ${rule.values.joinToString(", ")}"
        is SmartRule.Taste -> "Taste: ${rule.values.joinToString(", ")}"
        is SmartRule.Badge -> "Badge: ${rule.values.joinToString(", ")}"
        is SmartRule.Network -> "Network: ${rule.values.joinToString(", ")}"
        is SmartRule.Language -> "Language: ${rule.values.joinToString(", ")}"
    }
}

object SmartRules {

    private const val TAG = "SmartRules"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Decodes macOS's JSON blob, never throwing.
     *
     * An empty/undecodable set yields an empty [SmartRuleSet], which matches everything — the
     * same "reset to empty" fallback macOS uses (so a collection degrades instead of vanishing).
     */
    fun decode(text: String?): SmartRuleSet {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "{}") return SmartRuleSet()
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrElse {
            Log.w(TAG, "Smart rules failed to parse — treating as empty (matches everything)")
            return SmartRuleSet()
        }
        return when (element) {
            is JsonArray -> SmartRuleSet(matchAny = false, rules = element.mapNotNull { decodeRule(it) })
            is JsonObject -> {
                if (element.containsKey("rules") || element.containsKey("matchAny")) {
                    SmartRuleSet(
                        matchAny = (element["matchAny"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        rules = (element["rules"] as? JsonArray)?.mapNotNull { decodeRule(it) }.orEmpty()
                    )
                } else {
                    SmartRuleSet(matchAny = false, rules = listOfNotNull(decodeRule(element)))
                }
            }
            else -> SmartRuleSet()
        }
    }

    private fun decodeRule(element: JsonElement): SmartRule? {
        val obj = element as? JsonObject ?: return null
        val entry = obj.entries.firstOrNull() ?: return null
        val (key, value) = entry

        // Swift wraps associated values: {"_0": payload} / {"_0": a, "_1": b}. The older
        // direct forms (payload alone, or a 2-element array) are accepted too.
        val pair: Pair<JsonElement, JsonElement?>? = when (value) {
            is JsonObject -> value["_0"]?.let { it to value["_1"] }
            is JsonArray -> if (value.size == 2) value[0] to value[1] else null
            else -> null
        }
        val inner = pair?.first ?: value
        val second = pair?.second

        return when (key) {
            "genre" -> SmartRule.Genre(strings(inner))
            "mediaType" -> SmartRule.MediaType(strings(inner))
            "state" -> SmartRule.State(strings(inner))
            "taste" -> SmartRule.Taste(strings(inner))
            "badge" -> SmartRule.Badge(strings(inner))
            "network" -> SmartRule.Network(strings(inner))
            "language" -> SmartRule.Language(strings(inner))
            "releaseYear" -> {
                val year = (inner as? JsonPrimitive)?.intOrNull ?: return null
                SmartRule.ReleaseYear(year, (second as? JsonPrimitive)?.contentOrNull ?: "is")
            }
            "releaseYearRange" -> {
                val start = (inner as? JsonPrimitive)?.intOrNull ?: return null
                val end = (second as? JsonPrimitive)?.intOrNull ?: return null
                SmartRule.ReleaseYearRange(start, end)
            }
            else -> null
        }
    }

    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    /** Evaluates [ruleSet] against [item], mirroring macOS's `applySmartRule`. */
    fun matches(
        item: MediaItemEntity,
        badgeLabel: String?,
        ruleSet: SmartRuleSet
    ): Boolean {
        if (ruleSet.rules.isEmpty()) return true
        val satisfies = { rule: SmartRule -> satisfies(item, badgeLabel, rule) }
        return if (ruleSet.matchAny) ruleSet.rules.any(satisfies) else ruleSet.rules.all(satisfies)
    }

    private fun satisfies(item: MediaItemEntity, badgeLabel: String?, rule: SmartRule): Boolean = when (rule) {
        // macOS does a substring test against the comma-joined genre string.
        is SmartRule.Genre -> item.genres?.let { genres -> rule.values.any { genres.contains(it) } } ?: false
        is SmartRule.ReleaseYear -> {
            val year = releaseYear(item) ?: return false
            when (rule.comparison) {
                "after" -> year > rule.year
                "before" -> year < rule.year
                else -> year == rule.year
            }
        }
        is SmartRule.ReleaseYearRange -> {
            val year = releaseYear(item) ?: return false
            year in rule.start..rule.end
        }
        is SmartRule.MediaType -> rule.values.any { item.type.equals(it, ignoreCase = true) }
        is SmartRule.State -> rule.values.any { item.state.equals(it, ignoreCase = true) }
        is SmartRule.Taste -> rule.values.any { Taste.normalize(it).equals(Taste.normalize(item.taste), true) }
        is SmartRule.Badge -> badgeLabel != null && rule.values.any { it.equals(badgeLabel, ignoreCase = true) }
        is SmartRule.Network -> {
            val networks = item.network?.split(',')?.map { it.trim().lowercase() }.orEmpty()
            rule.values.any { networks.contains(it.lowercase()) }
        }
        is SmartRule.Language -> rule.values.any { item.language?.equals(it, ignoreCase = true) == true }
    }

    private fun releaseYear(item: MediaItemEntity): Int? = item.releaseDate?.take(4)?.toIntOrNull()
}
