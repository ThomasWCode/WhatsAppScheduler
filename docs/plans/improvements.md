# Improvements: polish, removals, tech debt, tests

Everything that isn't a bug ([bugs.md](bugs.md)) or a new feature ([quick-schedule.md](quick-schedule.md), [features.md](features.md)). IDs: **I** = change or polish, **R** = remove or simplify, **T** = tech debt, **Q** = tests.

---

## Changes and polish

<a id="i1"></a>
### I1 · Friendly times everywhere
- **Lists and notifications** read "Today 20:00 · in 3 h", "Tomorrow 09:00" or "Sat 3 Oct 18:30", instead of the fixed `EEE d MMM yyyy, HH:mm` from [Common.kt:30](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/Common.kt#L30). The relative part refreshes every minute while the list is visible.
- **12/24-hour** follows the system setting (`DateFormat.is24HourFormat`) in the labels and the time picker. This replaces the hard-coded `is24Hour = true` at [ScheduleEditorScreen.kt:282](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/schedules/ScheduleEditorScreen.kt#L282).
- **Other time zones:** when a schedule's zone differs from the phone's current zone, the row adds it, e.g. "09:00 London time". Rules keep the zone they were created in; this just makes that visible.

<a id="i2"></a>
### I2 · Plain-language results
Today the row shows raw text such as `Last: FAILED: search: Timed out after 8000ms waiting for wa.searchInput, wa.anyEditable`. Map step codes to sentences in the UI, and keep the technical text in Logs:

| Step | Shown as |
|---|---|
| `precheck` | "Auto-send is off" / "WhatsApp isn't installed" |
| `wake`, `unlockKeyguard` | "Couldn't unlock the phone" |
| `search`, `lockedChat` | "Couldn't find the chat 'Work crew'" |
| `deepLink` | "WhatsApp didn't accept the number +44 …" |
| `authPrompt` | "Couldn't open the locked chat" |
| `typeAndSend` | "Couldn't tap Send" |
| after the tap | "May have been sent: check the chat" (B02) |

This needs the typed `lastStatus` / `attention` columns ([T5](#t5)).

<a id="i3"></a>
### I3 · Theme, edge-to-edge and icon
- **Edge-to-edge:** call `enableEdgeToEdge()` in `MainActivity` and the new activities, and remove the fixed `windowLightStatusBar` (B25). Apply `imePadding()` on every form and sheet (B16).
- **Colour:** use one colour story in both modes. Today light mode forces WhatsApp teal as `primary` while dark mode uses pure dynamic colour ([AppTheme.kt:20–22](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/AppTheme.kt#L20)). Either seed both from the teal, or go fully dynamic. Recommendation: fully dynamic, with teal only as the fallback on devices without dynamic colour.
- **Icon:** add a `<monochrome>` layer to [ic_launcher.xml](../../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) so themed icons work.

<a id="i4"></a>
### I4 · Settings reorganised
```
Secrets        Lock-screen PIN (PIN/Password, enter twice; B14) · Chat Lock secret code
Sending        Notify after each send (F2) · Morning / evening times for chips · Keep sent messages for (30 days)
               · Re-lock after sending · Your own number ("Me" chip)
Test mode      Dry run (auto-off after 1 h, I6)
Advanced ▸     Retries · Seconds between retries · Step timeout · Whole-send timeout
               · Missed-send grace (default 20 min) · Countdown length (F1) · Default country
               · Use phone-number link for locked contacts
Diagnostics ▸  Setup checklist · Logs · UI inspector · Re-arm all alarms
```
Number fields commit on Done or loss of focus, with inline errors (B13).

<a id="i6"></a>
### I6 · Dry run becomes "Test mode"
- **Visible:** a prominent banner on the list and in the quick sheet, and a "Dry run: not sent" notification after each run (F2). Together these cover B11.
- **Auto-off:** test mode switches itself off after 1 h, with an "until turned off" option. Store `dryRunUntilMillis` in place of the boolean.
- **Per message:** *Test (dry run)* stays in each row's menu, for checking one message without switching the whole mode on.

<a id="i7"></a>
### I7 · Full editor (recurring only) cleanup
- **Shared parts:** reuse the recipient field and time picker from the quick sheet. Remove the Contact/Group segmented control and the Chat Lock switch; those are recipient properties now (R5, R8).
- **Repeat chips:** put them in a `FlowRow` (B15). Weekday chips use `DayOfWeek.getDisplayName(TextStyle.SHORT, locale)`. The rule preview reads like a sentence: "Every Mon and Thu at 18:30, starting Mon 5 Oct".
- **Validation and exits:** validate inline as you type. Ask before discarding unsaved changes on Back. Add a Delete action. Keep the existing enabled state (B21).

<a id="i8"></a>
### I8 · Logs screen
- **Sends tab**
  - Show plain-language statuses (I2) and an empty state.
  - Add filter chips (All, Sent, Failed, Missed).
  - Tapping an entry opens its schedule, if it still exists.
- **Trace tab**
  - *Clear* appears only here, and also deletes the window dump (B27).
  - Add a *Share trace* action.
  - Colour warnings and errors.
- **Placement:** move the whole screen under Settings → Diagnostics (R7).

<a id="i9"></a>
### I9 · Setup checklist
- **Grouping:** split into **Required** (blocks sending), **Recommended** and **Manual**.
- **Live service check:** add the "enabled but not running" check ([features F5](features.md#f5)).
- **First launch:** show the checklist automatically until every required item is OK. The unused `onboardingDone` setting can drive this; otherwise delete it (R2).
- **PIN item:** add one sentence: "If One UI's *Auto factory reset* is on, keep the stored PIN up to date. The app stops after one rejected unlock (B04), but a wrong PIN still costs attempts."

<a id="i10"></a>
### I10 · Automation tidiness
- **Leave no draft behind:** after a failed attempt that deep-linked with the text pre-filled, clear the composer during `tidyUp`. Otherwise the text stays in WhatsApp as a draft, where it could be sent by accident later.
- **Return to what you were using** after a send ([features F1](features.md#f1)).

<a id="i11"></a>
### I11 · Accessibility (a11y)
- Every chip, avatar and icon button needs a content description.
- Touch targets must be at least 48 dp; the Lock icon and the chip spacing are currently tight.
- The countdown notification needs spoken-friendly text ("Sending to Mum in 8 seconds").

<a id="i12"></a>
### I12 · Strings in resources
Only 8 strings live in [strings.xml](../../app/src/main/res/values/strings.xml). About 43 `Text("…")` literals sit inline in composables. Moving them makes the wording consistent and allows translation later. Do it screen by screen as each one is rebuilt; it doesn't need a pass of its own.

<a id="i13"></a>
### I13 · Documentation updates (as features land)
- **[README.md](../../README.md):** the quick sheet, Share, the new setup flow.
- **[ARCHITECTURE.md](../ARCHITECTURE.md):**
  - occurrence keys and de-duplication (B01);
  - the Uncertain result (B02);
  - the PIN circuit breaker (B04);
  - the heads-up gate (F1);
  - the 20-min grace then ask (B09);
  - the `send_log` foreign key.
- **[TESTING.md](../TESTING.md):** the new device scenarios from each plan file; update #9 for the grace behaviour.

---

## Removals and simplifications

<a id="r1"></a>
### R1 · Raise minSdk from 31 to 34
Your phone runs Android 14–16. Raising minSdk to 34 removes:
- the `SDK_INT >= 34` branches ([SendService.kt:43–47](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/SendService.kt#L43), [PermissionChecks.kt:77–78](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/onboarding/PermissionChecks.kt#L77));
- the inexact-alarm fallback and its latent crash (B19);
- the always-green "Alarms & reminders" checklist item.

Only do this if you'll never install on an older phone.

<a id="r2"></a>
### R2 · Delete dead code
- [WaAccessibilityService.kt](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/WaAccessibilityService.kt):
  - `lastEventPackage` and `lastEventAtMillis` are never read (lines 22–28, 37–38).
  - The `connected` alias duplicates `instance`.
- `ScheduleRepository.observeSchedule` is unused.
- `Settings.onboardingDone` is unused, unless I9 adopts it.
- Duplicated KDoc lines: [UiDriver.kt:44–45](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/UiDriver.kt#L44) and [OpenLockedChat.kt:39–43](../../app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/steps/OpenLockedChat.kt#L39).
- [InspectorScreen.kt:57](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/inspector/InspectorScreen.kt#L57): `Spacer(Modifier.width(8.dp))` inside a `Column` should be `height`.
- [SetupScreen.kt:49–54](../../app/src/main/java/com/thomaswcode/whatsappscheduler/ui/onboarding/SetupScreen.kt#L49) adds a lifecycle observer inside `LaunchedEffect` and never removes it. Use `DisposableEffect`, or `LifecycleResumeEffect` from lifecycle 2.8.

### R3 · Trim the accessibility service config
Remove key-event filtering and content-change events (B18).

<a id="r4"></a>
### R4 · No on/off switch for one-offs
For a one-off, "off" really means Postpone or Delete. Only recurring rows keep a Pause switch (B29).

### R5 · Chat Lock toggle leaves the message form
It becomes a recipient property, set once in recipient details ([quick-schedule.md §3](quick-schedule.md#3-recipients)).

### R6 · Tuning knobs move to Advanced
These settings stay, just out of the way: retries, delays, both timeouts, grace, default country, and the locked-contact deep-link option ([I4](#i4)).

<a id="r7"></a>
### R7 · Logs leave the main top bar
They move to Settings → Diagnostics. The list's *Needs attention* section and the notifications now carry failures to you.

<a id="r8"></a>
### R8 · Contact/Group switch removed from the quick path
Kind is implied by what you pick. A contact result is a contact; *Use "…" as a group or chat name* is a group.

---

## Tech debt

<a id="t1"></a>
### T1 · Room migrations become routine
- The v1 → v2 migration is specified in [quick-schedule.md §11](quick-schedule.md#11-data-model-changes-schema-v2).
- Add `androidx.room:room-testing` and a `MigrationTestHelper` test. `exportSchema` is already on and `app/schemas/` is committed.
- From then on, every schema change ships with an `AutoMigration` or a hand-written migration.
- Per the decisions log, data loss on update is acceptable, so `fallbackToDestructiveMigration()` may be used instead. It must be a deliberate choice, never missing by accident: a missing migration crashes the app on every launch.

<a id="t2"></a>
### T2 · ViewModels and saved state
The list, quick sheet, editor and settings each get a ViewModel with `SavedStateHandle`. Today all state lives in `remember` inside composables and the repositories are called straight from click handlers. Moving it:
- fixes lost input on recreation (B17);
- gives single-in-flight saves (B05);
- makes the screens testable.

The `viewModel()` factory can pull from `AppContainer`; no DI framework is needed.

<a id="t3"></a>
### T3 · Extract `SendQueueProcessor`
Move the queue, de-duplication, occurrence check, retry classification and PIN circuit breaker out of `SendService` into a plain class. It depends on `ScheduleRepository`, `MessageSender`, a clock and a notifier interface. `SendService` keeps only the foreground-service and wake-lock plumbing. This is what makes B01, B02 and B04 testable on the JVM.

<a id="t4"></a>
### T4 · Observable send state
Add a process-wide `SendStateHolder` in `AppContainer`, exposing `StateFlow<SendState>`: the queued ids, the current id, the current phase (countdown, unlocking, opening chat, sending) and the attempt number. It feeds:
- the list's "Sending…" rows (B08);
- the de-duplication in `rescheduleAll()` (B01);
- the health strip.

The service's own foreground notification can show the phase as well.

<a id="t5"></a>
### T5 · Typed status model
- Replace the free-text `lastResult` string with the `lastStatus` enum and `attention` columns (schema v2). Keep the detail text for Logs.
- `SendResult` gains `Uncertain` (B02) and `PinRejected` (B04).
- `Converters.stringToStatus` uses `valueOf`, which throws on an unknown name. Make it lenient (fall back to `FAILED`) so a later downgrade can't crash-loop the app.

### T6 · Atomic settings
Update settings inside `DataStore.edit {}` (B20). Replace `dryRun: Boolean` with `dryRunUntilMillis: Long?` (I6).

<a id="t7"></a>
### T7 · Toolchain refresh
AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01 and Gradle 8.14.3 are about two years old. Gradle 8.14.3 also can't run on this PC's default JDK 25, so every build needs `JAVA_HOME` pointed at the auto-provisioned JDK 17.
- Upgrade to a Gradle release that runs on Java 25 (9.1 or later; verify), with a matching AGP, Kotlin, KSP and Compose BOM.
- Do it as its own change before phase 2, so the new UI is written against current Compose APIs (`FlowRow`, `ModalBottomSheet`, the M3 date/time pickers).

### T8 · Stable debug signing key (optional)
Your decision treats a signing-key change as an expected wipe, so this is only a convenience. Committing a project `debug.keystore` (the `.gitignore` already allows `!debug.keystore`) would let builds from any machine update the installed app in place instead of forcing an uninstall. Skip it if you prefer.

---

## Tests

<a id="q1"></a>
### Q1 · Unit and Robolectric tests to add

| Area | Kind | Covers |
|---|---|---|
| `SendQueueProcessor` with fake sender, fake clock | JVM | B01 duplicates, B02 retry classification, B04 breaker, F1 gate decisions |
| `composerCleared(...)` | JVM | B03, including the "Message me…" text and non-English hints |
| `PhoneNormaliser` | JVM | B06 |
| `QuickTimes.suggest` | JVM | [quick-schedule.md §4](quick-schedule.md#4-time) table, midnight, DST |
| `ScheduleRepository` run updates, `postpone`, batches | Robolectric + in-memory Room | B08, §9, §10 |
| `AlarmScheduler.rescheduleAll` | Robolectric | 20-min grace then hold (B09); skips in-flight ids (B01) |
| Housekeeping | Robolectric | Auto-clear keeps `send_log` (§8); pruning (B28) |
| Migration 1 → 2 | Robolectric + `MigrationTestHelper` | T1 |

### Q2 · Device protocol additions
Append these to [TESTING.md](../TESTING.md):
- open the app during a send (B01);
- deliberately wrong PIN, **with Auto factory reset off** (B04);
- heads-up buttons, including during a call (F1);
- the Sent notification (F2);
- miss by 10 min vs 30 min with the phone off (B09);
- Share from WhatsApp and Chrome;
- a 3-recipient batch;
- Postpone from a row and from the notification;
- Check this chat now (F4);
- pre-flight with the service switched off (F5).
