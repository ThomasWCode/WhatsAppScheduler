# Maintenance: when a WhatsApp or One UI update breaks a step

All view ids and labels live in one file:

`app/src/main/java/com/thomaswcode/whatsappscheduler/sending/accessibility/Selectors.kt`

## Diagnose

1. Open **Logs → Automation trace**. The last `E` line names the step and what it waited for, e.g. `search: Timed out after 8000ms waiting for wa.searchInput, wa.anyEditable`.
2. Open **Settings → UI inspector**. The dump captured at the failure is shown; every line is one node with its class, `id=`, `text=`, `desc=`, and flags. Search it for the element the step wanted.
3. If the dump is not enough, use the inspector's **Capture in 8 s** button: press it, switch to the WhatsApp screen in question (or lock the phone and wake it to the PIN pad), and read the fresh dump. **Share** sends it to yourself for reading on a bigger screen.

Alternative from a computer:

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

## Fix

- Add the new id to the front of the relevant `ids` list in `Selectors.kt` (keep the old one; different WhatsApp versions coexist across devices).
- If the element has no stable id, extend the text/description regex instead.
- Rebuild, install, run a **Dry run** against yourself, then a real send.

## Where each step looks

| Step | Selector(s) | Typical breakage |
|---|---|---|
| `unlockKeyguard` | `keyguardPasswordField`, `keypadDigit*`, `keyguardEnter*` | One UI lock-screen redesign |
| `authPrompt` | `authUseCredential*`, `authPasswordField`, `authEnterByText` | Biometric prompt redesign; button label change ("Use PIN" → other wording) |
| `deepLink` | `waEntry`, `waInvalidNumberDialog` | Composer id change |
| `search` | `waSearchButton`, `waSearchInput`, `waConversationRowTitle`, `waChatTitle` | WhatsApp search redesign |
| `lockedChat` | `waLockedChatsRow` + the above | Folder renamed or moved |
| `typeAndSend` | `waEntry`, `waSend` | Send button id change |
| `verify` | `waEntry`, `waMessageText` | Bubble id change (only affects the confidence log line) |

## Timeouts

Slow phones or long chat lists may need a larger step timeout: Settings → Step timeout (ms). The whole-send timeout caps one attempt; the retry count and delay are alongside.
