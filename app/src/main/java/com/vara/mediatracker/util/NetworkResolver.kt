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
     * Resolves styling for a given network name or falls back to standard theme tokens.
     */
    fun resolveByName(networkName: String?, isTV: Boolean = true): NetworkInfo {
        if (networkName.isNullOrBlank()) {
            return if (isTV) {
                NetworkInfo("TV Series", "TV", Color(0xFF2E1065), Color(0xFFE9D5FF))
            } else {
                NetworkInfo("Theatrical", "FILM", Color(0xFF1E293B), Color(0xFF94A3B8))
            }
        }

        val lower = networkName.lowercase().trim()
        return when {
            lower.contains("apple") -> APPLE
            lower.contains("hbo") || lower.contains("max") -> HBO
            lower.contains("netflix") -> NETFLIX
            lower.contains("disney") || lower.contains("marvel") || lower.contains("lucasfilm") -> DISNEY
            lower.contains("prime") || lower.contains("amazon") -> AMAZON
            lower.contains("amc") -> AMC
            lower.contains("fx") -> FX
            lower.contains("hulu") -> HULU
            lower.contains("bbc") -> BBC
            lower.contains("paramount") -> PARAMOUNT
            lower.contains("peacock") -> PEACOCK
            lower.contains("showtime") -> SHOWTIME
            lower.contains("ena") -> NetworkInfo("ENA", "ENA", Color(0xFF0284C7), Color(0xFFE0F2FE))
            lower.contains("tvn") -> NetworkInfo("tvN", "tvN", Color(0xFFDC2626), Color(0xFFFEE2E2))
            lower.contains("jtbc") -> NetworkInfo("JTBC", "JTBC", Color(0xFF4338CA), Color(0xFFE0E7FF))
            lower.contains("kbs") -> NetworkInfo("KBS", "KBS", Color(0xFF1D4ED8), Color(0xFFDBEAFE))
            lower.contains("sbs") -> NetworkInfo("SBS", "SBS", Color(0xFF0369A1), Color(0xFFE0F2FE))
            lower.contains("mbc") -> NetworkInfo("MBC", "MBC", Color(0xFF047857), Color(0xFFD1FAE5))
            else -> {
                // Dynamic clean badge for any TMDB network/studio
                NetworkInfo(
                    name = networkName,
                    shortName = networkName.take(5).uppercase(),
                    badgeColor = Color(0xFF332038),
                    textColor = Color(0xFFFFD7F5)
                )
            }
        }
    }

    /**
     * Resolves the network name and styling for a media item.
     * Uses title keywords, overview keywords, and item type heuristics.
     */
    fun resolve(item: MediaItemEntity): NetworkInfo {
        val title = item.title.lowercase()
        val overview = item.overview.lowercase()
        val combined = "$title $overview"

        return when {
            // Explicit network mentions in title/overview
            combined.contains("apple tv") || combined.contains("apple original") -> APPLE
            combined.contains("hbo") || combined.contains("max original") -> HBO
            combined.contains("netflix") -> NETFLIX
            combined.contains("disney+") || combined.contains("marvel studios") || combined.contains("lucasfilm") -> DISNEY
            combined.contains("amazon prime") || combined.contains("prime video") -> AMAZON
            combined.contains("amc") -> AMC
            combined.contains("fx on hulu") || combined.contains(" fx ") -> FX
            combined.contains("hulu original") -> HULU
            combined.contains("bbc") -> BBC
            combined.contains("paramount+") -> PARAMOUNT

            // Signature titles
            title.contains("severance") || title.contains("ted lasso") || title.contains("morning show") ||
            title.contains("slow horses") || title.contains("silo") || title.contains("foundation") ||
            title.contains("black bird") || title.contains("bad sisters") || title.contains("for all mankind") -> APPLE

            title.contains("last of us") || title.contains("succession") || title.contains("white lotus") ||
            title.contains("house of the dragon") || title.contains("game of thrones") || title.contains("euphoria") ||
            title.contains("true detective") || title.contains("sopranos") || title.contains("wire") ||
            title.contains("dune: prophecy") -> HBO

            title.contains("stranger things") || title.contains("squid game") || title.contains("wednesday") ||
            title.contains("witcher") || title.contains("bridgerton") || title.contains("crown") ||
            title.contains("black mirror") || title.contains("arcane") || title.contains("ozark") ||
            title.contains("narcos") -> NETFLIX

            title.contains("mandalorian") || title.contains("andor") || title.contains("loki") ||
            title.contains("wandavision") || title.contains("ahsoka") || title.contains("falcon") -> DISNEY

            title.contains("boys") || title.contains("rings of power") || title.contains("reacher") ||
            title.contains("invincible") || title.contains("fallout") || title.contains("wheel of time") ||
            title.contains("fleabag") -> AMAZON

            title.contains("breaking bad") || title.contains("better call saul") || title.contains("walking dead") ||
            title.contains("mad men") -> AMC

            title.contains("bear") || title.contains("shogun") || title.contains("fargo") ||
            title.contains("always sunny") -> FX

            title.contains("handmaid") || title.contains("only murders") -> HULU

            title.contains("peaky blinders") || title.contains("doctor who") || title.contains("sherlock") -> BBC

            title.contains("yellowstone") || title.contains("star trek") || title.contains("tulsa king") -> PARAMOUNT

            // Korean broadcasting clues
            combined.contains("ena") -> resolveByName("ENA", isTV = true)
            combined.contains("tvn") -> resolveByName("tvN", isTV = true)
            combined.contains("jtbc") -> resolveByName("JTBC", isTV = true)

            // Safe fallback without guessing wrong networks
            item.type.contains("TV", ignoreCase = true) -> {
                NetworkInfo(
                    name = "TV Series",
                    shortName = "TV",
                    badgeColor = Color(0xFF2E1065),
                    textColor = Color(0xFFE9D5FF)
                )
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
