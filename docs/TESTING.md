# Device test protocol

Unit tests (`./gradlew test`) cover recurrence arithmetic and selector regexes. Everything else needs the phone. Run these in order after every selector change or WhatsApp update. Use **Dry run** first for steps 1–5, then repeat 1, 2 and 5 with Dry run off.

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
| 9 | Set the phone clock forward past a scheduled time while the app is closed (or leave the phone off across one) | Within the grace window: sent on next boot/app open. Beyond it: SKIPPED with "missed by N min" in Sends. |
| 10 | Leave the phone idle 24 h with the accessibility service enabled | WhatsApp Scheduler does not appear above 1 % in Settings → Battery usage. |

Useful commands:

```bash
adb logcat -s WaScheduler          # live automation trace
adb shell dumpsys alarm | grep -A3 whatsappscheduler
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys deviceidle force-idle   # simulate Doze
adb shell dumpsys deviceidle unforce
```
