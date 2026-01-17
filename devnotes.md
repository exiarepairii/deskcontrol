# DeskControl dev notes

## Recent changes (touchpad-blind-ops)
- Touchpad gestures: single tap, double-tap hold to drag, two-finger scroll.
- Touchpad processing: EMA smoothing + deadzone + acceleration curve.
- Focus model: back button is intercepted when touchpad active and forwarded via AccessibilityService.
- Cursor: external overlay, auto-hide on idle, wake on input, size scales with display.
- Settings: cursor size/alpha/auto-hide + touchpad tuning and drag boost.
- App picker: Android 15 package visibility fixed with `<queries>` and launcher intent filtering.
- Phone UI updated to Material 3 layout with consistent toolbars and spacing.
- Settings screen replaced by PreferenceFragment-based system settings style.

## Key files to revisit
- Touchpad input: `app/src/main/java/com/deskcontrol/TouchpadActivity.kt`
- Touchpad processing: `app/src/main/java/com/deskcontrol/TouchpadProcessor.kt`
- Accessibility injection + cursor: `app/src/main/java/com/deskcontrol/ControlAccessibilityService.kt`
- Settings persistence: `app/src/main/java/com/deskcontrol/SettingsStore.kt`
- Settings UI: `app/src/main/java/com/deskcontrol/SettingsActivity.kt`
- Settings preferences: `app/src/main/res/xml/settings_preferences.xml`
- App picker + search/sort: `app/src/main/java/com/deskcontrol/AppPickerActivity.kt`

## Gotchas
- `performGlobalAction(GLOBAL_ACTION_BACK)` may have random latency on device (OS-level).
- Drag uses accessibility gestures with short segments; tuning is in `dragStartDurationMs` and `dragSegmentDurationMs`.
- Cursor alpha is controlled via view alpha (paint stays opaque).
