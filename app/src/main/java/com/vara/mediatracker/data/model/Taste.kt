package com.vara.mediatracker.data.model

/**
 * The canonical taste vocabulary, matching macOS's `TasteValue` enum and the `taste` column
 * default on `user_media`.
 *
 * Android used to *write* the participle spellings — "Loved"/"Liked"/"Disliked" — while every
 * reader accepted both forms. That asymmetry only showed up on the Mac: its enum knows the
 * three forms, so a title liked or loved here landed there as an unparseable string and the
 * Mac then ignored the taste in every feature that reads one (badge gating, the Loved
 * filters, affinity, insights). Four rows had already been poisoned in the cloud.
 *
 * Values are normalised at all three boundaries — the write, the push, and the applied pull —
 * and existing rows are healed once at start-up via [com.vara.mediatracker.data.db.TasteCanonicalizer],
 * so the raw string comparisons elsewhere stay valid.
 */
object Taste {
    const val NONE = "None"
    const val LIKE = "Like"
    const val LOVE = "Love"
    const val DISLIKE = "Dislike"

    /** Maps any historical spelling onto a canonical value; unknown input reads as [NONE]. */
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "love", "loved" -> LOVE
        "like", "liked" -> LIKE
        "dislike", "disliked" -> DISLIKE
        else -> NONE
    }

    fun isLove(raw: String?): Boolean = normalize(raw) == LOVE

    fun isLike(raw: String?): Boolean = normalize(raw) == LIKE

    fun isDislike(raw: String?): Boolean = normalize(raw) == DISLIKE

    fun isLikedOrLoved(raw: String?): Boolean {
        val value = normalize(raw)
        return value == LOVE || value == LIKE
    }

    /** True when a stored value is not already canonical — the signal the one-off heal looks for. */
    fun isLegacy(raw: String?): Boolean = raw != null && raw != normalize(raw)

    /** Every non-canonical spelling seen in the wild, for the heal to rewrite. */
    val LEGACY_VALUES = listOf("Loved", "Liked", "Disliked")
}
