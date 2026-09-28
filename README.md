# WhatsApp Scheduler (Android)

Schedules WhatsApp text messages to contacts and groups, one-off or recurring, and sends them automatically at the chosen time from your own WhatsApp account, even when the phone is locked. No server, no WhatsApp API, no network permission.

It works by automating the official WhatsApp app through Android's accessibility service: at the scheduled minute it wakes the screen, enters the lock-screen PIN you stored, opens the chat, types the message and taps Send, then locks the phone again. Chats behind WhatsApp Chat Lock are supported through the same PIN.

Read the honest caveats in `docs/ARCHITECTURE.md` before relying on it: UI automation breaks when WhatsApp or One UI changes their layouts, and the app stores your lock-screen PIN on the device.

## Requirements

- Android 12 or newer (built and tested against Samsung One UI on Android 14–16).
- Screen lock type PIN or password (with or without biometrics). Pattern is not supported.
- WhatsApp or WhatsApp Business installed. WhatsApp's whole-app lock must be off; per-chat Chat Lock is fine.
- To build: JDK 17 or newer (JDK 25, as bundled with current Android Studio, works) and the Android SDK with platform 37 and build tools 36 or newer.

## Build and install

```bash
# from the repository root; the Gradle wrapper downloads Gradle 9.8.0 itself
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the Android SDK is not on your machine yet:

```bash
# Linux/macOS example; adjust paths on Windows
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -sSL -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --sdk_root=$HOME/android-sdk --licenses
latest/bin/sdkmanager --sdk_root=$HOME/android-sdk "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
echo "sdk.dir=$HOME/android-sdk" > /path/to/WhatsAppScheduler/local.properties
```

Or open the project in a current Android Studio (one that supports AGP 9.4), which installs the SDK for you.

## Tests

```bash
./gradlew test
```

Unit tests cover the recurrence arithmetic (including daylight-saving transitions and month clamping) and the text regexes the automation relies on. Everything that touches WhatsApp's UI can only be tested on a phone; the protocol is in `docs/TESTING.md`.

CI (`.github/workflows/ci.yml`) runs the unit tests, lint and a debug build on every pull request and on `main`, and keeps the debug APK as a build artifact.

## Scheduling a message

- **New message** opens a sheet: pick who (recent chats, a contact from the type-ahead, or a group typed by its exact name), write the message and tap a time (**In 30 min**, **Tonight**, **Tomorrow 09:00**, … or **Pick date & time…**). **Schedule** saves it, with **Undo** in case it was a mistake.
- Up to 10 recipients get the same message at the same time. They are kept together as one row, and tapping it reopens the sheet to change or delete them all.
- **Repeat…** hands the draft to the full editor for daily, weekly, monthly or custom repeats.
- **Share** text from any app and choose **Schedule on WhatsApp**: the sheet opens over that app and you are back where you were after scheduling.
- The list puts what needs you first (**Needs attention**: missed, failed, or may have been sent, with Send now, Discard or Mark as sent), then **Upcoming**, **Repeating** (with a Pause switch) and a collapsed **Sent**. Swipe a row away to delete it; **Undo** brings it back with its history. The strip at the top says whether auto-send is ready, and what to fix if not.
- **Postpone…** (in a row's ⋯ menu, and on missed or failed messages) moves a message to a new time; for a repeating one it moves only the next send, and says which later ones that skips.
- Sent messages are cleared from the list after 30 days (Settings → Sending → Keep sent messages for); Logs keep their history for 90 days.
- Long-press a chip for the recipient's details. For a group or a chat found by name, **Check this chat now** looks it up in WhatsApp while you watch (nothing is typed), so a name that doesn't match shows up now rather than at send time. A new name-based chat is offered this once after scheduling.
- Contacts permission is optional. It lets the type-ahead search your contacts on the phone and mark the ones that are on WhatsApp; without it you can pick one contact at a time or type a number.

## First-time setup on the phone

1. Install the APK and open the app. Open **Settings → Setup checklist** and clear every red item. The Samsung-specific steps are in `docs/SAMSUNG_SETUP.md`.
2. In **Settings**, store your lock-screen PIN. If you hid WhatsApp's Locked chats folder behind a secret code, store that too. Optionally set your own number (for a **Me** chip in the sheet) and the morning and evening times the time chips use.
3. Turn on **Dry run**, create a message to yourself two minutes ahead, lock the phone and watch it unlock, open the chat, type the text, clear it and re-lock. Check **Settings → Diagnostics → Logs** (Automation trace) if it stops anywhere.
4. Turn Dry run off.

## Project layout

- `app/src/main/java/com/thomaswcode/whatsappscheduler/domain` – recurrence rules and calculator, chat target, results.
- `data` – Room database, repository, encrypted secret store, settings.
- `scheduling` – exact alarms, boot recovery, missed-send handling.
- `sending` – foreground service, wake/unlock flow, fallback notification, and `accessibility/` with the UI driver, the selector catalogue and the individual automation steps.
- `ui` – Compose screens: schedule list, quick sheet (`quick/`), share target (`share/`), editor, settings, setup checklist, logs, UI inspector; `health/` works out what would stop a send.
- `docs/` – architecture, Samsung setup, maintenance (refreshing view ids), device test protocol.

## Licence

Personal-use project; no licence granted for redistribution on app stores.
