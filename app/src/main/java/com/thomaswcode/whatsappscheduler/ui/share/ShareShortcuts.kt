package com.thomaswcode.whatsappscheduler.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.ui.MainActivity
import com.thomaswcode.whatsappscheduler.ui.schedules.initials

/**
 * Direct Share (features F6): the most recent recipients as sharing shortcuts, so Android's share
 * sheet offers "Mum" for "Schedule on WhatsApp" directly, and the quick sheet opens with both the
 * chat and the shared text filled in, one step fewer than the plain share target.
 */
class ShareShortcuts(private val context: Context) {

    /** Makes [recents] (most recent first) the shortcuts, as many as the launcher takes; any others go. */
    fun publish(recents: List<Recipient>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, MAX)
            // Ranked by recency, so a push that has to make room (see [used]) drops the least recent.
            val shortcuts = recents.take(max).mapIndexed { rank, recipient -> shortcutFor(recipient, rank) }
            val kept = shortcuts.map { it.id }.toSet()
            // Long-lived shortcuts stay in the share sheet's cache until removed, also once they are no
            // longer dynamic, as after a push made room for another: so the cached ones count too.
            val gone = published().filter { it !in kept }
            if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone)
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    /**
     * A message was just scheduled to [recipients]: each one's shortcut is reported as used, which is
     * what the share sheet ranks its Direct Share targets by, so the chats used most are offered first.
     * Pushed rather than only reported, so a chat new to the recents has its shortcut already.
     */
    fun used(recipients: List<Recipient>) {
        runCatching {
            recipients.forEach { recipient ->
                val before = dynamic()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcutFor(recipient, rank = 0))
                // At the launcher's limit the push drops another shortcut, which as a long-lived one would
                // stay in the share sheet's cache: it goes from there too. The recents' own publish puts
                // it back if it still belongs.
                val dropped = before - dynamic()
                if (dropped.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, dropped.toList())
            }
        }
    }

    private fun dynamic(): Set<String> = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.toSet()

    /** This app's shortcuts that are dynamic, or cached as long-lived ones for the share sheet. */
    private fun published(): List<String> = ShortcutManagerCompat
        .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
        .map { it.id }

    private fun shortcutFor(recipient: Recipient, rank: Int): ShortcutInfoCompat {
        val id = idFor(recipient.id)
        return ShortcutInfoCompat.Builder(context, id)
            .setRank(rank)
            .setShortLabel(recipient.displayName)
            .setLongLabel("${recipient.displayName} · Schedule on WhatsApp")
            .setIcon(IconCompat.createWithAdaptiveBitmap(avatar(recipient.displayName)))
            // Picked from the launcher's long-press menu rather than the share sheet, it opens the app.
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .setPerson(Person.Builder().setName(recipient.displayName).setKey(id).build())
            .build()
    }

    /** The recipient's initials on a colour of its own, drawn for an adaptive icon's safe zone. */
    private fun avatar(name: String): Bitmap {
        val size = (ADAPTIVE_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PALETTE[(name.hashCode() and Int.MAX_VALUE) % PALETTE.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.3f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(initials(name), size / 2f, y, paint)
        return bitmap
    }

    companion object {
        /** Matches the share target declared in res/xml/shortcuts.xml. */
        const val CATEGORY = "com.thomaswcode.whatsappscheduler.category.SCHEDULE_TO"
        private const val PREFIX = "recipient-"
        private const val MAX = 4
        private const val ADAPTIVE_DP = 108

        /** Teal, indigo, amber, rose and green: white initials read on each. */
        private val PALETTE = intArrayOf(0xFF00796B.toInt(), 0xFF3949AB.toInt(), 0xFFB26A00.toInt(), 0xFFC2185B.toInt(), 0xFF2E7D32.toInt())

        fun idFor(recipientId: Long): String = PREFIX + recipientId

        /** The recipient a Direct Share shortcut stands for, from the id the share sheet hands over; null for any other. */
        fun recipientId(shortcutId: String?): Long? =
            shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()?.takeIf { it > 0 }
    }
}
