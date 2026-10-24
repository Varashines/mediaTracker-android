package com.vara.mediatracker.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vara.mediatracker.EXTRA_OPEN_MEDIA
import com.vara.mediatracker.MainActivity
import com.vara.mediatracker.R
import com.vara.mediatracker.data.model.MediaItemEntity

/**
 * The one thing worth knowing without opening the app: what you are in the middle of.
 *
 * A dynamic launcher shortcut, because it is the cheapest surface that carries real data — no
 * widget host, no extra process, no permission, no refresh schedule. Long-pressing the icon shows
 * "Up next — Severance · S2 E4"; tapping it opens that title.
 *
 * It deliberately reuses Home's own derivation rather than querying the database again: the caller
 * already has `HomeData`, so there is no second definition of "what is up next" to drift.
 *
 * The static `up_next` shortcut in `shortcuts.xml` stays as the fallback for when the app has never
 * run, or nothing is in progress; a dynamic shortcut with the same id overrides it.
 */
object UpNextShortcut {

    private const val ID = "up_next"

    fun update(context: Context, item: MediaItemEntity?, subtitle: String?) {
        val app = context.applicationContext
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (item != null) putExtra(EXTRA_OPEN_MEDIA, item.id)
        }
        val short = item?.title ?: "Continue watching"
        val long = if (item != null) {
            "Up next · ${item.title}${subtitle?.let { " · $it" }.orEmpty()}"
        } else {
            "Continue watching"
        }
        val shortcut = ShortcutInfoCompat.Builder(app, ID)
            .setShortLabel(short.take(30))
            .setLongLabel(long.take(60))
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, listOf(shortcut)) }
    }
}
