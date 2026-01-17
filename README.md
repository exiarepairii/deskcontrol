# DeskControl

DeskControl is an Android 15 MVP that lets you launch a single app on a wired
external display and control it from your phone as a touchpad + keyboard.
Input injection and the external cursor overlay are powered by an
AccessibilityService. This project targets a single device setup (no broad
device compatibility goals).

## MVP Scope

- Android 15 only, wired Type-C external display only.
- External display detection with DisplayManager and display info in UI.
- Launch any installed app onto the external display.
- Phone acts as touchpad (relative move, click, drag, scroll) and text input.
- Cursor overlay rendered on the external display with auto-hide.
- Clean teardown when external display disconnects.

## Requirements

- Android 15 (minSdk 35).
- Wired Type-C external display.
- Accessibility service enabled (required for cursor overlay and input).

## Build

```bash
./gradlew assembleDebug
```

Install the APK via Android Studio or:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Connect a wired external display (Type-C).
2. Launch DeskControl.
3. If prompted, enable the accessibility service (used for cursor + input).
4. Use **Pick App** to launch a selected app on the external display.
5. Open **Touchpad** to move the cursor, click, drag, scroll, and send text input.
6. Open **Diagnostics** to review display info and recent failures.
7. Open **Settings** to tune cursor and touchpad parameters.
8. Use **Stop Session** to clear session state and remove overlay.

## Project Structure

- `DisplaySessionManager`: tracks external display connect/disconnect.
- `AppPickerActivity`: lists launchable apps and triggers launch.
- `AppLauncher`: wraps `setLaunchDisplayId` and failure reasons.
- `TouchpadActivity`: touchpad UI (move, click, drag, scroll).
- `ControlAccessibilityService`: cursor overlay + gesture/text injection.
- `CursorOverlayView`: cursor rendering.
- `CoordinateMapper`: maps touchpad deltas with display rotation.
- `DiagnosticsActivity`: status + last failures + injection results.
- `HostActivity`: optional host UI on external display.
- `SettingsActivity`: cursor and touchpad tuning UI.

## Permissions and Notes

- Uses `AccessibilityService` for gesture injection and `ACTION_SET_TEXT`.
- Cursor overlay uses `TYPE_ACCESSIBILITY_OVERLAY` and is non-touchable.
- The overlay is added to the external display via `createWindowContext`.

## Touchpad Gestures

- Single-finger move: relative cursor movement with acceleration + smoothing.
- Single-finger tap: left click.
- Double-tap and hold: drag (release to drop).
- Two-finger vertical swipe: scroll.

## Settings

- Cursor size scale, alpha, and auto-hide delay.
- Touchpad base gain, acceleration, speed threshold, jitter, smoothing.
- Drag boost and scroll step.

## Known Limitations

- Android 15 only.
- Requires external display support for activities on secondary displays.
- Some target apps may block or fail to launch on a secondary display.

## License

See `LICENSE`.
