# Device test protocol

Unit tests (`./gradlew test`) cover the quick sheet's time chips and friendly times, recipients and batches, recurrence arithmetic, selector regexes, the send queue (de-duplication, occurrence keys, which failures are retried), the PIN circuit breaker, composer verification, phone-number parsing, the repository's run updates, the database migrations (against the exported schemas) and the editor's saved form. Everything else needs the phone. Run these in order after every selector change or WhatsApp update. Use **Test mode** first for steps 1–5, then repeat 1, 2 and 5 with it off.

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
| 9 | Set the phone clock forward past a scheduled time while the app is closed (or leave the phone off across one) | Within the grace window (20 min): sent on next boot/app open. Beyond it: not sent; MISSED with "missed by N min" in Sends, the message under Needs attention, and "Missed: your message to … (time)" with Send now, Postpone… and Discard. For a series the next occurrence is still armed, and the missed one is held as a one-off of its own. |
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
| 24 | Switch the app's accessibility service off in system settings, go back to the app, then switch it on again (B24) | The strip turns red at once ("Auto-send is off … Fix") and back to ready or the next item when it is on. Check with a screenshot: `uiautomator dump` itself unbinds the service. |
| 25 | Swipe a row away, then tap Undo | The row goes with its history (Logs → Sends); Undo brings back the row, its history and its alarm (`dumpsys alarm`). |
| 26 | Dry run on: a one-off to a made-up group name, then ⋯ → Send now… | While it retries, the row says "Waiting to try again…" and can't be opened or swiped. Then it moves to Needs attention, "Couldn't send · <time>", with Send now and Discard; Discard shows Undo. |
| 27 | A daily schedule (Repeat… from the sheet) | It is under Repeating with a switch; switched off it says "paused". One-offs have no switch. |
| 28 | ⋯ → Postpone… on a one-off, to tomorrow evening; then Undo | The row says "Tomorrow 18:30 · moved from …" and `dumpsys alarm` shows the new time; Undo puts both back. A missed one-off's Postpone… starts at the same time on the next day and brings it back to Upcoming. |
| 29 | Postpone a daily series' next send past the one after it | The dialog says "This also skips …". After the moved send, the series continues at its usual time the next day. |
| 30 | Settings → Keep sent messages for → 7 days, with a one-off sent more than 7 days ago; go back to the list (and again after switching away and back with 30 days → 7 days) | The one-off is gone from Sent at once; Logs → Sends still lists its send. |
| 31 | In the sheet, long-press a group's chip → Check this chat now; then a made-up group name | WhatsApp opens, searches, opens the group and backs out to the app; a toast says it was found, and the details show "✓ Found in WhatsApp's search …". The made-up name gives "Couldn't find …". Nothing is typed in either. |
| 32 | Settings → Test mode on; check the list, wait an hour (or set the phone clock on), check again | While on, the list and the sheet show "Test mode is on until …" in red with Turn off; afterwards it is off by itself and messages send again. "Until turned off" keeps it on. |
| 33 | A message sent for real, then a test run (⋯ → Test); tap the "Sent to …" notification's body | "Sent to …" with the time and the first line, and Open chat; then "Dry run: not sent to …". Both quiet, gone after a day; several of either are grouped, real sends and test runs apart ("Sent to 3 chats", "Dry run: not sent to 3 chats"). Tapping the body opens the list on that row, outlined for a moment. With Notify after each send off, only the dry-run one shows. |
| 34 | Test mode on, a one-off to a made-up group a minute ahead, Retries 0; let it fail | "Couldn't send to …" under *Needs your attention*, with Tap to send and Postpone…. Postpone… opens the picker over the current app; a time moves it (toast "Moved to …") and the notification goes. |
| 35 | Missed-send grace 0, a one-off a minute ahead, then `adb shell am force-stop` until after its time; open the app | "Missed: your message to … (time)" with Send now, Postpone… and Discard. Discard removes the row (Logs keep its history); Send now sends a copy (a dry run in test mode) and the notification goes. |
| 36 | When a send is interrupted after its Send tap (B02) | "May have been sent to …: check the chat" with Open chat, Mark as sent and Send again. Mark as sent moves the row to Sent. |
| 37 | Schedule a message 20 minutes ahead, then turn the accessibility service off; wait 5 minutes | "Your … message to … will fail" with the service problem and Fix, which opens Accessibility settings. Turning it back on and sending removes the warning; with nothing wrong there is no warning at all. |
| 38 | Test mode on; a one-off a few minutes ahead, the phone in use when it's due | "Sending to … " pops up counting down from 10 s, with Send now, Snooze 5 min and Postpone…. Left alone, it goes when the count ends; Send now goes at once. With the screen off, no countdown. |
| 39 | The same, tapping Snooze 5 min; then again, tapping Postpone… | Snooze: the row says it moves to 5 minutes on, and it goes then (Logs: "snoozed 5 min on the countdown"). Postpone…: the picker opens; a time reschedules it, and dismissing leaves it under Needs attention as "Not sent: choose a new time", with that notification. For a series only that occurrence is held; the series goes on. |
| 40 | A message due while on a phone or WhatsApp call; and one due while another app is open | During the call it waits, and the countdown starts once the call ends. Afterwards the phone goes back to the app that was open, as it was (Home only from the launcher). |
| 41 | Settings → Advanced → Step timeout: clear it and type 1, then 5000 after it so it reads 15000; press Done; leave and come back. Then type another value and close Advanced without pressing Done; and one more, leaving by the back arrow straight after typing | "1" stays as typed, marked 2000–60000 and not saved; "15000" is saved on Done and still there on return; a value typed before closing Advanced, or before leaving, is saved too. The tuning settings are under Advanced, closed until opened. |
| 42 | Settings → Diagnostics → Logs; try each filter chip, then tap a failed entry | Entries read "Couldn't send to …" or "Test run for …: not sent", with why in words and the technical detail beneath, and "by hand" for manual runs. Each filter shows its own entries, or a line saying there are none. Tapping an entry opens the list at that schedule's row, outlined, with Sent opened if it's there. |
| 43 | Logs → Automation trace | Share and Clear appear only on this tab, with a note that the trace names chats. Warnings are amber and errors red. Share opens the share sheet; Clear empties the trace and the saved window dump. |
| 44 | A failed send, as in #34 | The row under *Needs attention* says why ("Couldn't find the chat · Today 09:00"). So does its notification ("Couldn't find the chat, so the message for … hasn't gone"). |
| 45 | In the quick sheet, choose a made-up group, type a message, pick Tonight, then Repeat… | The full editor opens as "Repeating message" with that chat as a chip (its details behind a tap), the message and the time, repeating daily: "Every day at 20:00, starting today". No Contact/Group or Chat Lock switch. |
| 46 | In the editor, Weekly: untick every day, then tick Thu; tap the back arrow | "Pick at least one day." while none is ticked, then "Every Thu at 20:00, starting Thu …". Back asks "Discard your changes?"; Keep editing stays. |
| 47 | Save it, open it from the list, then Delete | The list shows "Weekly on Thu · next Thu …". Opened, it is "Edit repeating message" with Delete, which asks first and removes it. |

Useful commands:

```bash
adb logcat -s WaScheduler          # live automation trace
adb shell dumpsys alarm | grep -A3 whatsappscheduler
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys deviceidle force-idle   # simulate Doze
adb shell dumpsys deviceidle unforce
```
