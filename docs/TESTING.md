# Device test protocol

Unit tests (`./gradlew test`) cover the quick sheet's time chips and friendly times, recipients and batches, recurrence arithmetic, selector regexes, the send queue (de-duplication, occurrence keys, which failures are retried), the PIN circuit breaker, composer verification, phone-number parsing, the repository's run updates, the database migrations (against the exported schemas) and the editor's saved form. Everything else needs the phone. Run these in order after every selector change or WhatsApp update. Use **Dry run** first for steps 1–5, then repeat 1, 2 and 5 with Dry run off.

| # | Scenario | Expected |
|---|---|---|
| 1 | Message to yourself, 2 min ahead, phone unlocked and on the home screen | WhatsApp opens, text appears, Send is tapped, app returns home. `Logs → Sends` shows SENT. |
| 2 | Same, phone locked and screen off | Screen turns on, PIN is entered, chat opens, message sent, screen locks again. Whole run under 40 s. |
| 3 | Same as 2, but force Doze first: `adb shell dumpsys deviceidle force-idle` | Fires within a minute of the scheduled time. Reset with `adb shell dumpsys deviceidle unforce`. |
| 4 | Group by exact name; then a second group whose name differs only in case | Correct group chosen; trace shows a warning for the case-insensitive match. |
| 5 | Each of the three locked chats, phone locked, with and without a secret code configured | Folder revealed, prompt switched to PIN, PIN entered, chat opened, message sent, folder re-locked when leaving WhatsApp. |
| 6 | Force-stop the app (Settings → Apps → Force stop), then reboot | `adb shell dumpsys alarm \| grep whatsappscheduler` lists the alarms again after boot. |
| 7 | Edit `Selectors.waSend` to a wrong id, rebuild, schedule a send | Two retries 30 s apart, then a "Tap to send" notification; tapping it opens the chat with the text pre-filled. |
| 8 | Weekly rule on two weekdays | List shows the next occurrence; `dumpsys alarm` shows one alarm; after it fires the next occurrence advances. |
| 9 | Set the phone clock forward past a scheduled time while the app is closed (or leave the phone off across one) | Within the grace window: sent on next boot/app open. Beyond it: SKIPPED with "missed by N min" in Sends, and a "Missed: message to … (time)" notification whose tap opens the chat with the message ready. |
| 10 | Leave the phone idle 24 h with the accessibility service enabled | WhatsApp Scheduler does not appear above 1 % in Settings → Battery usage. |
| 11 | Schedule a message to yourself; while it is being sent, open the app, rotate it, and tap Settings → Re-arm all alarms | Sent once. The trace shows "already queued or sending; ignoring the duplicate" or "is being sent; not re-arming it". |
| 12 | **Turn Auto factory reset off first** (Lock screen → Secure lock settings). Store a deliberately wrong PIN, schedule a message 2 min ahead, lock the phone | The PIN is typed once. No retry; an "Automatic sending paused" notification and a Tap to send prompt. Settings shows why unlocking is paused. A second locked send fails at precheck without touching the lock screen. Storing the right PIN resumes sending. |
| 13 | Send now… on a one-off due in 10 min | The dialog names the recipient and the time. The message is sent once now and not again at its time; the row shows it finished. |
| 14 | Send now… on a daily schedule: once with "Send early", once with "Send a copy" | "Send early" moves the next occurrence to tomorrow; "Send a copy" leaves it as it was. |
| 15 | Editor: type a local number (`07…`), then one with a typo | The first shows "Opens chat with +44 …"; the second is flagged and Save explains why. Double-tapping Save creates one schedule. |
| 16 | Install a build with a newer database schema over the installed app (`adb install -r`) | Schedules and Logs → Sends are unchanged. Pull `databases/scheduler.db` with its `-wal` file: `PRAGMA user_version` is the new version. |
| 17 | Half-fill the editor, then rotate the phone and switch dark mode on and off | Everything typed is still there, and an existing schedule is not reloaded over the edits. |
| 18 | New message: type two letters of a contact's name, pick it, write a message, tap **In 30 min**, Schedule; then Undo | The contact shows "on WhatsApp" if it is. The row appears with the time; after Undo it is gone and `dumpsys alarm` no longer lists its alarm. Contacts permission denied: **Pick from contacts** still works. |
| 19 | In Chrome or Notes, share some text and choose **Schedule on WhatsApp** | The sheet opens over that app with the text in Message. After Schedule, a toast confirms and you are back in the other app. |
| 20 | Schedule one message to two recipients (a contact and a group), then tap the row | One row, "A and B". The sheet opens as Edit message with both chips; **Delete** removes both rows and both alarms. |
| 21 | Fill the sheet, then **Repeat…** | The full editor opens with the first recipient, the message and the time, set to Daily. Opening New message again shows an empty sheet. |
| 22 | Half-fill the sheet, swipe it away, rotate, tap New message; then **Discard** | The draft comes back after the rotation; Discard empties it. |
| 23 | Tap into the editor's message field and Settings' own-number field with the keyboard up (B16) | The keyboard never covers the field being typed in or Save. |

Useful commands:

```bash
adb logcat -s WaScheduler          # live automation trace
adb shell dumpsys alarm | grep -A3 whatsappscheduler
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys deviceidle force-idle   # simulate Doze
adb shell dumpsys deviceidle unforce
```
