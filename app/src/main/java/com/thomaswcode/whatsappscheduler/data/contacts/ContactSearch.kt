package com.thomaswcode.whatsappscheduler.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
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
        val onWhatsApp = whatsAppContactIds(hits.values.mapTo(mutableSetOf()) { it.contactId })
        // A stable sort: within each group the provider's own ranking is kept.
        hits.values.map { it.copy(onWhatsApp = it.contactId in onWhatsApp) }.sortedByDescending { it.onWhatsApp }.take(limit)
    }

    private fun whatsAppContactIds(ids: Set<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val found = mutableSetOf<Long>()
        runCatching {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.CONTACT_ID),
                "${RawContacts.ACCOUNT_TYPE} IN (?, ?) AND ${RawContacts.CONTACT_ID} IN (${ids.joinToString(",")})",
                arrayOf(WHATSAPP_ACCOUNT, WHATSAPP_BUSINESS_ACCOUNT),
                null,
            )?.use { c -> while (c.moveToNext()) found += c.getLong(0) }
        }
        return found
    }

    private companion object {
        const val WHATSAPP_ACCOUNT = "com.whatsapp"
        const val WHATSAPP_BUSINESS_ACCOUNT = "com.whatsapp.w4b"
    }
}
