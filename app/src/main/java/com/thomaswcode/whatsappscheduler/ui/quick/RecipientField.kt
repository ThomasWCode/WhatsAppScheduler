package com.thomaswcode.whatsappscheduler.ui.quick

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaswcode.whatsappscheduler.data.RecipientDraft
import com.thomaswcode.whatsappscheduler.data.contacts.ContactHit
import com.thomaswcode.whatsappscheduler.data.db.Recipient
import com.thomaswcode.whatsappscheduler.domain.TargetKind

/**
 * The To field (quick-schedule.md §3): chosen recipients as chips, a type-ahead over contacts and
 * saved recipients, and the recent recipients to tap. Long-press (or tap a chosen one) for details.
 */
@Composable
fun RecipientField(
    chosen: List<RecipientDraft>,
    query: String,
    recents: List<Recipient>,
    me: RecipientDraft?,
    contactHits: List<ContactHit>,
    savedHits: List<Recipient>,
    contactsAllowed: Boolean,
    /** False while the sheet is saving: the draft is frozen. */
    enabled: Boolean,
    searchFocus: FocusRequester,
    onQuery: (String) -> Unit,
    onSearchFocused: () -> Unit,
    onRemove: (RecipientDraft) -> Unit,
    onDetails: (RecipientDraft) -> Unit,
    onToggleRecent: (Recipient) -> Unit,
    onAddDraft: (RecipientDraft) -> Unit,
    onAddContact: (ContactHit) -> Unit,
    onAddSaved: (Recipient) -> Unit,
    onAddTyped: (String) -> Unit,
    onAllowContacts: () -> Unit,
    onPickContact: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("To", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 4.dp))
            chosen.forEach { recipient ->
                RecipientChip(
                    label = recipient.displayName,
                    kind = recipient.kind,
                    locked = recipient.isLocked,
                    selected = true,
                    onClick = { onDetails(recipient) },
                    onLongClick = { onDetails(recipient) },
                    onRemove = { onRemove(recipient) },
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(if (chosen.isEmpty()) "Search contacts or type a group…" else "Add another…") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank()) onAddTyped(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .focusRequester(searchFocus)
                .onFocusChanged { if (it.isFocused) onSearchFocused() },
        )
        if (query.isBlank()) {
            val available = availableRecents(recents, chosen, me)
            val meAvailable = me?.takeIf { m -> chosen.none { it.sameChatAs(m) } }
            if (available.isNotEmpty() || meAvailable != null) {
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    meAvailable?.let { m -> RecipientChip("Me", TargetKind.CONTACT, m.isLocked, selected = false, onClick = { onAddDraft(m) }) }
                    available.forEach { recent ->
                        RecipientChip(
                            label = recent.displayName,
                            kind = recent.kind,
                            locked = recent.isLocked,
                            selected = false,
                            onClick = { onToggleRecent(recent) },
                            onLongClick = { onDetails(recent.toDraft()) },
                        )
                    }
                }
            }
        } else {
            if (!contactsAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Allow contacts to search them here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onAllowContacts) { Text("Allow") }
                    TextButton(onClick = onPickContact) { Text("Pick from contacts") }
                }
            }
            contactHits.forEach { hit ->
                ListItem(
                    headlineContent = { Text(hit.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(hit.number + if (hit.onWhatsApp) " · on WhatsApp" else "") },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onAddContact(hit) }),
                )
            }
            savedHits.filter { saved -> contactHits.none { it.e164 != null && it.e164 == saved.phoneE164 } }.forEach { saved ->
                ListItem(
                    headlineContent = { Text(saved.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(saved.phoneE164 ?: if (saved.kind == TargetKind.GROUP) "Group or chat name" else "Found by name") },
                    leadingContent = { Icon(if (saved.kind == TargetKind.GROUP) Icons.Default.Group else Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onAddSaved(saved) }),
                )
            }
            // A typed number opens that number's chat; anything else is looked for by name.
            val number = QuickScheduleViewModel.looksLikeNumber(query.trim())
            ListItem(
                headlineContent = { Text(if (number) "Use ${query.trim()}" else "Use \"${query.trim()}\" as a group or chat name") },
                supportingContent = { Text(if (number) "Opens the chat with this number" else "Found by this exact name in WhatsApp's search") },
                leadingContent = { Icon(if (number) Icons.Default.Person else Icons.Default.Group, contentDescription = null) },
                modifier = Modifier.combinedClickable(role = Role.Button, onClick = { onAddTyped(query) }),
            )
        }
    }
}

/** A recipient as a chip: tap, long-press for details, and × to remove when chosen. At least 48 dp tall to touch. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipientChip(
    label: String,
    kind: TargetKind,
    locked: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Details", role = Role.Button),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (locked) Icon(Icons.Default.Lock, contentDescription = "Chat Lock", modifier = Modifier.size(16.dp).padding(end = 2.dp))
            else if (kind == TargetKind.GROUP) Icon(Icons.Default.Group, contentDescription = "Group", modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onRemove != null) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove $label",
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                        .combinedClickable(onClick = onRemove, role = Role.Button),
                )
            }
        }
    }
}
