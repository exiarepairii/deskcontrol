# DeskControl dev notes

## Handover essentials (read first)
- The UI is intentionally calm, low-contrast, and macOS-inspired. Avoid adding bright colors or high-contrast elements unless explicitly requested.
- Keep primary action singular and visually dominant; all secondary actions should share one neutral style.
- Touchpad page has special OLED treatment: full black background in dark mode, with a custom drawable for the touchpad area.
- Main screen avoids diagnostics/detail info; that belongs in Diagnostics only (currently removed from home).
- Cursor size/opacity/speed are now inline sliders; keep values snapped to slider step size to avoid crashes.

## Current UX conventions
- Main screen hierarchy: status row + contextual display selector, primary action, secondary actions.
- Display selector uses 1-based labels (Display 1/2/3) and shows resolution as the secondary line.
- Touchpad hints: when touchpad is active, hint text should dim; inactive should be brighter to draw attention.
- Accessibility gating for touchpad happens inside Touchpad screen, not on the home screen.

## Visual system and theming
- Colors are defined in `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml`.
- Accent color is #7FB7AE. Use it only for primary actions; secondary controls should remain neutral.
- Light mode uses soft gray backgrounds and translucent surfaces; avoid pure white.
- Dark mode uses graphite backgrounds; only the Touchpad screen is pure black for OLED power saving.
- Segmented control uses subtle track/highlight drawables (`display_selector_track.xml`, `display_selector_highlight.xml`).

## Settings screen rules
- Use inline controls (switches, sliders, simple previews). Avoid dialogs for simple values.
- Sliders must snap to step size to prevent Material Slider validation crashes.
- Show dependent settings only when their parent switch is enabled (e.g., hide delay only when auto-hide is ON).

## Key files to understand
- Touchpad input and focus: `app/src/main/java/com/deskcontrol/TouchpadActivity.kt`
- Touchpad processing: `app/src/main/java/com/deskcontrol/TouchpadProcessor.kt`
- Accessibility injection + cursor overlay: `app/src/main/java/com/deskcontrol/ControlAccessibilityService.kt`
- Settings persistence: `app/src/main/java/com/deskcontrol/SettingsStore.kt`
- Settings UI: `app/src/main/java/com/deskcontrol/SettingsActivity.kt`
- Main screen UI: `app/src/main/res/layout/activity_main.xml`
- Touchpad UI: `app/src/main/res/layout/activity_touchpad.xml`

## Common pitfalls
- Material Slider will crash if a stored value is not aligned to `valueFrom + n * stepSize`.
- Display selection must stay 1-based in UI; never expose system displayId directly.
- Do not reintroduce diagnostics/details onto the main screen without confirmation.
- Touchpad background colors are overridden by day/night drawables; use `drawable-night/touchpad_area_bg.xml` for OLED mode.

## Release checklist (lightweight)
- Update `app/build.gradle` versionCode/versionName.
- Add entry to `CHANGELOG.md`.
- Sanity-check dark/light mode visuals, especially Touchpad OLED black mode.

## Updating devnotes.md tips
- Start with a short “Handover essentials” summary; keep it accurate and current.
- Add only decisions and constraints that affect future work (avoid transient logs).
- Prefer bullet points, grouped by topic; keep each bullet to a single idea.
- When changing behavior, also update the related pitfalls/conventions here.
- Link to exact files/paths for anything non-obvious to reduce onboarding time.

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
