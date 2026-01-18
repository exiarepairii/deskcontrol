# DeskControl dev notes

## Handover essentials (read first)
- The UI is intentionally calm, low-contrast, and macOS-inspired. Avoid adding bright colors or high-contrast elements unless explicitly requested.
- Keep primary action singular and visually dominant; all secondary actions should share one neutral style.
- Touchpad page has special OLED treatment: full black background in dark mode, with a custom drawable for the touchpad area.
- Main screen avoids diagnostics/detail info; that belongs in Diagnostics only (currently removed from home).
- Cursor size/opacity/speed are now inline sliders; keep values snapped to slider step size to avoid crashes.
- All user-visible text must live in string resources (English default + zh-rCN).
- Touchpad auto-dim is per-window only and must restore brightness only on focus loss (onPause/onStop).

## Current UX conventions
- Main screen hierarchy: status row + contextual display selector, primary action, secondary actions.
- Display selector uses 1-based labels (Display 1/2/3) and shows resolution as the secondary line.
- Touchpad hints: when touchpad is active, hint text should dim; inactive should be brighter to draw attention.
- Accessibility gating for touchpad happens inside Touchpad screen, not on the home screen.
- Keep-screen-on toggle defaults to ON; it only applies while Touchpad/Host are visible.
- Touchpad auto-dim uses a 10s delay and never increases brightness while the touchpad remains focused.

## Visual system and theming
- Colors are defined in `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml`.
- Accent color is #7FB7AE. Use it only for primary actions; secondary controls should remain neutral.
- Light mode uses soft gray backgrounds and translucent surfaces; avoid pure white.
- Dark mode uses graphite backgrounds; only the Touchpad screen is pure black for OLED power saving.
- Segmented control uses subtle track/highlight drawables (`display_selector_track.xml`, `display_selector_highlight.xml`).
- Cursor defaults to white with a subtle shadow; outline swaps (white cursor -> black outline, black cursor -> white outline).

## Settings screen rules
- Use inline controls (switches, sliders, simple previews). Avoid dialogs for simple values.
- Sliders must snap to step size to prevent Material Slider validation crashes.
- Show dependent settings only when their parent switch is enabled (e.g., hide delay only when auto-hide is ON).
- Theme and language use segmented toggle groups (system/dark/light, system/en/zh-CN).
- Keep-screen-on and touchpad auto-dim live together in the Display section with their summaries.

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
- Touchpad auto-dim should never restore brightness on input events; restore only on focus loss.

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
## Recent changes (1.0.2)
- Display: expanded diagnostics logging for display enumeration and selection.
- Diagnostics: copy icon in the toolbar; logs entry grouped under Developer.
- Touchpad: first-use tips clarified and brightness restore tied to touchpad focus.
## Recent changes (1.0.1)
- Compatibility: lowered minSdk to Android 11 (API 30).
## Recent changes (0.5.0)
- Touchpad: auto-dim after 10s with smooth animation and per-window brightness restore on focus loss only.
- Settings: auto-dim toggle and minimum brightness slider grouped with keep-screen-on copy updates.
- Back latency: warm-up on Touchpad entry to reduce first-back latency.
## Recent changes (0.4.0)
- Internationalization: all UI text moved to strings with English + zh-rCN resources.
- Settings: theme and language controls added; keep-screen-on toggle defaults to ON.
- Cursor visuals: white default, dynamic outline, subtle shadow.
- Touchpad: status bar hidden for full-height control area.

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
