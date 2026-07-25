# DeskControl dev notes

## Handover essentials (read first)
- The UI is intentionally calm, low-contrast, and macOS-inspired. Avoid adding bright colors or high-contrast elements unless explicitly requested.
- Keep primary action singular and visually dominant; all secondary actions should share one neutral style.
- Touchpad and Motion Mouse share the OLED control-surface treatment: hidden status bar, full black background in dark mode, and the custom touchpad-area drawable.
- Main screen avoids diagnostics/detail info; that belongs in Diagnostics only (currently removed from home).
- Cursor size/opacity/speed are now inline sliders; keep values snapped to slider step size to avoid crashes.
- All user-visible text must live in string resources (English default + zh-rCN).
- Touchpad and Motion Mouse share `ControlSurfaceWindowPolicy` for keep-screen-on and per-window auto-dim; external-display input focus must not cancel dimming while the phone control page remains resumed. Brightness restores on control deactivation, `onPause`, and `onStop`.
- Touchpad and Motion Mouse share `ScreenBlackoutController` for the full-black swipe-to-unlock screen; Motion Mouse keeps sensor aiming active while blacked out.
- Touchpad and Motion Mouse share `ControlSurfaceGestureController` for taps, drags/swipes, two-finger scrolling, cancellation, and pointer-transition suppression; keep input-mode differences inside that controller.
- Motion Mouse touch is gesture-only and relative: the ray owns the cursor position, taps and native long presses begin at the current cursor, and swipe/long-press-drag deltas map 1:1 from that fixed cursor anchor. These gestures must never move the visible cursor, and a phone touch position must never map directly to an external-display position.
- Motion Mouse reuses the regular Touchpad content geometry. Its tuning panel is collapsed by default, expands below the pad, and scrolls internally.
- Motion Mouse has no dynamic status row above the control area; state changes must not resize the pad.
- Motion Mouse sensor axes are remapped for the phone display rotation; do not restore a portrait-only activity lock. Cursor movement uses intrinsic rotation relative to the calibrated phone plane: local Z controls horizontal movement and local X controls vertical movement, with no absolute world yaw/pitch mapping.
- Motion Mouse tuning defaults are 20 degrees horizontal coverage, 10 degrees vertical coverage, and 0.5 smoothing; keep the existing update interval and dead-zone defaults unchanged.
- All five Motion Mouse feel sliders persist immediately, reload through `SettingsStore`, and must remain clamped and aligned to their Material Slider steps.

## Current UX conventions
- Main screen hierarchy: status row + contextual display selector, primary action, secondary actions.
- Display selector uses 1-based labels (Display 1/2/3) and shows resolution as the secondary line.
- Touchpad and Motion Mouse place the left-aligned activation/Back-forwarding instruction above the control area with an 8dp top inset. The centered in-area copy starts with the current mode, leaves one blank line, then puts each mode-specific gesture instruction on its own line; use an English colon followed by a space in these labels, and when active, only the in-area hint dims.
- Accessibility gating for touchpad happens inside Touchpad screen, not on the home screen.
- Touchpad accessibility gate: manual settings button is primary; Shizuku is secondary and should explain itself when unavailable.
- Keep-screen-on toggle defaults to ON; it only applies while Touchpad/Motion Mouse/Host are visible.
- Control-surface auto-dim uses a 10s delay and never increases brightness while the control surface remains active.
- Touchpad and Motion Mouse share the same activation contract: touching the control area activates back forwarding and auto-dim; touching page controls deactivates both and restores brightness.
- Touchpad and Motion Mouse expose a destination-labeled mode switch immediately left of Screen off in the toolbar; switching finishes the current control page so only one mode remains in the back stack.
- Touchpad and Motion Mouse default control pages are intentionally the same visual shell: the shared Touchpad title, fixed-width destination-labeled switch action with swap icon, Screen off/overflow actions, padding, and equal-weight control area must remain identical; only the centered help copy and necessary switch destination label differ by mode.
- Motion Mouse calibration remains automatic on entry and manually available through the Volume Down hold so it does not add a mode-specific toolbar control.
- Touchpad interaction settings shared with Settings and all Motion Mouse tuning controls use `SettingsStore` as their source of truth; control pages refresh them in `onResume` so both entry points stay synchronized.
- The home screen exposes a single Touchpad entry with no separate Motion Mouse action. That entry and successful app selection open the last-used control surface, defaulting to Touchpad when no history exists; entering Touchpad or Motion Mouse persists the mode selected through the toolbar switch.
- Automatic external-display focus recovery defaults to enabled; preserve an existing stored user choice when loading settings.
- Control-surface activation depends on configured accessibility plus an external display, not the transient accessibility-service singleton; Motion Mouse direct touch must not wait for a rotation-sensor sample.
- Motion Mouse auto-calibration does not activate control mode; direct touch, manual calibration, Volume Down calibration, and blackout explicitly activate it.
- Motion Mouse haptic feedback is enabled by default, persisted, and applies to calibration, clicks, and drag start.
- Motion Mouse one-handed quick calibration uses a 600ms Volume Down hold while the page is resumed; the accessibility service forwards the key so calibration still works when the external app owns input focus. A short press must still lower media volume once on key release, and the key handler must be cleared in `onPause`.
- External-display control tutorials are blocking, interactive practice layers: move the real cursor into a target, click and long-press real buttons, drag a card, scroll a practice page, and (in Motion mode) calibrate. There is no welcome page or timeout; the darker accessibility overlay intercepts practice gestures so the projected app is unaffected, and only the current task can advance.
- Motion Mouse tuning lives in the toolbar overflow menu because it is an infrequent control; keep calibration and blackout directly accessible.
- Touchpad and Motion Mouse share `ControlSurfaceBackController` and `AccessibilityGateController`; do not fork back-forwarding or Shizuku behavior between pages.
- Touchpad and Motion Mouse share the post-accessibility app-selection prompt through `ControlSurfaceOnboardingController`; it runs only after the intro is dismissed, remains cancelable, and must not re-prompt during the same page entry.
- Dock reveal uses a small bottom-edge band because Motion Mouse smoothing converges on the display edge and cannot reliably overshoot it.

