# Remote Keyboard

Use an iPhone (landscape, as a PWA) as a keyboard for an Android tablet. Works with no internet:
both devices only need to be on the same local network (router without internet, or a hotspot).

```
iPhone Safari PWA  --ws://tablet-ip:8080-->  Android IME (HTTP + WebSocket server)  -->  focused text field
```

## Build the Android app
1. Open this folder in Android Studio (Koala or newer) and let Gradle sync, or run `./gradlew assembleDebug`.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on the tablet.

## Set up the tablet
1. Open **Remote Keyboard**, tap **1. Enable keyboard**, switch on "Remote Keyboard (iPhone)".
2. Tap any text field, then **2. Choose keyboard** and pick Remote Keyboard.
   While it is active the tablet shows a small bar with the address, PIN and a "Switch keyboard" button
   to get your normal keyboard back.

## Enable the mouse (trackpad)
Android has no non-root API for a real pointer, so the trackpad uses an Accessibility service that draws
a cursor overlay and performs taps, drags and scrolls at the cursor.
1. In the app tap **3. Enable mouse (accessibility)** and turn on "Remote Keyboard mouse".
2. Android 13+ (sideloaded app): if it is greyed out, go to Settings > Apps > Remote Keyboard > menu (three dots) >
   **Allow restricted settings**, then try again.
3. The tablet bar and app screen show "mouse on" once it is running.
The service does not read screen content. The keyboard service must stay selected, since it hosts the server.

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
