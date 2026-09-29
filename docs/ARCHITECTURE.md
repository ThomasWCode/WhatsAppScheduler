# Architecture

## Why this design

Five ways of scheduling WhatsApp sends without the official API were evaluated:

| Method | Automatic while locked | Account risk | Fragility | Verdict |
|---|---|---|---|---|
| Accessibility automation of the official app + stored PIN | Yes | None | UI changes | **Chosen** |
| Tap-to-send notification | No | None | None | Kept as the fallback path |
| Unofficial linked-device client (whatsmeow/Baileys) | Yes | Documented bans, tightening since 2024 | Protocol changes | Rejected |
| WhatsApp Web in a WebView with script injection | Yes | Same as above | High | Rejected |
| Notification direct-reply hijack | Only with a live notification | None | Medium | Not a scheduler |

WhatsApp itself has been testing native scheduled messages since early 2026. If that ships, this app becomes unnecessary for one-off sends; recurring sends may still need it.

## Components

```
AlarmManager.setAlarmClock ──► AlarmReceiver ──► SendService (foreground) ──► SendQueueProcessor (one job at a time)
                                                                                     │
                                                                                     ▼
                                                                              AccessibilitySender
                                                    │
        ┌──────────────────┬──────────────────┬─────┴──────────┬─────────────────┐
        ▼                  ▼                  ▼                ▼                 ▼
 WakeUnlockController  OpenChatBy*       HandleAuthPrompt   TypeAndSend      VerifySent
 (UnlockActivity +     (deep link /      (Chat Lock → PIN)
  UnlockKeyguard)       search / locked)
                                  all through UiDriver over WaAccessibilityService
```

- **AlarmScheduler** keeps exactly one alarm per enabled schedule. It uses `setAlarmClock` because Samsung's power management treats it like a user alarm; if the exact-alarm permission is revoked it falls back to `setAndAllowWhileIdle` and the list shows a warning. Each alarm carries the occurrence it is for (`occurrence_at`). `BootReceiver` re-arms after reboot, app update and clock changes. An occurrence whose alarm could not fire (phone off, force-stop, clock change) is handed to the send queue as a late alarm. The queue sends it when it is inside the grace window (default 2 h); otherwise it logs SKIPPED and posts a "Missed" notification that offers to send by hand. Deciding lateness only in the queue means a re-arm and an alarm delivered at the same moment can't produce two outcomes. Re-arming leaves alone any schedule that is queued or being sent.
- **SendService** is a `specialUse` foreground service. It holds a partial wake lock per job, sized to that job's worst case (every attempt timing out, plus the retry delays), and otherwise only hosts **SendQueueProcessor**, a plain class that can be tested on the JVM:
  - **Once per occurrence.** A request's kind is `SCHEDULED` (an alarm), `SEND_NOW` (the pending occurrence, early), `EXTRA_COPY` or `TEST` (a dry run). The first two carry the occurrence time as an idempotency key. A request is dropped if the same send is already queued or running, and skipped if the schedule has since moved past its occurrence, so opening the app, rotating it or a clock broadcast during a send cannot queue it again. `SEND_NOW` uses up the occurrence, so it isn't sent again at its time.
  - **Retries only before the tap.** Failures are classified by how far the attempt got (`JobProgress`). `Failed` (before Send was tapped) is retried, by default twice, 30 s apart, unless waiting cannot help (no PIN stored, PIN entry paused). `Uncertain` (anything after the tap, including verification) is never retried and posts "May have been sent: check the chat" instead of Tap to send. `PinRejected` is never retried either (see below).
  - **Outcomes against the current row.** `recordRun` re-reads the schedule in a transaction and writes only the run columns, and moves the schedule past the occurrence only if the row still shows it, so an edit made during a send is kept.
