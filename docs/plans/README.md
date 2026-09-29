# Polish plan

A review of the whole app at commit `de71f79` on 2026-09-28, with decisions agreed in Q&A. The item descriptions (including "nothing here has been fixed yet") describe the code as it was at that commit; progress is tracked in the **Status** column below.

| File | What's in it |
|---|---|
| [bugs.md](bugs.md) | 29 defects, ranked Critical → Low, each with location, scenario, fix plan and test |
| [quick-schedule.md](quick-schedule.md) | **Headline:** the fast one-off flow: quick sheet, Share target, recipients, time chips, list redesign, postpone, Sent section, multiple recipients, schema v2 |
| [features.md](features.md) | Send-time features: countdown heads-up, Sent notification, attention notifications, chat check, health/pre-flight. Also the **considered-and-not-planned** list |
| [improvements.md](improvements.md) | Polish (I), removals (R), tech debt (T), tests (Q) |

## Order of work

The phases are ordered so that the safety fixes land first, and the schema changes once before the UI that needs them.

| Phase | Scope | Why this order | Status |
|---|---|---|---|
| **0 · Safety** | B01–B08, B10, B12, B14; T3 (extract `SendQueueProcessor`) with its tests. Interim for B09: notify on skip. | Duplicate sends and repeated wrong-PIN entries are the only issues that can cause real-world harm. None of these needs a schema change; new `SendStatus` values are stored as text. | Done (#3), plus B21, B22, B26, I10 and the T5 lenient converter. On the phone: #11 and #15 passed, and #13 as a dry run; #12 (wrong PIN) needs you |
| **1 · Groundwork** | T7 toolchain (optional; do it first if at all); R1 minSdk decision; T1 schema v2 + migration test; T2 ViewModels; T4 send state; T5 typed status; B17 | Everything after this depends on the v2 tables and on ViewModels. | Done (T7 in #4; T4 came with phase 0), plus B15 and B23. R1 decided: minSdk 34 (phase 5g) |
| **2 · Quick sheet** | [quick-schedule.md](quick-schedule.md) §2–6 and §10: the sheet, Share, recipients and type-ahead, time chips, Repeat…, multiple recipients; B16, B22, B23 | The headline feature. | Done (#6), plus B25 (status bar in dark mode). Device-checked: type-ahead, chips, Undo, Share, edit mode, batches, Repeat… |
| **3 · List** | §7–9: health strip, sections, swipe, in-flight rows, Postpone, Sent and auto-clear; F4; F5 live status; B24, B28, B29 | Builds on the phase 1 status columns. | In progress. 3a (#7): health strip with B24 and F5 live status, sections, swipe with Undo, in-flight rows, B29/R4, R7. 3b (#8): Postpone, Sent auto-clear, B28. 3c (#9): F4 Check this chat now |
| **4 · Send time** | F1 heads-up, F2 Sent notification, F3 attention notifications, F5 pre-flight; B09 (20-min grace, then ask), B11 | Needs the held/attention state and the Postpone picker. | In progress. 4a (#10): F2 Sent notification, B11 and I6 test mode. 4b (#11): F3 attention notifications with their actions. 4c (#12): B09 (20-minute grace, then held and asked) and the F5 pre-flight check. 4d: F1 countdown heads-up, and back to the app the user was in after a send |
| **5 · Polish** | The remaining I, R and B items (B13, B15, B18–B21, B25–B27); F6 optional; docs updates (I13) | Low risk, and can be done in any order. | In progress. 5a (#14): B18/R3, R2, B19, B27. 5b: I4/R6 Settings reorganised, B13 |

## Decisions log

Agreed on 2026-09-28. Later plans should build on these rather than re-open them.

| Topic | Decision |
|---|---|
| Entry points for a one-off | **In-app quick sheet** (+ button) and **Share from any app**. Not a launcher shortcut, QS tile or widget. |
| Choosing recipients | **Both** recent-recipient chips **and** type-ahead contact search (adds `READ_CONTACTS`; still no network permission). |
| Time options | **Named slots** (Tonight 20:00, Tomorrow 09:00, Mon 09:00) and **relative offsets** (In 30 min / 1 h / 3 h), plus a custom picker. No natural-language entry. |
| Recurring schedules | Used rarely: **kept but tucked behind "Repeat…"**. The quick sheet is one-off only. |
| WhatsApp native scheduling | **Not available** in your WhatsApp yet. Keep the current approach; revisit if it arrives ([features.md](features.md#considered-and-not-planned)). |
| Phone in use when a send is due | **Countdown heads-up.** The notification has **both** *Snooze 5 min* and *Postpone…* (picker). Dismissing the picker means nothing is sent until a time is chosen. |
| Postpone on messages | Opens a **date & time picker set to the current send time**. No fixed amounts. |
| After a successful send | A **quiet "Sent" notification** with Open chat. |
| Sent one-offs | A **collapsible Sent section**, auto-cleared after 30 days. History is kept in Logs. |
| Extra features | **Postpone** and **multiple recipients**. Not templates. |
| Backup / export | **None.** Every case where data is lost is one where you'd expect or want deletion: uninstall, Clear storage, new phone or factory reset, a signing-key change, deleting a schedule. |
| Missed sends (phone was off) | **Up to 20 min late: send automatically. Later: ask** (Send now / Postpone… / Discard). Never skip silently. Replaces the 2-hour silent skip. |
| Plan files | `docs/plans/`, first left uncommitted for review, then **committed** alongside the phase 0 work so each PR can be read against its plan item. |

### Clarification: idle phones are not affected by the grace window

Scheduled messages send on time even if the phone hasn't been touched for days. `setAlarmClock` fires in Doze, and the app wakes and unlocks the phone itself. The grace window applies only when the alarm couldn't fire at all: usually the phone was **switched off**, occasionally the app was force-stopped or the clock was changed. Nothing on the device can send while it's off; this decision covers what happens once it's back on.

## Still to verify on the device

These are flagged in the plans, and the fix depends on what the phone actually does:
- B15: repeat chips overflowing;
- B16: keyboard covering the form;
- B25: status-bar icons in dark mode;
- B26: the Chat Lock prompt's negative button.
- The WhatsApp raw-contact badge ([quick-schedule.md §3](quick-schedule.md#3-recipients)).
- That window retrieval still works after trimming the accessibility config (B18).
- Which Gradle version first runs on Java 25 (T7).
