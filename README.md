# Remote Keyboard

Use an iPhone (landscape, as a PWA) as a keyboard for an Android tablet. Works with no internet:
both devices only need to be on the same local network (router without internet, or a hotspot).

```
iPhone Safari PWA  --ws://tablet-ip:8080-->  Android IME (HTTP + WebSocket server)  -->  focused text field
```

## Build the Android app
1. Open this folder in Android Studio (Koala or newer) and let Gradle sync, or run `./gradlew assembleDebug`.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on the tablet.

## Set up the tablet (guided)
Open the **Remote Keyboard** app. It shows each step with a check mark when it is done:
1. **Enable the keyboard**: switch on "Remote Keyboard (iPhone)" in the keyboard list.
2. **Choose it as your active keyboard**: tap Choose and pick Remote Keyboard in the popup.
   It must stay selected; the connection only runs while it is the active keyboard.
3. **Enable the mouse** (optional, for the trackpad): turn on "Remote Keyboard mouse" in Accessibility.
   Android 13+ sideloaded: App info, menu (three dots), **Allow restricted settings**, if it is greyed out.
4. **Connect your iPhone**: the app shows the address and PIN, and "iPhone connected" when it works.

### Widget
Long-press the home screen, Widgets, **Remote Keyboard**. It shows the state, PIN and address and has one
button for the next step: Enable keyboard, Activate, then Switch back to my keyboard.
Android only lets an app switch keyboards silently with a special permission. Without it, the button opens
the keyboard popup and you tap Remote Keyboard once. To make it fully one-tap, run once from a PC with USB debugging:
`adb shell pm grant app.remotekeyboard android.permission.WRITE_SECURE_SETTINGS`
(the app's step 5 has a "Copy command" button). The widget then also restores your previous keyboard.

## Set up the iPhone (no Mac needed)
1. Put the iPhone on the same Wi-Fi as the tablet, or on the tablet's hotspot.
2. In **Safari**, open the address shown on the tablet (for example `http://192.168.1.20:8080`).
3. Enter the PIN. Tap Share, then **Add to Home Screen**, and launch it from there for full screen.
4. Rotate to landscape and type.

## Trackpad and settings (iPhone, Settings button in the top bar)
- Trackpad position: Off / Left / Right / Above / Below the keyboard. The keyboard stretches to fill the rest.
- Keyboard: Show or Hide (hide it and the trackpad fills the screen). The two can't both be off.
- Trackpad size, pointer speed, tap-to-click and natural scrolling. Settings are remembered on the iPhone.
- One finger moves, tap clicks, two quick taps double-click, press and hold = long press
  (Android's "right click": context menus), keep moving after the hold to drag, two fingers scroll,
  two-finger tap = Back.

## Notes
- Needs a focused text field on the tablet. It cannot type at lock-screen PIN prompts.
- Shift, Ctrl, Alt and Cmd are one-shot (tap, then press a key). Caps is a toggle.
  Backspace, arrows and space repeat when held. Ctrl+C/V/A and similar work in most apps.
- The PIN is checked on every connection, and 5 wrong tries lock the server for 1 minute.
  Use **New PIN** in the app to change it. Traffic is unencrypted, so use trusted networks.
- iOS suspends Safari when the screen locks or you switch apps. The page reconnects by itself when you return.
- Change the port in `Util.kt` (`PORT`).

## Files
- `app/src/main/java/app/remotekeyboard/KeyboardService.kt`: the IME, server and key injection
- `app/src/main/assets/keyboard.html`: the iPhone keyboard (PWA); `manifest.webmanifest`, `icon.png`
- `CursorService.kt`: accessibility service, cursor overlay, click/drag/scroll gestures
- `MainActivity.kt`: setup screen