## Visual system and theming
- Colors are defined in `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml`.
- Accent color is #7FB7AE. Use it only for primary actions; secondary controls should remain neutral.
- Light mode uses soft gray backgrounds and translucent surfaces; avoid pure white.
- Dark mode uses graphite backgrounds; only the Touchpad screen is pure black for OLED power saving.
- Segmented control uses subtle track/highlight drawables (`display_selector_track.xml`, `display_selector_highlight.xml`).
- Cursor defaults to white with a subtle shadow; outline swaps (white cursor -> black outline, black cursor -> white outline).

## Settings screen rules
- Settings uses a Pixel/Android-style overview plus seven category subpages: Appearance & language, Display, Dock, Touchpad, Motion Mouse, Cursor, and Developer.
- `SettingsActivity` owns the toolbar and Fragment back stack. Category UI is built from the reusable Material rows in `SettingsUi.kt`; keep category titles identical between overview rows and detail toolbars.
- Keep the overview to grouped navigation rows with live summaries. Detailed switches and sliders belong on their category page rather than returning to a single long settings screen.
- On wide screens, settings content is centered and capped at 680dp.
- Use inline controls (switches, sliders, simple previews). Avoid dialogs for simple values.
- Sliders must snap to step size to prevent Material Slider validation crashes.
- Slider ranges live in `SettingsSliderRanges`; Settings fragments, control-page tuning panels, and `SettingsStore` loading/setters must all use those shared definitions. Keep tuned defaults unchanged, leave useful adjustment room on both sides when the value domain permits it, and prefer a manageable number of discrete stops.
- Show dependent settings only when their parent switch is enabled (e.g., hide delay only when auto-hide is ON).
- Theme and language use segmented toggle groups (system/dark/light, system/en/zh-CN).
- Keep-screen-on and control-surface auto-dim live together in the Display section with their summaries.

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
- Never recapture the original window brightness while a dim session is active; repeated display/state callbacks must preserve the first restore target.
- Cursor rendering doubles the former computed base size after applying the existing 0.5–3.0 scale and 10–26 px clamp, so every saved scale—including 1.0—has exactly twice its previous actual size.

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

## Practical engineering lessons (scroll/input)
- Keep two-finger scroll implementations isolated: legacy step-based mode and direct gesture mode now use separate controllers (`LegacyScrollController`, `DirectScrollController`) and are orchestrated by `TouchpadActivity`; avoid mixing state between modes.
- Anti-misfire for two-finger exit is handled in `TouchpadActivity` via `suppressSingleUntilUp`; when touching this logic, verify no click/cursor move can occur between `ACTION_POINTER_UP` and final `UP/CANCEL`.
- Legacy mode tuning has two independent knobs:
  - user knob: “Step trigger distance” (`touchpadScrollStepDp`) controls how much finger travel is needed per emitted step;
  - injection knob: swipe distance/duration constants in `ControlAccessibilityService.performScrollStep` control per-step visual travel (used to satisfy pull-to-refresh thresholds).
- Direct mode tuning has two independent knobs:
  - “Direct gesture gain” controls mapped delta scaling;
  - “Direct gesture step length” (`touchpadDirectScrollStepDp`) controls chunk length per injected segment.
  Keep both exposed in Settings and clearly scoped to direct mode only.
- For direct mode stability, never emit tiny or boundary-clamped gesture fragments; short fragments are frequently interpreted by target apps as taps.
- Cursor hotspot alignment should be tuned by `CURSOR_TIP_FRACTION_X/Y` in `ControlAccessibilityService`; adjust in small increments and validate against small tap targets.
- Any slider-backed setting must be clamped/snap-aligned in both `SettingsActivity` (UI snap) and `SettingsStore` (persist clamp) to prevent `Slider` crashes on reopen.
- Continued control-surface gesture strokes must be dispatched serially; coalesce pending touch points while a gesture segment is in flight and always send a terminal continuation on cancel.
- Keep Settings wording mode-specific and explicit (“default two-finger” vs “direct gesture experimental”) and disable irrelevant controls when the other mode is active.
- `settings_preferences.xml` is not part of runtime settings flow; current settings are code-driven in `SettingsActivity` + `activity_settings.xml`.

## Recent changes (touchpad-blind-ops)
## Recent changes (1.1.4)
- Accessibility: optional Shizuku flow to auto-enable service with manual fallback (Touchpad gate).
- Settings: add Touchpad section header and section dividers; titles bolded for legibility.
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
- Shizuku: provider authority must be `${applicationId}.shizuku` and `android:exported="true"` or provider lookup fails.
- Shizuku: `newProcess` is private in the API; use reflection and guard with try/catch around `checkSelfPermission()`.
- Drag uses accessibility gestures with short segments; tuning is in `dragStartDurationMs` and `dragSegmentDurationMs`.
- Cursor alpha is controlled via view alpha (paint stays opaque).
