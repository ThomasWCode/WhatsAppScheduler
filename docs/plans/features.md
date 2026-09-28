# New features: send time and after

These complement the quick-scheduling flow in [quick-schedule.md](quick-schedule.md). All of them come from the [decisions log](README.md#decisions-log). Bug references (Bxx) point to [bugs.md](bugs.md).

| ID | Feature | Phase |
|---|---|---|
| [F1](#f1) | Countdown heads-up when the phone is in use (Send now / Snooze 5 min / Postpone…) | 4 |
| [F2](#f2) | Quiet "Sent" confirmation notification | 4 |
| [F3](#f3) | One consistent set of "needs your attention" notifications | 4 |
| [F4](#f4) | "Check this chat now" for chats found by name | 3 |
| [F5](#f5) | Live health status and a pre-flight check before each send | 3–4 |
| [F6](#f6) | Direct Share targets for recent recipients (optional) | 5 |

---

<a id="f1"></a>
## F1 · Countdown heads-up when the phone is in use

**Decision:** When a send is due while you're using the phone, show a countdown instead of taking over the screen without warning. The notification has **both** *Snooze 5 min* and *Postpone…*.

```
┌──────────────────────────────────────────────┐
│ ✉ Sending to Mum in 0:08                     │
│ "Happy birthday! 🎉"                          │
│ [Send now]   [Snooze 5 min]   [Postpone…]    │
└──────────────────────────────────────────────┘
```

**When it appears**
- For scheduled sends only, and only when `PowerManager.isInteractive` is true (the screen is on, locked or not).
- A manual **Send now** from the app skips it, because you're present and have just confirmed.
- **During a phone or VoIP call** (`AudioManager.mode` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`; no permission needed), the send is held and re-checked every 15 s for up to 30 min. When the call ends, the countdown starts.

**Behaviour**
| You do | Result |
|---|---|
| Nothing | It sends when the countdown ends (default 10 s; 5–30 s under Settings → Advanced). |
| **Send now** | It sends immediately. |
| **Snooze 5 min** | This occurrence moves to now + 5 min (`repository.postpone`), the alarm is re-armed, and the job ends. It's logged, and doesn't count as a failure. |
| **Postpone…** | The job ends. The schedule goes on hold (`attention = HELD`, no alarm), and the postpone picker opens at the current send time ([quick-schedule.md §9](quick-schedule.md#9-postpone)). Confirming reschedules it. **Dismissing the picker leaves it held: nothing is sent until you choose a time.** It then appears under Needs attention with a notification, "Not sent: choose a new time for Mum". |

**Batches:** a batch ([quick-schedule.md §10](quick-schedule.md#10-multiple-recipients)) gets one countdown ("Sending to Mum, Dad and Sam in 10 s"). The action applies to every member.

**After the send, return to what you were doing.** Today, `tidyUp` presses Home when the phone was unlocked ([AccessibilitySender.kt:96](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/AccessibilitySender.kt#L96)). Instead:
1. Record `foregroundPackage()` before the send starts.
2. Afterwards, relaunch that app's launch intent without `RESET_TASK_IF_NEEDED`, which brings its existing task back as it was.
3. If the app was the launcher, unknown, or this app itself, fall back to Home.

**Implementation**
- A new `HeadsUpGate`, called by `SendService` before `sender.send` on scheduled requests. It posts the notification and suspends on a `CompletableDeferred<GateDecision>`. Either a `HeadsUpActionReceiver` (explicit, `FLAG_IMMUTABLE` broadcasts) completes it, or the timeout does.
- A new notification channel, "Before sending": `IMPORTANCE_HIGH` for heads-up, no sound, short vibration. The countdown uses `setUsesChronometer(true)` + `setChronometerCountDown(true)` + `setWhen(dueAt)`, with `setTimeoutAfter`.
- Snooze and Postpone change `nextAtMillis`, so the old alarm's occurrence key goes stale and can't cause a duplicate (B01).

**Tests**
- JVM: the gate decisions, with a fake clock and a fake sender.
- Device: each button; the countdown timing out; a phone call during the due time; a batch.

<a id="f2"></a>
## F2 · Quiet "Sent" confirmation

**Decision:** A silent, low-priority notification after each successful send.

- **Channel:** a new "Sent" channel at `IMPORTANCE_LOW`. It appears in the shade with no sound and no pop-up.
- **Content:** title *Sent to Mum*; text *09:00 · Happy birthday! 🎉* (the first line, truncated).
- **Action:** **Open chat**, which goes to `wa.me/<digits>` without text for contacts, or opens WhatsApp for groups. Tapping the body opens the app on that row.
- **Lifetime:** auto-cancels on tap, and after 24 h via `setTimeoutAfter`.
- **Batches:** grouped under one summary, "Sent to 3 chats".
- **Dry runs** post *Dry run: not sent to Mum* instead. That makes a forgotten dry-run mode visible (B11).
- **Clean-up:** a success also cancels any stale "Tap to send" notification for the schedule (B10).
- **Setting:** Settings → Sending → *Notify after each send*, on by default.

<a id="f3"></a>
## F3 · "Needs your attention" notifications

One channel and one vocabulary for everything that needs you. Each notification is removed once it's resolved, whether from the notification, the list, or by a later success. Each matching schedule also shows under **Needs attention** on the list.

| Situation | Notification | Actions |
|---|---|---|
| Failed before the Send tap, after all retries | "Couldn't send to Mum" | **Tap to send** (existing fallback path) · Postpone… |
| Failed *after* the Send tap (B02) | "May have been sent to Mum: check the chat" | Open chat · Mark as sent · Send again |
| Missed more than 20 min (B09) | "Missed: your message to Mum (Tue 09:00)" | Send now · Postpone… · Discard |
| Postpone picker dismissed (F1) | "Not sent: choose a new time for Mum" | Postpone… · Send now · Discard |
| PIN rejected (B04) | "Automatic sending paused: update your PIN" | Open settings |
| Pre-flight problem (F5) | "Your 09:00 message to Mum will fail: accessibility service is off" | Fix |

**Implementation**
- Send now and Discard are broadcast actions to one `AttentionActionReceiver`. Postpone opens `PostponeActivity`.
- Keep the existing `fallback` channel id, so your notification settings survive, and rename its label to "Needs your attention".
- The PIN and pre-flight items show in the list's health strip rather than as rows.

<a id="f4"></a>
## F4 · "Check this chat now"

Chats without a number (groups, and contacts found by name) fail at send time if the name doesn't exactly match what WhatsApp shows. That could be a typo, an emoji, or a renamed group, and it may not surface until days later.

- In **recipient details**, *Check this chat now* runs the search path immediately while you watch: open WhatsApp, search, open the chat, compare the title, back out. **Nothing is typed.**
- **Success** sets `verifiedAtMillis` and shows a ✓ next to the recipient.
- **Failure** shows "Couldn't find 'Work crew' in WhatsApp search" with links to the inspector and to editing the name.
- It's also offered once, as a snackbar ("Check 'Work crew' now?"), after you create a new name-based recipient.
- **Reuse:** `OpenChatBySearch.goHome/search/openResult`, plus the chat-title check. The phone is already unlocked because you're using it. It takes about 5 s.

<a id="f5"></a>
## F5 · Live health status and a pre-flight check

**Live status** drives the health strip on the list ([quick-schedule.md §7](quick-schedule.md#7-main-list)) and the warning row in the quick sheet. Ordered by severity:
1. Accessibility service **enabled but not running**. `isEnabled()` is true but `WaAccessibilityService.instance` is null; One UI can leave it like this after an update or a crash. Fix hint: "Turn it off and on again".
2. Service disabled.
3. PIN rejected (B04), or no PIN on a secure phone.
4. Dry run on (B11).
5. Notifications blocked, or battery restricted.

It re-evaluates on resume and whenever the service connects or disconnects.

**Pre-flight check**
- **When:** 15 minutes before each scheduled occurrence, a second exact alarm (`setExactAndAllowWhileIdle`, separate request code) runs the precheck. It's skipped when the occurrence was created less than 15 min ahead.
- **What:** service bound, WhatsApp installed, PIN present and not rejected.
- **Result:** notify only on a problem ([F3](#f3) table), e.g. "Your 09:00 message to Mum will fail: accessibility service is off · Fix". That leaves 15 minutes to fix it instead of discovering a failure afterwards.
- **Cost:** a plain `goAsync` receiver; no foreground service.

<a id="f6"></a>
## F6 · Direct Share targets (optional)

Publish the top recent recipients as sharing shortcuts: `ShortcutManagerCompat.pushDynamicShortcut`, plus a share-target category declared in `res/xml/shortcuts.xml`. Android's share sheet then offers **"Mum · Schedule on WhatsApp"** directly, and the quick sheet opens with both recipient and message filled in: one step fewer than the plain Share target. It's low priority, and only worth doing once the Share target is in daily use.

---

<a id="considered-and-not-planned"></a>
## Considered and not planned

| Idea | Why not |
|---|---|
| Backup, export, or automatic copies of schedules (folder snapshot, Android Auto Backup) | **Decision:** every case where data is lost is one where you'd expect or want deletion: uninstall, Clear storage, new phone or reset, a signing-key change, deleting a schedule. `allowBackup` stays `false`, and the README's "nothing leaves the device" stays true. |
| Message templates / snippets | Not chosen. |
| Launcher shortcut, Quick Settings tile | Not chosen. About 20 lines each if wanted later: a static shortcut into the quick sheet. |
| Home-screen widget | Not chosen, and the most work of the entry points. |
| Natural-language time entry ("fri 18:30") | Not chosen; the time chips cover the common cases. |
| Fixed "+1 h / +1 day" postpone amounts on messages | **Decision:** Postpone opens a picker. The only fixed amount is *Snooze 5 min* in the heads-up (F1). |
| Removing recurrence | Kept, tucked behind *Repeat…* ([quick-schedule.md §6](quick-schedule.md#6-repeat-and-the-full-editor)). |
| Attachments/media; @mentions in groups | Out of scope in [ARCHITECTURE.md](../ARCHITECTURE.md). Mentions would mean automating WhatsApp's mention picker. |
| **WhatsApp's native scheduled messages** | Not in your WhatsApp as of 2026-09-28 (beta reports since Feb 2026). **Revisit when it arrives:** the app could drive WhatsApp's own scheduler *at creation time*, while you're holding the phone. That would remove the stored PIN and the wake-and-unlock step, and messages would go out server-side even with the phone off. Recurring schedules would still need this app. |
| Sending while the phone is off | Impossible by design: no network permission, and unofficial WhatsApp clients risk bans ([ARCHITECTURE.md](../ARCHITECTURE.md)). Only native WhatsApp scheduling (above) could do it. |
