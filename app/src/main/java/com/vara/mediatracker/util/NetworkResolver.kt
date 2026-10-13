package com.vara.mediatracker.util

import androidx.compose.ui.graphics.Color
import com.vara.mediatracker.data.model.MediaItemEntity

object NetworkResolver {

    data class NetworkInfo(
        val name: String,
        val shortName: String,
        val badgeColor: Color,
        val textColor: Color
    )

    private val HBO = NetworkInfo("HBO", "HBO", Color(0xFF6B21A8), Color(0xFFE9D5FF))
    private val APPLE = NetworkInfo("Apple TV+", "TV+", Color(0xFF1E293B), Color(0xFFF1F5F9))
    private val NETFLIX = NetworkInfo("Netflix", "NFLX", Color(0xFFE50914), Color(0xFFFFFFFF))
    private val DISNEY = NetworkInfo("Disney+", "DIS+", Color(0xFF1E3A8A), Color(0xFFBFDBFE))
    private val AMAZON = NetworkInfo("Prime Video", "PRIME", Color(0xFF0369A1), Color(0xFFE0F2FE))
    private val HULU = NetworkInfo("Hulu", "HULU", Color(0xFF065F46), Color(0xFFA7F3D0))
    private val PEACOCK = NetworkInfo("Peacock", "PEA", Color(0xFF047857), Color(0xFFD1FAE5))
    private val PARAMOUNT = NetworkInfo("Paramount+", "PARA+", Color(0xFF1D4ED8), Color(0xFFDBEAFE))
    private val AMC = NetworkInfo("AMC", "AMC", Color(0xFF78350F), Color(0xFFFEF3C7))
    private val SHOWTIME = NetworkInfo("Showtime", "SHO", Color(0xFF991B1B), Color(0xFFFEE2E2))
    private val BBC = NetworkInfo("BBC", "BBC", Color(0xFF18181B), Color(0xFFFAFAFA))
    private val FX = NetworkInfo("FX", "FX", Color(0xFF3F3F46), Color(0xFFF4F4F5))

    /**
     * Resolves the network name and styling for a media item.
     * Uses title keywords, overview keywords, and item type heuristics.
     */
    fun resolve(item: MediaItemEntity): NetworkInfo {
        val title = item.title.lowercase()
        val overview = item.overview.lowercase()
        val combined = "$title $overview"

        return when {
            // Apple TV+ originals
            title.contains("severance") || title.contains("ted lasso") || title.contains("morning show") ||
            title.contains("slow horses") || title.contains("silo") || title.contains("foundation") ||
            title.contains("black bird") || title.contains("bad sisters") || title.contains("for all mankind") ||
            combined.contains("apple tv") || combined.contains("apple original") -> APPLE

            // HBO / Max originals
            title.contains("last of us") || title.contains("succession") || title.contains("white lotus") ||
            title.contains("house of the dragon") || title.contains("game of thrones") || title.contains("euphoria") ||
            title.contains("true detective") || title.contains("sopranos") || title.contains("wire") ||
            title.contains("dune: prophecy") || combined.contains("hbo") || combined.contains("max original") -> HBO

            // Netflix originals
            title.contains("stranger things") || title.contains("squid game") || title.contains("wednesday") ||
            title.contains("witcher") || title.contains("bridgerton") || title.contains("crown") ||
            title.contains("black mirror") || title.contains("arcane") || title.contains("ozark") ||
            title.contains("narcos") || combined.contains("netflix") -> NETFLIX

            // Disney+ / Marvel / Star Wars
            title.contains("mandalorian") || title.contains("andor") || title.contains("loki") ||
            title.contains("wandavision") || title.contains("ahsoka") || title.contains("falcon") ||
            combined.contains("disney+") || combined.contains("marvel studios") || combined.contains("lucasfilm") -> DISNEY

            // Amazon Prime
            title.contains("boys") || title.contains("rings of power") || title.contains("reacher") ||
            title.contains("invincible") || title.contains("fallout") || title.contains("wheel of time") ||
            title.contains("fleabag") || combined.contains("amazon prime") || combined.contains("prime video") -> AMAZON

            // AMC
            title.contains("breaking bad") || title.contains("better call saul") || title.contains("walking dead") ||
            title.contains("mad men") || combined.contains("amc") -> AMC

            // FX
            title.contains("bear") || title.contains("shogun") || title.contains("fargo") ||
            title.contains("always sunny") || combined.contains("fx on hulu") -> FX

            // Hulu
            title.contains("handmaid") || title.contains("only murders") || combined.contains("hulu") -> HULU

            // BBC
            title.contains("peaky blinders") || title.contains("doctor who") || title.contains("sherlock") ||
            combined.contains("bbc") -> BBC

            // Paramount+
            title.contains("yellowstone") || title.contains("star trek") || title.contains("tulsa king") ||
            combined.contains("paramount+") -> PARAMOUNT

            // Defaults based on type & hash for visual variety
            item.type.contains("TV", ignoreCase = true) -> {
                val fallbacks = listOf(APPLE, HBO, NETFLIX, AMAZON, DISNEY, FX)
                val idx = (item.title.hashCode() and 0x7FFFFFFF) % fallbacks.size
                fallbacks[idx]
            }
            else -> {
                NetworkInfo(
                    name = "Theatrical",
                    shortName = "FILM",
                    badgeColor = Color(0xFF1E293B),
                    textColor = Color(0xFF94A3B8)
                )
            }
        }
    }
}
