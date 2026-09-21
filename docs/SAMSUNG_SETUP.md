# Samsung (One UI) setup

Work through the in-app **Setup checklist**; it deep-links to most of these screens and re-checks them every time you return. Items it cannot verify are listed here in full.

## Permissions the app asks for

| Item | Where | Why |
|---|---|---|
| Accessibility service | Settings → Accessibility → Installed apps → WhatsApp Scheduler auto-send → On | Types the PIN, opens chats, taps Send. Idle at all other times. |
| Alarms & reminders | Settings → Apps → WhatsApp Scheduler → Alarms & reminders | Exact alarms at the scheduled minute, also in Doze. |
| Appear on top | Settings → Apps → WhatsApp Scheduler → Appear on top | Lets a background service bring WhatsApp to the front. |
| Notifications | Prompted on first launch | Foreground service notice and the tap-to-send fallback. |
| Full-screen notifications | Settings → Apps → WhatsApp Scheduler → Notifications → Allow full-screen notifications (Android 14+) | Wakes the screen when locked. |
| Battery: Unrestricted | Settings → Apps → WhatsApp Scheduler → Battery → Unrestricted | Otherwise One UI defers alarms and kills the service. |

## Samsung-specific settings (manual)

- **Never sleeping apps**: Settings → Battery → Background usage limits → Never sleeping apps → add *WhatsApp Scheduler* **and** *WhatsApp*. Also make sure neither is in *Deep sleeping apps*.
- **Put unused apps to sleep**: same screen; turn it off, or accept that the app may be put to sleep after days of not opening it. Opening the app resets this.
- **Adaptive battery**: Settings → Battery → Background usage limits (or "More battery settings") → Adaptive battery → off is safest. On is usually fine once the app is in Never sleeping apps.
- **Lock screen**: Settings → Lock screen → Screen lock type → PIN (4 digits or more) or Password. Pattern is not supported. Biometrics can stay on; the app uses the PIN as the fallback.
- **Confirm PIN without tapping OK** (Lock screen → Screen lock type → PIN → checkbox, only offered for 6+ digit PINs): optional. The app handles both auto-submit and the OK/Enter key.
- **Advanced Protection mode** (Settings → Security and privacy → Advanced Protection, on newer One UI): must stay **off**; it blocks third-party accessibility services.

## WhatsApp settings

- Settings → Privacy → **App lock: off**. Chat Lock on individual chats is fine; flag those chats as locked when scheduling.
- If you hid the Locked chats folder with a **secret code**, store the code in the app's Settings; the app types it into WhatsApp's search to reveal the folder.
- Keep WhatsApp updated, but expect the automation to need a selector refresh after major WhatsApp redesigns (see `MAINTENANCE.md`).

## In the app

1. Settings → store the lock-screen PIN (you will be asked to authenticate first).
2. Settings → Dry run on. Create a message to yourself 2 minutes ahead. Lock the phone. Watch.
3. Logs → Automation trace shows each step; the UI inspector shows the last window dump if a step timed out.
4. Dry run off.
