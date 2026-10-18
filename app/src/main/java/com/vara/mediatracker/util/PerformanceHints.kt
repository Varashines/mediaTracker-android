package com.vara.mediatracker.util

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import java.util.concurrent.TimeUnit

/**
 * Runs [block] under a performance hint, where the platform has the API for one.
 *
 * The work behind this is sustained — a sync, an enrichment pass over the library, a badge
 * recompute — rather than a burst. Nothing tells the scheduler that, so it treats the thread like
 * any other background work and clocks it down, and a pass that should take seconds takes longer. A
 * hint session states how long the work is expected to take; reporting the actual duration at the
 * end is the half that matters, because that is what the scheduler corrects itself with next time.
 *
 * The session names the calling thread. That is the thread driving the pass — where a worker fans
 * out to `Dispatchers.IO`, those children do not exist yet when the session is created, so they
 * cannot be enumerated. It is an approximation, and a harmless one: a hint that is too broad costs
 * the scheduler information, never correctness.
 *
 * API 31 — Android 12 — and up. Below that, or on a device without the service, [block] simply runs
 * unhinted, which is what it did before this existed.
 */
suspend fun <T> withPerformanceHint(
    context: Context,
    targetMillis: Long,
    block: suspend () -> T
): T {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return block()
    return runHinted(context, targetMillis, block)
}

@RequiresApi(Build.VERSION_CODES.S)
private suspend fun <T> runHinted(
    context: Context,
    targetMillis: Long,
    block: suspend () -> T
): T {
    val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return block()
    val session = runCatching {
        manager.createHintSession(
            intArrayOf(Process.myTid()),
            TimeUnit.MILLISECONDS.toNanos(targetMillis)
        )
    }.getOrNull() ?: return block()

    val started = System.nanoTime()
    return try {
        block()
    } finally {
        // Report before closing: this is the feedback the scheduler learns from.
        runCatching { session.reportActualWorkDuration(System.nanoTime() - started) }
        session.close()
    }
}
