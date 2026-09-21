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
AlarmManager.setAlarmClock ──► AlarmReceiver ──► SendService (foreground, one job at a time)
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

- **AlarmScheduler** keeps exactly one alarm per enabled schedule. It uses `setAlarmClock` because Samsung's power management treats it like a user alarm; if the exact-alarm permission is revoked it falls back to `setAndAllowWhileIdle` and the list shows a warning. `BootReceiver` re-arms after reboot, app update and clock changes. Occurrences missed while the phone was off are sent immediately when inside the grace window (default 2 h) and otherwise skipped and logged.
- **SendService** is a `specialUse` foreground service holding a partial wake lock. It processes a queue of schedule ids, retries a failed send (default twice, 30 s apart), records the outcome, advances recurring schedules and posts the tap-to-send fallback notification on failure. "Send now" and "Dry run now" from the list use the same path without advancing the schedule.
- **WakeUnlockController** records whether the screen was off and the keyguard locked, acquires a screen wake lock, launches `UnlockActivity` (also via a full-screen-intent notification as a second route), and calls `requestDismissKeyguard`. On a secure device the bouncer appears and `UnlockKeyguard` types the stored PIN, handling both a text field and a numeric keypad, with or without an Enter key. After the send it locks the screen again through the accessibility global action when the phone was locked before.
- **Chat selection**: contacts with a phone number use the official `https://wa.me/<number>?text=` link, which pre-fills the composer. Groups and contacts without a number are found through WhatsApp's own search by exact name. Chats flagged as locked go through the Locked chats folder: reveal it (typing the secret code in search if one is configured), tap it, switch the biometric prompt to the device PIN, enter the PIN, then select the chat by name. None of the lock-related steps run for chats that are not flagged, so ordinary sends are unaffected.
- **UiDriver** is a small polling layer over `AccessibilityNodeInfo`: wait-for with timeouts, click with clickable-ancestor and tap-gesture fallbacks, `ACTION_SET_TEXT` with verification, and a full window-tree dump saved on every failure.
- **Selectors** holds every view id and label in one file, most-likely-first, with text fallbacks. This is the only file that should need editing when WhatsApp or One UI changes.
- **Secrets** (PIN, secret code) are AES-GCM encrypted with a non-exportable Android Keystore key and stored in DataStore. The app decrypts them at send time, so anyone who controls the app binary on the phone can too; the settings screen says so. The settings screen itself is behind the device's biometric or credential prompt.
- **No `INTERNET` permission** is declared. Nothing leaves the device.

## Failure handling

Every wait has a step timeout (default 8 s) and every job a total timeout (default 90 s). A failure captures the window tree to `files/last_window_dump.txt` (viewable in the UI inspector), the job is retried, and after the last attempt the fallback notification lets the user send with one tap: the message is copied to the clipboard and the chat opened with the text pre-filled when a number is known. Recurring schedules always advance regardless of outcome, so one failure never stalls a series.

## Known limitations

- Screen lock must be PIN or password. Pattern locks cannot be typed reliably.
- WhatsApp's whole-app lock must be off. Per-chat Chat Lock works via the PIN fallback of its prompt.
- Group names must match exactly; two chats with the same name resolve to the first search result (logged as a warning).
- Attachments, templates and Play Store distribution are out of scope.
- A send takes over the screen for roughly 15–40 s. If you are using the phone at that moment, WhatsApp will appear in front of you.
- Samsung's Advanced Protection mode blocks third-party accessibility services entirely.
