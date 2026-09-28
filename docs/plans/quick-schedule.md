# Quick one-off scheduling (headline plan)

The most-used feature is a single message at a future time. Today it goes through the same nine-part form as recurring rules: kind, name, phone, lock toggle, message, a date dialog, a time dialog, six repeat chips, Save. This plan makes it the default path and makes it fast.

Decisions behind this plan are in the [decisions log](README.md#decisions-log). Bug references (Bxx) point to [bugs.md](bugs.md).

## 1. Goal

**A recent recipient in under 10 seconds:** tap **+**, tap **Mum**, type the message, tap **Tomorrow 09:00**, tap **Schedule**. That's four taps plus typing, with no dialogs.

What the design must get right:
- The quick path never shows recurrence, Chat Lock, phone formats or timeouts.
- Everything learned once is remembered: who a recipient is, their number, whether their chat is locked.
- Nothing about the send engine changes. The sheet still produces an ordinary `Schedule` with `Recurrence.Once`.

## 2. Entry points

| Entry | Behaviour |
|---|---|
| **+ on the main list** | An extended FAB labelled "New message" opens the **quick sheet**, a `ModalBottomSheet` over the list. |
| **Share from any app** | A new exported `ShareTargetActivity` with an `ACTION_SEND` / `text/plain` intent filter, labelled "Schedule on WhatsApp". It shows the same quick sheet over the sharing app with the message pre-filled from `EXTRA_TEXT` (plus `EXTRA_SUBJECT` if present), and focus goes to the recipient. After Schedule it shows a short toast ("Scheduled for Tomorrow 09:00") and finishes, returning you to the app you shared from. Sharing from WhatsApp (long-press a message → Share) works too. |

Considered but not chosen: launcher shortcut, Quick Settings tile, home-screen widget. See [features.md → considered](features.md#considered-and-not-planned). A launcher shortcut would be about 20 lines on top of this work if you want it later.

## 3. Recipients

A new `recipients` table (see [§11](#11-data-model-changes-schema-v2)) holds everyone you've scheduled to. Each recipient stores a display name, the name as shown in WhatsApp (for the search path), a phone number, kind, a Chat Lock flag, last-used time and use count. Chat Lock moves off the message form and onto the recipient, so it's set once.

### Picking

```
To   [ Mum × ] [ Search contacts or type a group… ]
     Recent:  (Dad) (Work crew) (Sam) (Me) (Grandma) …
```

- **Recent chips:** the top 8 by `lastUsedAtMillis`. Tapping one toggles it in or out of the "To" field. More than one can be selected ([§10](#10-multiple-recipients)).
- **Type-ahead search** needs **READ_CONTACTS**, which is requested the first time the search field gets focus. There is still no network permission, so nothing leaves the phone.
  - **Results:** queried from `ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI`, one row per number: name, number, and a small WhatsApp badge when the contact has a WhatsApp raw contact (`RawContacts.ACCOUNT_TYPE = "com.whatsapp"`, *verify on device*). Rows with the badge sort first.
  - **Saved recipients** (groups included) that match the query also appear.
  - **Last row:** *Use "Work crew" as a group or chat name*. It creates a `GROUP` recipient found through WhatsApp search.
  - **If permission is denied:** the field shows "Allow contacts to search" and a **Pick from contacts** button, which uses the existing system picker (`ACTION_PICK`, no permission needed).
- **Numbers** go through `PhoneNormaliser` (B06). Invalid numbers are rejected on the spot.
- **Long-press a chip** (or ⋯ on a result) to open **recipient details**:
  - name as shown in WhatsApp;
  - number;
  - **Behind Chat Lock** toggle;
  - **Check this chat now** ([features F4](features.md#f4));
  - remove from recents.

  Editing details updates any pending schedules for that recipient; see the note in §11.
- **"Me" chip (optional):** if you enter your own number under Settings → Sending, a pinned *Me* chip gives you a one-tap test target.

## 4. Time

A pure function, `QuickTimes.suggest(now: ZonedDateTime, morning = 09:00, evening = 20:00)`, generates the chips. It is unit-tested at awkward moments: 23:50, 00:30, Friday evening, and across DST.

| Chip | When shown | Resolves to |
|---|---|---|
| In 30 min / In 1 h / In 3 h | Always | now + offset, rounded **up** to the next whole minute |
| This morning 09:00 | Before 05:00 | today at *morning* (so at 01:30, "tomorrow morning" isn't ambiguous) |
| Tonight 20:00 | When at least 30 min before *evening* | today at *evening* |
| Tomorrow 09:00 | Always | tomorrow at *morning* |
| Mon 09:00 | Fridays and Saturdays (when Monday isn't already "tomorrow") | next Monday at *morning* |
| Pick date & time… | Always | a date picker followed by a time picker |

- The *morning* and *evening* times can be changed under Settings → Sending.
- Chips sit in a `FlowRow`: relative offsets first, then the named slots. There are never more than 7.
- The selected chip is highlighted, with the resolved moment beneath it: **"Tue 29 Sep · 09:00 · in 18 h 52 min"**.
- **The custom picker** disables past dates (`selectableDates`) and follows the system 12/24-hour setting (`DateFormat.is24HourFormat`), replacing the hard-coded `is24Hour = true`. It opens at the selected chip's time, or now + 1 h if none is selected.
- **Validation:** the time must be at least 1 minute ahead. Under 2 minutes, a hint says "Sends in under 2 minutes".

## 5. The quick sheet

```
┌─────────────────────────────────────────────┐
│ ▔▔▔                                         │
│ To   [ Mum × ] [ Search contacts…         ] │
│      (Dad) (Work crew) (Sam) (Me)           │
│ ┌─────────────────────────────────────────┐ │
│ │ Message                                 │ │
│ │                                         │ │
│ └─────────────────────────────────────────┘ │
│ When (In 30 min) (In 1 h) (In 3 h)          │
│      (Tonight 20:00) [Tomorrow 09:00]       │
│      (Pick date & time…)                    │
│      Tue 29 Sep · 09:00 · in 18 h 52 min    │
│                                             │
│ Repeat…                                     │
│ [        Schedule · Tomorrow 09:00        ] │
└─────────────────────────────────────────────┘
```

**Opening**
- Opened from **+**, focus lands on the search field and the recent chips are visible. Picking a recipient moves focus to the message field, with the keyboard up.
- Opened from **Share**, the message is already filled in, so focus lands on the recipient.

**Scheduling**
- The **Schedule** button shows the resolved time. It stays disabled until there is a recipient, a message and a valid time. It is also disabled while saving, so a double tap can't create two schedules (B05).
- After scheduling from the list, the sheet closes and a snackbar says **"Scheduled: Mum · Tomorrow 09:00 · Undo"**.

**Warnings inside the sheet**
- **Health:** if auto-send isn't ready (service off or not running, no PIN, PIN rejected; see [features F5](features.md#f5)), one inline warning row appears, e.g. "Auto-send is off: the accessibility service isn't running · Fix". You can still schedule.
- **Dry run:** if dry-run mode is on, a warning row says so (B11).

**Keeping what you typed**
- The draft survives rotation and process death, using a ViewModel with `SavedStateHandle` (B17).
- Swiping the sheet away keeps the draft, and the next **+** restores it. The **Discard** text button clears it.
- The sheet uses `imePadding()` so the keyboard never covers Schedule (B16).

**Editing**
- Tapping an upcoming one-off on the list opens the same sheet in edit mode. The title reads "Edit message", the button reads **Save**, and there's a **Delete** action.

## 6. Repeat… and the full editor

- **Repeat…** is a text button. It opens the existing full editor, cleaned up per [improvements I7](improvements.md#i7), pre-filled with the sheet's recipient, message and time.
- Recurring rules are **unchanged**: Daily, Weekly, Monthly, Every N hours, Every N days.
- Recurring schedules open the full editor when tapped. One-offs never do.

## 7. Main list

```
┌ WhatsApp Scheduler                      ⚙ ┐
│ ● Auto-send ready                          │  ← or red: "Accessibility service is off · Fix"
│                                            │
│ NEEDS ATTENTION                            │
│ ⚠ Mum · Missed Tue 09:00                   │
│   "Happy birthday! 🎉"                      │
│   [Send now] [Postpone…] [Discard]         │
│                                            │
│ UPCOMING                                   │
│ Dad · Today 20:00 · in 3 h              ⋮  │
│   "Don't forget the bins"                  │
│ Mum, Sam +1 · Tomorrow 09:00            ⋮  │
│   "Lunch on Sunday?"                       │
│                                            │
│ REPEATING                                  │
│ Work crew · Every Mon 09:00        [on] ⋮  │
│                                            │
│ SENT (4) ▸                                 │
│                          [ ✎ New message ] │
└────────────────────────────────────────────┘
```

**Health strip**
- It replaces the setup banner. Green means ready. Red names the first blocking problem and links to the fix.
- It re-evaluates on resume (B24) and checks that the service is actually bound, not just enabled ([features F5](features.md#f5)).

**Sections**
| Section | Contents |
|---|---|
| **Needs attention** | Anything waiting on you: missed (B09), failed after retries, *may have been sent* (B02), or held because you dismissed the Postpone picker ([features F1](features.md#f1)). Each row has inline actions. |
| **Upcoming** | One-offs, soonest first. |
| **Repeating** | Recurring schedules, each with a Pause switch. Only recurring rows get a switch (R4, B29). |
| **Sent** | Collapsed by default ([§8](#8-sent-section-and-auto-clear)). |

A section is hidden when it's empty.

**Rows**
- **Contents:** an initials avatar, the recipient(s), a friendly time ("Today 20:00 · in 3 h", "Tomorrow 09:00", "Sat 3 Oct 18:30"), and two lines of the message.
- **Tap:** a one-off opens the quick sheet in edit mode; a recurring schedule opens the full editor.
- **⋯ menu:** **Postpone…**, **Send now…** (with the confirmation from B07), **Test (dry run)**, **Delete**.
- **Swipe to delete**, with **Undo** in a snackbar instead of a dialog. A delete you make yourself also removes that schedule's send history, since you'd expect it gone.
- **In flight:** a row being sent shows "Sending…" with a small progress indicator and can't be edited (B08). The service exposes its current job as a `StateFlow` ([improvements T4](improvements.md#t4)).

**Top bar**
- Only ⚙ Settings stays. Logs moves to Settings → Diagnostics ([improvements R7](improvements.md#r7)), because the Needs attention section now surfaces failures where you'll see them.

## 8. Sent section and auto-clear

- A one-off moves to **Sent** once it has been sent.
  - A dry run counts too, marked *(dry run)*.
  - Failed and uncertain sends go to Needs attention instead.
- **Sent rows:** they show "Sent Tue 09:00". The main action is **Send again…**, which opens the quick sheet with the recipient and message filled in and no time selected.
- **Auto-clear:** sent one-offs are deleted **30 days** after sending. The period can be changed under Settings → Sending ("Keep sent messages for").
- **History survives auto-clear.** The `send_log` foreign key changes from `CASCADE` to `SET_NULL`, so the Logs → Sends history outlives the schedule row. History itself is pruned after 90 days (B28).
- **Housekeeping** runs on app start and after each send finishes. No WorkManager is needed.

## 9. Postpone

**Decision:** *Postpone…* opens a **date and time picker set to the current send time**. There are no fixed "+1 h" amounts on messages. (The heads-up notification also gets a quick *Snooze 5 min*; see [features F1](features.md#f1).)

**Where it's offered:** the row's ⋯ menu, the Needs attention actions, and the heads-up notification. The notification opens a small translucent `PostponeActivity` hosting the same picker, so it works on top of whatever app you're in.

**Picking a time**
- Past times are disabled.
- On confirm, a snackbar says "Moved to Wed 18:30 · Undo".

**One-off:** `firstAtMillis` and `nextAtMillis` move to the chosen time. `postponedFromMillis` keeps the original time, so the row can show "(moved from 09:00)".

**Recurring:** only **this occurrence** moves. The rule is untouched.
- `recordRun` already computes the next occurrence after `max(ranAt, nextAtMillis)`, so the series resumes normally after the postponed send.
- Postponing past the series' next occurrence absorbs that occurrence. The picker says so: "This also skips Wed 09:00".

**Implementation**
- Add a dedicated `ScheduleRepository.postpone(id, at)`. It must not go through `save()`, which would recompute the occurrence from the rule.
- It clears any `attention` flag and re-arms the alarm with the new occurrence key (B01).

## 10. Multiple recipients

- The "To" field holds several chips. Selecting three recipients and tapping Schedule creates **three ordinary schedules** sharing a `batchId`.
  - This keeps the send engine, retries and history per recipient.
  - A failure for one person doesn't block the others.
- **In the list**, a batch shows as one row ("Mum, Dad +1"). Tapping it opens the sheet with every recipient.
- **Editing a batch:** changes to the message or time apply to every member. Removing a chip deletes that member. Adding a chip creates a new member.
- **Sending:** members share one occurrence time and go through the queue one after another, 15–40 s each.
  - The heads-up countdown ([features F1](features.md#f1)) appears once for the batch: "Sending to Mum, Dad and Sam in 10 s".
  - The Sent notification is grouped ([features F2](features.md#f2)).
  - Any member that fails appears on its own under Needs attention.
- **Limit:** 10 recipients per batch, to stop one mistake from turning into a flood.

## 11. Data model changes (schema v2)

This is a single migration, 1 → 2, landing in phase 1 before any of the UI above.

| Table | Change | Why |
|---|---|---|
| `recipients` (new) | `id`, `kind`, `displayName`, `whatsappName?`, `phoneE164?`, `isLocked`, `lastUsedAtMillis`, `useCount`, `verifiedAtMillis?` | Recents, type-ahead, Chat Lock set once, F4 |
| `schedules` | + `recipientId?` (FK → recipients, `SET_NULL`) | Recents and editing a recipient |
| `schedules` | + `batchId?` (indexed) | §10 |
| `schedules` | + `postponedFromMillis?` | §9 |
| `schedules` | + `attention?` (`MISSED`, `FAILED`, `UNCERTAIN`, `HELD`) | Needs attention; replaces parsing `lastResult` strings |
| `schedules` | + `lastStatus?` (the `SendStatus` enum) | Sent / Needs attention sections without string parsing |
| `schedules` | + `sourceScheduleId?` | A missed recurring occurrence held as a one-off copy (B09) |
| `send_log` | `scheduleId` becomes nullable, FK `CASCADE` → `SET_NULL`; + `occurrenceAtMillis?` | History outlives auto-clear (§8); B01 idempotency |
| `SendStatus` enum | + `UNCERTAIN`, `MISSED` (keep `SKIPPED` for old rows) | B02, B09 |

- **Target columns stay:** `targetName`, `phoneE164` and `isLocked` remain on `schedules` as the snapshot that is actually sent. Editing a recipient's details runs one `UPDATE` over that recipient's *pending* schedules. Sent history keeps what was actually used.
- **Migration:**
  - Use Room `AutoMigration` for the added table and columns.
  - Write a manual migration for the `send_log` foreign-key change, which needs a table rebuild in SQLite, if `AutoMigration` can't express it.
  - Test it with `MigrationTestHelper` against the existing [schema v1 JSON](../../app/schemas/com.thomaswcode.whatsappscheduler.data.db.AppDatabase/1.json).
  - Per the decisions log, losing data on an update would be acceptable, so `fallbackToDestructiveMigration()` is an allowed shortcut. A real migration is still recommended, because v2 ships while messages are pending, and a *missing* migration crashes the app on every launch.

## 12. Files

**New**
- `ui/quick/QuickScheduleSheet.kt`, `ui/quick/QuickScheduleViewModel.kt`
- `ui/quick/RecipientField.kt`, `ui/quick/RecipientDetailsSheet.kt`
- `ui/quick/TimeChips.kt`, `domain/QuickTimes.kt` (pure)
- `domain/PhoneNormaliser.kt` (pure, B06)
- `data/db/Recipient.kt` + `RecipientDao`, `data/RecipientRepository.kt`, `data/contacts/ContactSearch.kt`
- `ui/share/ShareTargetActivity.kt`, `ui/postpone/PostponeActivity.kt` + a shared `PostponePicker` composable
- `data/Housekeeping.kt` (auto-clear and log pruning)

**Changed**
- `ScheduleListScreen.kt`: sections, health strip, swipe, in-flight state
- `ScheduleEditorScreen.kt`: becomes the recurring editor only
- `ScheduleRepository.kt`: `postpone`, targeted run updates (B08), batch create/update
- `Entities.kt`, `AppDatabase.kt`: v2
- `AndroidManifest.xml`: share target, `READ_CONTACTS`, new activities
- `SettingsStore.kt`: morning/evening times, keep-sent days, own number

## 13. Tests

**Unit**
- `QuickTimes`: every row of the §4 table, midnight, Friday/Saturday, a DST day.
- `PhoneNormaliser`: `07…`, `+44…`, `0044…`, spaces and dashes, invalid input.
- Recent-recipient ranking.

**Robolectric**
- Batch create and edit.
- `postpone` semantics for one-off and recurring, including absorbing the next occurrence.
- Housekeeping keeps `send_log` rows after auto-clear.
- The v1 → v2 migration.

**Device** (add to [TESTING.md](../TESTING.md))
- Share from WhatsApp and from Chrome.
- Type-ahead with the contacts permission denied, then granted.
- A 3-recipient batch.
- Postpone from a row and from the notification.
- Sent auto-clear with the clock moved forward.
