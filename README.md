# WhatsApp Scheduler (Android)

Schedules WhatsApp text messages to contacts and groups, one-off or recurring, and sends them automatically at the chosen time from your own WhatsApp account, even when the phone is locked. No server, no WhatsApp API, no network permission.

It works by automating the official WhatsApp app through Android's accessibility service: at the scheduled minute it wakes the screen, enters the lock-screen PIN you stored, opens the chat, types the message and taps Send, then locks the phone again. Chats behind WhatsApp Chat Lock are supported through the same PIN.

Read the honest caveats in `docs/ARCHITECTURE.md` before relying on it: UI automation breaks when WhatsApp or One UI changes their layouts, and the app stores your lock-screen PIN on the device.

## Requirements

- Android 12 or newer (built and tested against Samsung One UI on Android 14–16).
- Screen lock type PIN or password (with or without biometrics). Pattern is not supported.
- WhatsApp or WhatsApp Business installed. WhatsApp's whole-app lock must be off; per-chat Chat Lock is fine.
- JDK 17+ and Android SDK (platform 35, build-tools 35.0.0) to build.

## Build and install

```bash
# from the repository root; the Gradle wrapper downloads Gradle 8.14.3 itself
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
latest/bin/sdkmanager --sdk_root=$HOME/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$HOME/android-sdk" > /path/to/WhatsAppScheduler/local.properties
```

Or open the project in Android Studio (Ladybug or newer), which installs the SDK for you.

## Tests

```bash
./gradlew test
```

Unit tests cover the recurrence arithmetic (including daylight-saving transitions and month clamping) and the text regexes the automation relies on. Everything that touches WhatsApp's UI can only be tested on a phone; the protocol is in `docs/TESTING.md`.

CI (`.github/workflows/ci.yml`) runs the unit tests, lint and a debug build on every pull request and on `main`, and keeps the debug APK as a build artifact.

## First-time setup on the phone

1. Install the APK and open the app. Open **Settings → Setup checklist** and clear every red item. The Samsung-specific steps are in `docs/SAMSUNG_SETUP.md`.
2. In **Settings**, store your lock-screen PIN. If you hid WhatsApp's Locked chats folder behind a secret code, store that too.
3. Turn on **Dry run**, create a message to yourself two minutes ahead, lock the phone and watch it unlock, open the chat, type the text, clear it and re-lock. Check **Logs → Automation trace** if it stops anywhere.
4. Turn Dry run off.

## Project layout

- `app/src/main/java/com/thomaswcode/whatsappscheduler/domain` – recurrence rules and calculator, chat target, results.
- `data` – Room database, repository, encrypted secret store, settings.
- `scheduling` – exact alarms, boot recovery, missed-send handling.
- `sending` – foreground service, wake/unlock flow, fallback notification, and `accessibility/` with the UI driver, the selector catalogue and the individual automation steps.
- `ui` – Compose screens: schedule list, editor, settings, setup checklist, logs, UI inspector.
- `docs/` – architecture, Samsung setup, maintenance (refreshing view ids), device test protocol.

## Licence

Personal-use project; no licence granted for redistribution on app stores.
