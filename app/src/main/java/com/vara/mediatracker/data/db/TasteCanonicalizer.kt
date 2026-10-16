package com.vara.mediatracker.data.db

import android.util.Log
import com.vara.mediatracker.data.model.Taste

/**
 * One-shot rewrite of legacy taste spellings to canonical values.
 *
 * Android used to store and push "Loved"/"Liked"/"Disliked" while the Mac's `TasteValue` enum
 * only knows "Love"/"Like"/"Dislike", so those titles arrived on the Mac as unparseable strings
 * and were ignored by every taste-driven feature. New writes, pushes and applied pulls are
 * normalised at the boundary; this heals what is already on the device (and, because the next
 * push re-sends the row, in the cloud too).
 *
 * Idempotent: with no legacy rows it is a handful of no-op updates.
 */
class TasteCanonicalizer(private val db: AppDatabase) {

    suspend fun canonicalize() {
        for (legacy in Taste.LEGACY_VALUES) {
            val canonical = Taste.normalize(legacy)
            if (canonical == legacy) continue
            db.mediaDao().replaceTaste(legacy, canonical)
        }
        Log.i("TasteCanonicalizer", "Legacy taste values rewritten to canonical form")
    }
}