- **PIN circuit breaker.** A PIN typed without the phone (or the Chat Lock prompt) accepting it is recorded next to the PIN. From then on nothing types the PIN until it is saved again in Settings, and the app never makes more than two failed entries in 24 hours even across re-saves. This matters because One UI lengthens the lockout after 5 failures and, with *Auto factory reset* on, wipes the phone after 15. On the keypad the first digit decides whether clicks or touch gestures work, before anything is submitted, so the PIN is never submitted twice in one attempt.
- **WakeUnlockController** records whether the screen was off and the keyguard locked, acquires a screen wake lock, launches `UnlockActivity` (also via a full-screen-intent notification as a second route), and calls `requestDismissKeyguard`. On a secure device the bouncer appears and `UnlockKeyguard` types the stored PIN, handling both a text field and a numeric keypad, with or without an Enter key. After the send it locks the screen again through the accessibility global action when the phone was locked before.
- **Chat selection**: contacts with a phone number use the official `https://wa.me/<number>?text=` link, which pre-fills the composer. Groups and contacts without a number are found through WhatsApp's own search by exact name. Chats flagged as locked go through the Locked chats folder: reveal it (typing the secret code in search if one is configured), tap it, switch the biometric prompt to the device PIN, enter the PIN, then select the chat by name. None of the lock-related steps run for chats that are not flagged, so ordinary sends are unaffected.
- **Data** is one Room database, `scheduler.db`, at schema v2. `schedules` keeps the snapshot of target and message that is actually sent. It also records the last run's typed status (`lastStatus`) and whether it is waiting on the user (`attention`: missed, failed, may have been sent, or held). `recipients` remembers who has been messaged, for the quick sheet. `send_log` is the history; its link to a schedule is `ON DELETE SET NULL`, so history outlives a deleted schedule. Every schema change ships with a migration and a `MigrationTestHelper` test against the exported schema in `app/schemas/`: a missing migration would crash the app on every launch.
- **UI** is Compose with one ViewModel per screen, built from `AppContainer` (no DI framework). The editor's form and the quick sheet's draft live in the `SavedStateHandle`, so rotation, a theme change or process death keep what was typed.
- **Quick sheet.** `QuickScheduleViewModel` schedules a one-off to one or more recipients. Several recipients are saved in one transaction as rows sharing a `batchId` (at most 10), one per recipient, so each is sent, retried and logged on its own while the list shows them as one entry. Recipients resolve against the `recipients` table: by phone number for contacts and by exact name for groups, so what was learned about a chat, such as that it is behind Chat Lock, is reused. The contact type-ahead reads `ContactsContract` on the device and badges contacts that have a WhatsApp raw contact. `ShareTargetActivity` is a translucent activity that accepts shared text and hosts the same sheet.
- **List.** `ListSections` sorts the schedules into Needs attention (anything with `attention` set), Upcoming one-offs, Repeating series and Sent one-offs, as pure code with its own tests. A batch is one entry per section it has members in, split by outcome, and a series that needs attention appears under both Needs attention and Repeating, since the attention is about one occurrence. A delete from the list takes the schedule's history with it and keeps both in memory for Undo, which re-inserts them under the same ids (AUTOINCREMENT never reuses one). Rows being sent read `SendState` and can't be edited, swiped or switched. A test run by hand is logged but doesn't change a schedule's last run, so Sent keeps saying when the message really went.
- **Postpone** moves one occurrence without going through `save()`, which would recompute it from the rule: a one-off's time, or a series' next occurrence only. The row keeps `postponedFromMillis` ("moved from"), cleared once that occurrence has run or the schedule is saved again. After a moved occurrence runs, the series resumes after the latest of the run, the new time and the original one, so occurrences it passed are skipped and one moved earlier isn't sent again at its old time. `Postponing.skipped` lists what a move skips, for the dialog.
- **Housekeeping** runs on app start and whenever the send queue empties: sent one-offs older than the Settings period (30 days by default) are deleted, their history kept unlinked, and history older than 90 days is pruned (B28).
- **Check this chat now** (features F4) runs the search steps for a chat found by name: WhatsApp's search, the result row and the chat's title, then Back until WhatsApp has gone. Nothing is typed. It shares one `Mutex` with sends (`LockedSender` wraps the sender, `ChatChecker` takes it with `tryLock`), so a check never runs during a send and a send waits for a check. A chat that was found gets `verifiedAtMillis`, which is cleared when its name or kind changes.
- **Test mode** is `dryRunUntilMillis` in the settings: off, a time (switching it on means an hour), or until turned off. `Settings.dryRun` is computed from it, so a send decides as before, and the warning (red, with Turn off) goes by itself when the time passes. A stored on/off from before migrates to "until turned off".
- **Notifications after a send** (features F2) go to a low-importance "Sent" channel: "Sent to Mum" with Open chat, grouped, gone after a day; a test run always posts "Dry run: not sent to …".
- **Health.** `HealthCheck` lists what would stop or change a send (service off or not bound, WhatsApp missing, overlay denied, no PIN, PIN paused, dry run, exact alarms or full-screen notifications off, notifications blocked, battery restrictions), most serious first. The list's strip and the sheet show the first one, in red only when it blocks sending, and evaluate again on every resume (B24).
- **UiDriver** is a small polling layer over `AccessibilityNodeInfo`: wait-for with timeouts, click with clickable-ancestor and tap-gesture fallbacks, `ACTION_SET_TEXT` with verification, and a full window-tree dump saved on every failure.
- **Selectors** holds every view id and label in one file, most-likely-first, with text fallbacks. This is the only file that should need editing when WhatsApp or One UI changes.
- **Secrets** (PIN, secret code) are AES-GCM encrypted with a non-exportable Android Keystore key and stored in DataStore. The app decrypts them at send time, so anyone who controls the app binary on the phone can too; the settings screen says so. The settings screen itself is behind the device's biometric or credential prompt.
- **No `INTERNET` permission** is declared. Nothing leaves the device.

## Failure handling

Every wait has a step timeout (default 8 s) and every job a total timeout (default 90 s), which covers everything up to the Send tap; verification after the tap has its own step timeout. A failure captures the window tree to `files/last_window_dump.txt` (viewable in the UI inspector). A failure from before the tap clears the pre-filled message from the composer, so it doesn't wait in WhatsApp as a draft, and is retried; after the last attempt the fallback notification lets the user send with one tap: the message is copied to the clipboard and the chat opened with the text pre-filled when a number is known. The notification names the occurrence, and it is removed when the schedule is later sent, edited or deleted. Recurring schedules always advance regardless of outcome, so one failure never stalls a series.

Verification counts the composer as cleared when it is empty, shows its hint (`isShowingHintText`), or no longer holds the message. It never compares with the hint's wording, which differs by language and could match a message such as "Message me when you land".

## Known limitations

- Screen lock must be PIN or password. Pattern locks cannot be typed reliably.
- WhatsApp's whole-app lock must be off. Per-chat Chat Lock works via the PIN fallback of its prompt.
- Group names must match exactly; two chats with the same name resolve to the first search result (logged as a warning).
- Attachments, templates and Play Store distribution are out of scope.
- A send takes over the screen for roughly 15–40 s. If you are using the phone at that moment, WhatsApp will appear in front of you.
- If the process dies after Send was tapped but before the outcome is recorded, the occurrence is still pending and is sent again when alarms are next re-armed inside the grace window.
- Samsung's Advanced Protection mode blocks third-party accessibility services entirely.
