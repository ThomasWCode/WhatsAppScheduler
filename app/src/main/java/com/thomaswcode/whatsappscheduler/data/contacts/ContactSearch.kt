package com.thomaswcode.whatsappscheduler.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import com.thomaswcode.whatsappscheduler.domain.PhoneNormaliser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One phone number from the phone's contacts, offered in the quick sheet's type-ahead. */
data class ContactHit(
    val contactId: Long,
    val name: String,
    val number: String,
    /** The number in E.164, or null when it doesn't parse (shown, but rejected if picked). */
    val e164: String?,
    /** WhatsApp has synced this contact (it has a WhatsApp raw contact), so the chat is likely to exist. */
    val onWhatsApp: Boolean,
)

/**
 * Type-ahead over the phone's contacts (quick-schedule.md §3). Needs READ_CONTACTS, which the sheet
 * asks for the first time the search field is used; the query stays on the phone.
 */
class ContactSearch(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** One row per number, contacts WhatsApp knows first; the same number twice is shown once. */
    suspend fun search(query: String, countryIso: String, limit: Int = 20): List<ContactHit> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty() || !hasPermission()) return@withContext emptyList()
        val hits = LinkedHashMap<String, ContactHit>()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(text))
        runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext() && hits.size < limit * 2) {
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    val e164 = PhoneNormaliser.toE164(number, countryIso)
                    hits.putIfAbsent(e164 ?: number.filter { it.isDigit() || it == '+' }, ContactHit(c.getLong(0), name, number, e164, onWhatsApp = false))
                }
            }
        }
        val onWhatsApp = whatsAppNumbers(hits.values.mapTo(mutableSetOf()) { it.contactId })
        // A stable sort: within each group the provider's own ranking is kept.
        hits.values.map { it.copy(onWhatsApp = it.e164 != null && it.e164 in onWhatsApp) }.sortedByDescending { it.onWhatsApp }.take(limit)
    }

    /**
     * The numbers WhatsApp itself lists for these contacts, in E.164. Its raw contacts carry data rows
     * whose DATA1 is the chat id, "447400123456@s.whatsapp.net". Per number, not per contact: someone
     * with two numbers may be on WhatsApp with only one of them.
     */
    private fun whatsAppNumbers(contactIds: Set<Long>): Set<String> {
        if (contactIds.isEmpty()) return emptySet()
        val rawIds = mutableListOf<Long>()
        val numbers = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts._ID),
                "${RawContacts.ACCOUNT_TYPE} IN (?, ?) AND ${RawContacts.CONTACT_ID} IN (${contactIds.joinToString(",")})",
                arrayOf(WHATSAPP_ACCOUNT, WHATSAPP_BUSINESS_ACCOUNT),
                null,
            )?.use { c -> while (c.moveToNext()) rawIds += c.getLong(0) }
            if (rawIds.isEmpty()) return@runCatching
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(Data.DATA1),
                "${Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",")}) AND ${Data.MIMETYPE} LIKE ?",
                arrayOf("$WHATSAPP_MIMETYPE_PREFIX%"),
                null,
            )?.use { c -> while (c.moveToNext()) whatsAppChatNumber(c.getString(0))?.let(numbers::add) }
        }
        return numbers
    }

    companion object {
        private const val WHATSAPP_ACCOUNT = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_ACCOUNT = "com.whatsapp.w4b"
        private const val WHATSAPP_MIMETYPE_PREFIX = "vnd.android.cursor.item/vnd.com.whatsapp"

        /** "447400123456@s.whatsapp.net" gives "+447400123456"; a group or anything else, null. */
        fun whatsAppChatNumber(chatId: String?): String? {
            val digits = chatId?.takeIf { it.endsWith("@s.whatsapp.net") }?.substringBefore('@') ?: return null
            return digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "+$it" }
        }
    }
}
