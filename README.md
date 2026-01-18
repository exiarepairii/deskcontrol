# DeskControl

DeskControl turns your phone into a touchpad and keyboard for a single app
running on a wired external display. It targets Android 11+ and uses an
AccessibilityService to render the cursor and inject input.

[中文说明](README_zh.md)

## Highlights

- Launch any installed app onto a wired external display.
- Control the external app with a phone touchpad (move, click, drag).
- Per-display cursor overlay with auto-hide and tuning controls.
- Clean teardown when the external display disconnects.

## Requirements

- Android 11+ (minSdk 30).
- Wired Type-C external display.
- Accessibility service enabled (required for cursor and input injection).

## Quick Start

1. Connect the wired external display.
2. Launch DeskControl and enable the accessibility service if prompted.
3. Pick an app to launch on the external display.
4. Open Touchpad and control the external app.

## Touchpad Usage

- Move: slide one finger in the touchpad area.
- Click: tap once in the touchpad area.
- Slide & drag: double-tap, then slide (vibration confirms).
- Auto-dim: after 10s inside the touchpad area, the screen dims. It restores
  when you tap outside the touchpad area or leave the screen.
- Back: when the touchpad area is active, Back is forwarded to the external app.
- Exit: tap the top-left back arrow, or tap outside the touchpad area and then press Back.

## Build

```bash
./gradlew assembleDebug
```

Install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Settings

- Cursor size, opacity, color, and auto-hide delay.
- Touchpad sensitivity, acceleration, jitter, smoothing, and scroll step.
- Keep screen on while using touchpad.
- Auto-dim touchpad after 10 seconds (per-window brightness only).

## Project Layout

- `DisplaySessionManager`: external display tracking and selection.
- `AppLauncher`: launch routing and failure diagnostics.
- `TouchpadActivity`: touchpad UI and input logic.
- `ControlAccessibilityService`: cursor overlay and gesture/text injection.
- `CursorOverlayView`: cursor rendering and animation.
- `DiagnosticsActivity`: status and recent failure history.

## Permissions and Notes

- Uses `AccessibilityService` for gesture injection and `ACTION_SET_TEXT`.
- Cursor overlay uses `TYPE_ACCESSIBILITY_OVERLAY` and is non-touchable.
- The overlay is attached to the external display via `createWindowContext`.

## Limitations

- Android 11+ only.
- Requires device support for secondary-display activities.
- Some apps do not allow launch on a secondary display.

## License

See `LICENSE`.
