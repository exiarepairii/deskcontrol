# DeskControl

DeskControl turns your phone into a touchpad and motion mouse for a single app
running on a wired external display. It supports Android 11+ and targets
Android 16 (API 36). It uses an AccessibilityService to render the cursor and
inject input.

[中文说明](README_zh.md)

## Highlights

- Launch any installed app onto a wired external display.
- Control the external app with a phone touchpad (move, click, drag).
- Per-display cursor overlay with auto-hide and tuning controls.
- Clean teardown when the external display disconnects.

## Requirements

- Android 11+ (minSdk 30).
- Android 16 target SDK (API 36).
- Wired Type-C external display.
- Accessibility service enabled (required for cursor and input injection).

## Quick Start

1. Connect the wired external display.
2. Open Touchpad, review the accessibility disclosure, and enable the
   DeskControl accessibility service.
3. Pick an app to launch on the external display.
4. Open Touchpad and control the external app.

## Touchpad Usage

- Move: slide one finger in the touchpad area.
- Click: tap once in the touchpad area.
- Drag: touch and hold, then slide (vibration confirms).
- Auto-dim: after 10s inside the touchpad area, the screen dims. It restores
  when you tap outside the touchpad area or leave the screen.
- Back: when the touchpad area is active, Back is forwarded to the external app.
- Exit: tap the top-left back arrow, or tap outside the touchpad area and then press Back.

## Build

Two distribution flavors are maintained from the same source tree:

- `play`: package `com.suspace.deskcontrol`, Google Play Billing, supporter icons,
  no Shizuku.
- `direct`: package `com.deskcontrol`, optional Shizuku enablement, no Billing or
  alternate icon assets.

```bash
./gradlew assemblePlayDebug
./gradlew assembleDirectDebug
```

Install the APK:

```bash
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
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
- `ControlAccessibilityService`: cursor overlay, user-directed gesture injection,
  external-window focus, and Back forwarding.
- `CursorOverlayView`: cursor rendering and animation.
- `DiagnosticsActivity`: status and recent failure history.

## Permissions and Notes

- Uses `AccessibilityService` for user-directed gesture injection, external-window
  focus, Back forwarding, and the Motion Mouse calibration key.
- Cursor overlay uses `TYPE_ACCESSIBILITY_OVERLAY` and is non-touchable.
- The overlay is attached to the external display via `createWindowContext`.
- See `docs/google-play-release.md` for the Play Console declaration checklist.

## Limitations

- Android 11+ only.
- Requires device support for secondary-display activities.
- Some apps do not allow launch on a secondary display.

## License

See `LICENSE`.
