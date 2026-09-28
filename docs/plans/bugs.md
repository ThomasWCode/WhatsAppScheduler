# Bugs and defects

Found by a full read of the code at commit `de71f79` on 2026-09-28. **Nothing here has been fixed yet.**

Severity weighs impact against likelihood for the actual setup: one person, a Samsung phone on Android 14–16, English WhatsApp. Items marked **(verify on device)** are inferred from the code and platform behaviour; confirm them on the phone before fixing. The phase column refers to the order of work in [README.md](README.md).

| Severity | Meaning |
|---|---|
| **Critical** | A message goes out that you didn't intend (a duplicate), or one is reported as sent when it wasn't |
| **High** | A message is lost or duplicated in a common situation, or the phone's own safety is at risk |
| **Medium** | Wrong or confusing behaviour with a workaround, or a failure that stays silent |
| **Low** | Cosmetic issues, rare edge cases, dead code with a latent failure |

## Summary

| ID | Sev. | Title | Phase |
|---|---|---|---|
| [B01](#b01) | Critical | Opening the app during a send queues the same message again | 0 |
| [B02](#b02) | Critical | A failure after Send was tapped is retried, sending the message again | 0 |
| [B03](#b03) | Critical | Verification is fooled by texts starting "Message" and by non-English WhatsApp | 0 |
| [B04](#b04) | High | A wrong stored PIN is typed again and again | 0 |
| [B05](#b05) | High | Double-tapping Save creates two schedules and blanks the screen | 0 |
| [B06](#b06) | High | Hand-typed local phone numbers are saved in an invalid form | 0 |
| [B07](#b07) | High | "Send now" on an upcoming one-off sends it twice | 0 |
| [B08](#b08) | High | Finishing a send overwrites edits made while it ran | 0 |
| [B09](#b09) | Medium | Messages missed while the phone was off are skipped silently (→ ask after 20 min) | 4 |
| [B10](#b10) | Medium | Old "Tap to send" notifications survive a later success | 0 |
| [B11](#b11) | Medium | Global dry run silently turns every real send into a no-op | 4 |
| [B12](#b12) | Medium | The service wake lock can expire mid-queue | 0 |
| [B13](#b13) | Medium | Number fields in Settings fight you while typing | 5 |
| [B14](#b14) | Medium | PIN dialog offers only digits and saves typos unconfirmed | 0 |
| [B15](#b15) | Medium | Repeat chips don't fit on a normal-width phone (verify) | 5 |
| [B16](#b16) | Medium | Keyboard may hide fields and the Save button (verify) | 2 |
| [B17](#b17) | Medium | The form loses what you typed on rotation or theme change | 1 |
| [B18](#b18) | Medium | The accessibility service subscribes to far more than it uses | 5 |
| [B19](#b19) | Low | Inexact-alarm fallback would crash on Android 12–12L; dead elsewhere | 5 |
| [B20](#b20) | Low | Quick successive settings changes can undo each other | 5 |
| [B21](#b21) | Low | Editing a schedule silently re-enables it | 5 |
| [B22](#b22) | Low | Picking a different contact keeps the previous name | 2 |
| [B23](#b23) | Low | Default time near midnight is rejected as "in the past" | 2 |
| [B24](#b24) | Low | Setup banner on the list can be out of date | 3 |
| [B25](#b25) | Low | Status-bar icons invisible in dark mode (verify) | 5 |
| [B26](#b26) | Low | Chat Lock prompt: the selector may tap "Cancel" (verify) | 5 |
| [B27](#b27) | Low | Window dump keeps chat text; "Clear trace" doesn't remove it | 5 |
| [B28](#b28) | Low | Send history is never pruned | 3 |
| [B29](#b29) | Low | Finished one-offs keep a toggle that does nothing | 3 |

---

## Critical

<a id="b01"></a>
### B01 · Opening the app during a send queues the same message again

**Where:** [MainActivity.kt:25](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/MainActivity.kt#L25), [AlarmScheduler.kt:58–61](../../app/src/main/java/com/thomaswcode/whatsappscheduler/scheduling/AlarmScheduler.kt#L58), [SendService.kt:57–58](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L57), [SendService.kt:74–84](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L74). The same path is also reached from [SettingsScreen.kt:97](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/settings/SettingsScreen.kt#L97) (Re-arm all alarms) and [BootReceiver.kt:15–20](../../app/src/main/java/com/thomaswcode/whatsappscheduler/scheduling/BootReceiver.kt#L15) (`TIME_SET`, `TIMEZONE_CHANGED`).

**What happens:** A schedule's `nextAtMillis` only moves on when the send *finishes* (`recordRun` inside `SendService.finish`). Until then `rescheduleAll()` sees it as overdue but inside the grace window, so it calls `SendService.enqueue` again. Nothing stops the duplicate:
- the queue doesn't de-duplicate;
- `process()` only checks that the schedule exists and is enabled, never that this occurrence is still due;
- one-offs stay `enabled = true` after they finish.

So the second request sends the message again.

**Scenario:**
1. A one-off to Mum fires at 09:00 and the screen wakes.
2. At 09:00:20 you open the app. Rotating it, or the system switching to dark mode (which recreates the activity), has the same effect.
3. `rescheduleAll()` enqueues the schedule again.
4. The first job sends at 09:00:35 and marks the schedule finished. The second job then sends it again.

With retries the window is up to about 5.5 minutes (3 × 90 s job timeout + 2 × 30 s delay). A network time update that broadcasts `TIME_SET` during a send does the same.

**Fix plan:**
1. Give each occurrence an idempotency key. Put `nextAtMillis` into the alarm intent and the enqueue extras as `occurrenceAtMillis`. In `process()`, re-read the schedule and skip, with a log line, unless `schedule.nextAtMillis == request.occurrenceAtMillis`. Manual "send now" requests carry no occurrence and are handled by [B07](#b07).
2. De-duplicate the queue by schedule id, and track the in-flight id. Enqueueing an id that is already queued or running does nothing.
3. Make `rescheduleAll()` skip ids that are queued or in flight, using the service state flow from [improvements T4](improvements.md#t4).
4. Call `rescheduleAll()` from `MainActivity` only when `savedInstanceState == null`, so a recreate doesn't re-run it.

**Test:** Extract the queue and retry logic from `SendService` into a plain class, `SendQueueProcessor`, and give it a fake `MessageSender`. Check three things:
- enqueueing the same occurrence twice sends once;
- enqueueing after `recordRun` has advanced the schedule is skipped;
- a manual request isn't blocked by a scheduled one.

<a id="b02"></a>
### B02 · A failure after Send was tapped is retried, sending the message again

**Where:** [SendService.kt:90–98](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L90), [AccessibilitySender.kt:47–72](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/AccessibilitySender.kt#L47), [AccessibilitySender.kt:79–84](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/AccessibilitySender.kt#L79), [VerifySent.kt:17–22](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/VerifySent.kt#L17)

**What happens:** The retry loop retries every `SendResult.Failed`, whichever step failed. `VerifySent` runs *after* the Send button has been tapped, and the whole job, verification included, runs inside `withTimeout(jobTimeoutMs)`. So anything that fails after the tap produces `Failed`: a verify check that times out, or the job timeout expiring during verification. The next attempt then deep-links, types and taps Send again. After the last attempt, the fallback notification invites you to send it by hand as well.

**Scenario:**
1. A slow unlock uses 85 of the 90 seconds.
2. Send is tapped at 88 s.
3. The job timeout fires during `VerifySent`, giving `Failed("job")`.
4. 30 s later the message is sent again.

That can repeat for up to 3 copies, followed by a "Tap to send" prompt inviting a 4th.

**Fix plan:**
- Have `TypeAndSend` record that the tap happened, by returning a flag or setting a field on a per-job context.
- Add `SendResult.Uncertain(reason)` and `SendStatus.UNCERTAIN`, and use them for any failure after the tap.
  - Never retry an uncertain result.
  - Notify "May have been sent to Mum. Check the chat." with an **Open chat** action, not "Tap to send".
- Run verification outside the job timeout, with its own short timeout, so the job timeout can only fire before the tap.

**Test:** Use the `SendQueueProcessor` from B01 with a fake sender that fails at each step in turn. Only failures before the tap may be retried.

<a id="b03"></a>
### B03 · Verification is fooled by texts starting "Message" and by non-English WhatsApp

**Where:** [VerifySent.kt:20](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/VerifySent.kt#L20)

**What happens:** The composer counts as "cleared" when its text is empty **or starts with "Message"**, which is meant to match WhatsApp's placeholder hint. That causes two failures:
- **False success.** Take "Message me when you land": if the Send tap didn't register, the composer still starts with "Message", so the run is logged SENT, never retried, and no fallback appears.
- **False failure.** In another language the empty-composer hint isn't "Message" ("Mensaje", "Nachricht", …). Verification then always fails after a *successful* send, which leads to B02's duplicates.

**Fix plan:** Use `AccessibilityNodeInfo.isShowingHintText` (API 26+) and `hintText`. Count as success when the composer is empty, is showing its hint, or no longer equals the message. Put the check in a pure function, `composerCleared(text, hint, showingHint, message)`, so it can be unit-tested with these cases.

---

## High

<a id="b04"></a>
### B04 · A wrong stored PIN is typed again and again

**Where:** [UnlockKeyguard.kt:37–39](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/UnlockKeyguard.kt#L37), [UnlockKeyguard.kt:53–65](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/UnlockKeyguard.kt#L53), [SendService.kt:90–98](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L90), [WakeUnlockController.kt:42–46](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/WakeUnlockController.kt#L42)

**What happens:** The stored PIN goes wrong most often when you change the phone's PIN and forget to update the app. Every attempt then types the wrong PIN:
- 3 attempts per message with the default settings;
- sometimes twice per attempt, when the clicked digits didn't register and the wrong-PIN banner wasn't recognised (the gesture retry at lines 61–64);
- again for every other due message, because nothing remembers that the PIN failed.

One UI lengthens the lockout after 5 failures. If the optional **Auto factory reset** setting (Lock screen → Secure lock settings) is on, it **wipes the phone after 15 failed attempts**. Three overdue messages after a reboot, with retries, can reach that.

**Fix plan:**
- Treat "keyguard still locked after entering the PIN" as a new `SendResult.PinRejected` that is never retried.
- Store a "PIN rejected at <time>" flag next to the PIN in `SecretStore`.
  - While the flag is set, no job types the PIN. Jobs fail at precheck with "Update the PIN in Settings", and a notification says the same.
  - Saving the PIN again clears the flag.
- Only take the gesture-retry path when the field shows that *no* digits registered.
- Set a hard cap of at most 2 PIN entries per 24 hours across all jobs.

**Test:** Unit-test the circuit breaker. On the device, test with a deliberately wrong PIN, and **turn Auto factory reset off first**.

<a id="b05"></a>
### B05 · Double-tapping Save creates two schedules and blanks the screen

**Where:** [ScheduleEditorScreen.kt:230–263](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L230)

**What happens:** Save launches a coroutine without disabling itself. For a new schedule `existing` is still null at the second tap, so two rows are inserted and the message is sent twice. `onDone()` also runs twice. That pops both the editor and the list, leaving the NavHost with no destination and a blank screen.

**Fix plan:** Add a `saving` state that disables the button and makes `onDone` run at most once. Better still, move saving into a ViewModel with a single in-flight job ([improvements T2](improvements.md#t2)). The quick sheet needs the same guard.

<a id="b06"></a>
### B06 · Hand-typed local phone numbers are saved in an invalid form

**Where:** [ScheduleEditorScreen.kt:251](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L251), [:291](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L291), [:296](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L296)

**What happens:** Only the contact-picker path converts to E.164 using the default country. A number typed by hand goes through `normalise()`, which just puts "+" in front of the digits:
- `07700 900123` becomes `+07700900123`;
- `0044 7700 900123` becomes `+00447700900123`.

Validation only checks for at least 8 digits, so the schedule saves. At send time WhatsApp rejects the link, and the message fails hours later with nobody around to fix it.

**Fix plan:** Add a shared `PhoneNormaliser` helper and unit-test it. It should:
- run typed input through `PhoneNumberUtils.formatNumberToE164(input, defaultCountryIso)`;
- accept a leading `00`;
- reject anything that doesn't parse.

Show the result under the field, e.g. "Opens chat with +44 7700 900123". The new recipient picker ([quick-schedule.md §3](quick-schedule.md#3-recipients)) uses the same helper.

<a id="b07"></a>
### B07 · "Send now" on an upcoming one-off sends it twice

**Where:** [ScheduleListScreen.kt:122](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L122), [:186](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L186), [ScheduleRepository.kt:57–60](../../app/src/main/java/com/thomaswcode/whatsappscheduler/data/ScheduleRepository.kt#L57)

**What happens:** Send now enqueues with `advance = false`. The pending occurrence is left alone, so it also fires at its scheduled time. It also runs straight from the menu with no confirmation, so one mis-tap messages a real contact.

**Fix plan:** Put Send now behind a confirmation dialog that names the recipient.
- **One-off:** the dialog reads "Send to Mum now instead of Tue 09:00?" and consumes the occurrence. That means `advance = true` with the occurrence key from B01.
- **Recurring:** the dialog offers two choices: "Send this occurrence early" (advances) or "Send an extra copy now" (doesn't advance).

<a id="b08"></a>
### B08 · Finishing a send overwrites edits made while it ran

**Where:** [ScheduleRepository.kt:39–54](../../app/src/main/java/com/thomaswcode/whatsappscheduler/data/ScheduleRepository.kt#L39), [:57–60](../../app/src/main/java/com/thomaswcode/whatsappscheduler/data/ScheduleRepository.kt#L57)

**What happens:** `recordRun` and `recordManual` write back the *whole row*, using the `Schedule` object loaded when the job started (`schedules.update(schedule.copy(...))`). Anything changed during a send is silently reverted when the job finishes: new message text, a new time, or switching the schedule off. If the time was changed, the next alarm is also computed from the old one. With retries, this window is minutes long.

**Fix plan:**
- Replace the full-row write with a targeted `@Query("UPDATE schedules SET nextAtMillis = :next, lastRunAtMillis = :ran, lastResult = :result WHERE id = :id")`.
- Wrap it in a `@Transaction` that re-reads the row to compute the next occurrence.
- While a job runs, show "Sending…" on its row and block editing ([quick-schedule.md §7](quick-schedule.md#7-main-list)).

---

## Medium

<a id="b09"></a>
### B09 · Messages missed while the phone was off are skipped silently

**Where:** [AlarmScheduler.kt:62–66](../../app/src/main/java/com/thomaswcode/whatsappscheduler/scheduling/AlarmScheduler.kt#L62)

**What happens:** The grace window only applies when the alarm couldn't fire at all: usually the phone was switched off, occasionally the app was force-stopped or the clock was changed. An idle or locked phone is *not* affected, because `setAlarmClock` fires in Doze. Past the window (default 2 h), the occurrence is logged SKIPPED and nobody is told. A one-off then shows "Finished", which reads like success.

**Decision (2026-09-28):** Up to **20 minutes late**, send automatically. Later than that, **ask**; never skip silently.

**Fix plan:**
- Change the `missedGraceMinutes` default from 120 to **20**. It stays adjustable under Settings → Advanced.
- Past the window, don't send. Put the message on hold and post "Missed: your message to Mum (Tue 09:00) wasn't sent", with **Send now**, **Postpone…** and **Discard** actions. It also appears under *Needs attention* ([features F3](features.md#f3)).
- **One-off:** the schedule itself is held (`holdReason = MISSED`, no alarm) until you pick an action.
- **Recurring:** the series moves on to its next occurrence as normal. The missed occurrence becomes a held one-off copy (`sourceScheduleId` points at the series), so Send now, Postpone and Discard use the ordinary one-off machinery.
- Update TESTING.md #9 and ARCHITECTURE.md, which currently describe "skipped and logged".

<a id="b10"></a>
### B10 · Old "Tap to send" notifications survive a later success

**Where:** [FallbackNotifier.kt:20–27](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/FallbackNotifier.kt#L20). Nothing ever cancels `ID_FALLBACK_BASE + id`.

**Scenario:** Yesterday's recurring send failed. Today's succeeded, or you used Send now. Yesterday's notification is still there. Tapping it opens the chat with the text pre-filled, and you send a duplicate.

**Fix plan:** Cancel the notification when that schedule is sent (for real or as a dry run), deleted or edited. Put the occurrence time in the notification text so a stale one is recognisable.

<a id="b11"></a>
### B11 · Global dry run silently turns every real send into a no-op

**Where:** [SendService.kt:86](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L86), [ScheduleListScreen.kt:98–105](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L98)

**What happens:** If Dry run is left on after calibration, due messages are typed, cleared and logged DRY_RUN, and nobody is told. The only hint is a small tertiary-coloured line on the list.

**Fix plan:**
- Show a prominent `errorContainer` banner on the list and inside the quick sheet.
- Post a "Dry run: not sent" notification after each dry run ([features F2](features.md#f2)).
- Optionally, switch dry-run mode off automatically after 1 h ([improvements I6](improvements.md#i6)).

<a id="b12"></a>
### B12 · The service wake lock can expire mid-queue

**Where:** [SendService.kt:48–50](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L48)

**What happens:** A single 10-minute partial wake lock covers the service's whole lifetime. The worst case for one message is about 5.5 minutes (3 × 90 s + 2 × 30 s), so two failing messages outlast it. Once it expires, the CPU can sleep during the retry `delay` while the screen is off, and the retry runs late.

**Fix plan:** Acquire the wake lock per job, with a timeout derived from the retry budget, and release it after each job.

<a id="b13"></a>
### B13 · Number fields in Settings fight you while typing

**Where:** [SettingsScreen.kt:158–171](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/settings/SettingsScreen.kt#L158), used by the fields at [:84–88](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/settings/SettingsScreen.kt#L84)

**What happens:** Every keystroke saves a clamped value, and `remember(value)` then resets the text to that value. For example, typing `15000` into Step timeout:
1. "1" is saved as 2000, the minimum.
2. The field now shows `2000`.
3. The next key, "5", turns it into `20005`.

**Fix plan:** Keep the text local. Validate and save on IME Done or loss of focus, and show an error for out-of-range values instead of clamping silently.

<a id="b14"></a>
### B14 · PIN dialog offers only digits and saves typos unconfirmed

**Where:** [SettingsScreen.kt:105–113](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/settings/SettingsScreen.kt#L105) (`numeric = true`), [:138](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/settings/SettingsScreen.kt#L138)

**What happens:** The dialog title says "PIN or password", but it uses `KeyboardType.NumberPassword`, so an alphanumeric password can't be typed. A single mistyped entry is also saved without confirmation, which leads straight into [B04](#b04). Saving an empty field removes the PIN without warning.

**Fix plan:**
- Add a PIN / Password toggle.
- Ask for the value twice.
- Make removal an explicit "Remove PIN" button.
- Saving clears the B04 "PIN rejected" flag.

<a id="b15"></a>
### B15 · Repeat chips don't fit on a normal-width phone (verify on device)

**Where:** [ScheduleEditorScreen.kt:199–208](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L199) (6 chips) and [:210–219](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L210) (7 weekday chips), each in a plain `Row`

**What happens:** Six chips need roughly 470 dp. At 360–412 dp the last ones ("Hours", "Days") get squeezed to nothing or clipped. The weekday row also overflows at 360 dp.

**Fix plan:** Use `FlowRow`. The redesigned full editor replaces this row anyway ([improvements I7](improvements.md#i7)).

<a id="b16"></a>
### B16 · Keyboard may hide fields and the Save button (verify on device)

**Where:** [MainActivity.kt:21](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/MainActivity.kt#L21), [ScheduleEditorScreen.kt:134–135](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L134). There is no `imePadding()`, and targetSdk 35 enforces edge-to-edge on Android 15+, where `adjustResize` may no longer shrink Compose content by itself.

**Fix plan:** Call `enableEdgeToEdge()` in `MainActivity`, and add `Modifier.imePadding()` to every form and sheet. The focused field should scroll into view. Both the quick sheet and the Share sheet need this.

<a id="b17"></a>
### B17 · The form loses what you typed on rotation or theme change

**Where:** [ScheduleEditorScreen.kt:71–87](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L71), which uses plain `remember`

**What happens:** Recreating the activity resets the whole form. That happens on rotation, when scheduled dark mode switches at sunset, or when the font size changes. For an existing schedule the `LaunchedEffect` reloads the stored values, so your edits are lost too. The recreate also re-runs `rescheduleAll()` (B01).

**Fix plan:** Move form state into a ViewModel with `SavedStateHandle` ([improvements T2](improvements.md#t2)), or use `rememberSaveable` as a minimum.

<a id="b18"></a>
### B18 · The accessibility service subscribes to far more than it uses

**Where:** [accessibility_service_config.xml:3,5](../../app/src/main/res/xml/accessibility_service_config.xml#L3), [WaAccessibilityService.kt:22–39](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/WaAccessibilityService.kt#L22)

**What happens:** The service asks for more than it needs:
- It subscribes to every window-content change in WhatsApp, SystemUI (the clock, the notification shade), Settings and Samsung's biometric apps.
- It requests `flagRequestFilterKeyEvents`.

None of it is used:
- The only fields the events update, `lastEventPackage` and `lastEventAtMillis`, are never read.
- `onKeyEvent` isn't implemented.

The cost is a constant stream of calls into the app even while it's idle, which is what TESTING.md check #10 guards against. Key filtering is also a sensitive capability that Android lists when you turn the service on.

**Fix plan:** Remove `flagRequestFilterKeyEvents` and `typeWindowContentChanged`. The driver polls, so it doesn't need events. Delete the dead fields. Afterwards, check that `windows` and `rootInActiveWindow` still work with the reduced config.

---

## Low

<a id="b19"></a>
### B19 · Inexact-alarm fallback would crash on Android 12–12L; dead elsewhere

**Where:** [AlarmScheduler.kt:38–45](../../app/src/main/java/com/thomaswcode/whatsappscheduler/scheduling/AlarmScheduler.kt#L38), [AlarmReceiver.kt:16](../../app/src/main/java/com/thomaswcode/whatsappscheduler/scheduling/AlarmReceiver.kt#L16)

**What happens:** It depends on the Android version:
- **Android 13+:** `USE_EXACT_ALARM` is granted automatically, so `canScheduleExactAlarms()` is always true. The fallback never runs on your phone, and the "Alarms & reminders" checklist item is always green.
- **Android 12/12L:** the user can revoke exact alarms. The fallback alarm then fires, but a foreground service may not be started from an inexact alarm. The receiver throws `ForegroundServiceStartNotAllowedException` and the message is lost silently.

**Fix plan:** Raise minSdk ([improvements R1](improvements.md#r1)), then delete both the fallback and the checklist item. If minSdk stays at 31, catch the exception and post the fallback notification.

<a id="b20"></a>
### B20 · Quick successive settings changes can undo each other

**Where:** [SettingsStore.kt:63–77](../../app/src/main/java/com/thomaswcode/whatsappscheduler/data/settings/SettingsStore.kt#L63). It reads `current()` outside the DataStore transaction, then writes every key.

**Fix plan:** Apply the transform to the preferences snapshot inside `edit {}`.

<a id="b21"></a>
### B21 · Editing a schedule silently re-enables it

**Where:** [ScheduleEditorScreen.kt:256](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L256)

**Fix plan:** Keep `existing.enabled`. For one-offs this becomes moot once their toggle is removed ([improvements R4](improvements.md#r4)).

<a id="b22"></a>
### B22 · Picking a different contact keeps the previous name

**Where:** [ScheduleEditorScreen.kt:126](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L126) (`if (name.isBlank())`)

**What happens:** The new number is saved with the old name. The label is wrong, and so is the chat, if the send ever falls back to searching by name.

**Fix plan:** Replace the name while it still equals the previous pick's name. The recipient picker supersedes this.

<a id="b23"></a>
### B23 · Default time near midnight is rejected as "in the past"

**Where:** [ScheduleEditorScreen.kt:78–79](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L78). The date is today and the time is now + 5 min, so at 23:57 you get 00:02 *today*.

**Fix plan:** Derive both from a single `LocalDateTime.now(zone).plusMinutes(5)`. The time chips supersede this.

<a id="b24"></a>
### B24 · Setup banner on the list can be out of date

**Where:** [ScheduleListScreen.kt:70](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L70). The banner is only re-evaluated when `hasPin` or the schedule list changes, never on resume. An accessibility service that One UI switches off isn't noticed until something else changes.

**Fix plan:** Re-evaluate on `ON_RESUME`, and add the live service-bound check ([features F5](features.md#f5)).

<a id="b25"></a>
### B25 · Status-bar icons invisible in dark mode (verify on device)

**Where:** [themes.xml:4](../../app/src/main/res/values/themes.xml#L4) sets `windowLightStatusBar = true` unconditionally. Under targetSdk 35 edge-to-edge the app draws behind a transparent bar, so dark icons sit on a dark top bar.

**Fix plan:** Call `enableEdgeToEdge()`, which picks icon contrast per theme, and remove the fixed flag.

<a id="b26"></a>
### B26 · Chat Lock prompt: the selector may tap "Cancel" (verify on device)

**Where:** [Selectors.kt:117–120](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/Selectors.kt#L117), [HandleAuthPrompt.kt:16](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/HandleAuthPrompt.kt#L16), [:42–45](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/HandleAuthPrompt.kt#L42)

**What happens:** `authUseCredentialById` includes `button_negative` and `negative_button`, which on many biometric prompts is **Cancel**. That selector is tried first. If "Use PIN" has a different id on the device, the prompt is dismissed and the step fails with a misleading "PIN field not found".

**Fix plan:** Accept the negative-button ids only when the button's text also matches the use-credential regex.

<a id="b27"></a>
### B27 · Window dump keeps chat text; "Clear trace" doesn't remove it

**Where:**
- [AutomationLog.kt:54–59](../../app/src/main/java/com/thomaswcode/whatsappscheduler/util/AutomationLog.kt#L54): `clear()` leaves `last_window_dump.txt` in place.
- [UiDriver.kt:222–223](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/UiDriver.kt#L222): the dump keeps up to 60 characters of every non-editable text, which includes chat names and message previews.
- [InspectorScreen.kt:76–77](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/inspector/InspectorScreen.kt#L76): the dump can be shared.

**Fix plan:** Make Clear delete the dump as well. Add one line of warning next to Share: "Contains chat names and message previews."

<a id="b28"></a>
### B28 · Send history is never pruned

**Where:** [ScheduleRepository.kt:62](../../app/src/main/java/com/thomaswcode/whatsappscheduler/data/ScheduleRepository.kt#L62). `pruneLogs` exists but nothing calls it, so `send_log` grows forever even though the UI shows only 200 rows.

**Fix plan:** Prune history older than 90 days in the same housekeeping pass that auto-clears Sent items ([quick-schedule.md §8](quick-schedule.md#8-sent-section-and-auto-clear)).

<a id="b29"></a>
### B29 · Finished one-offs keep a toggle that does nothing

**Where:** [ScheduleListScreen.kt:175](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L175), [:182](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleListScreen.kt#L182). Switching a finished one-off off and on just recalculates "no next occurrence".

**Fix plan:** The list redesign covers this: a Sent section, and no toggle on one-offs ([quick-schedule.md §7](quick-schedule.md#7-main-list)).
