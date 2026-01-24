# Changelog

## 1.1.2
- Touchpad: long-press drag latch with continuous two-finger scroll mode and anchor-based scrolling.
- Touchpad: add screen-off button and hide system bars on the touchpad page.
- Back forwarding: focus-aware diagnostics, external-display toasts, and improved window retrieval.
- Auto-dim: avoid brightening in dark environments; adjust dim range (1–15%) with new default (3%).

## 1.1.1
- Compatibility: fix Android 11 crashes when launching app picker or creating overlay contexts.
- Dock: improve recents filling logic and settings icon row for pinned apps.
- Settings: Dock and cursor preview behavior while adjusting controls.

## 1.1.0
- Dock: add settings to enable, scale, and pin three apps with default browser seeding.
- Dock: reorder pins/all apps/recents with a divider and improved scaling behavior.
- Settings: show Dock and cursor previews while adjusting controls.

## 1.0.3
- External display: add App Switch Bar overlay with favorites/recents and All Apps drawer.
- Touchpad: two-finger drag triggers the same behavior as double-tap drag (slower tuning).
- Scroll: inject scroll gestures directly for better compatibility.

## 1.0.2
- Display logging: expanded external display diagnostics and selection reasoning.
- Diagnostics: copy icon in the toolbar; logs entry moved under Developer section.
- Touchpad: first-use tips refined; brightness restore now follows touchpad focus.

## 1.0.1
- Compatibility: lower minSdk to Android 11 (API 30).

## 1.0.0
- Touchpad: auto-dim per-window after 10s with smooth animation and focus-loss restore.
- Settings: touchpad auto-dim toggle and minimum brightness slider grouped with keep-screen-on.
- Back: warm up the input pipeline on Touchpad entry to reduce first-back latency.

## 0.4.0
- Add full i18n resources for English and Simplified Chinese, removing hardcoded UI text.
- Add language and theme controls to Settings with system-follow options.
- Improve cursor visuals (white default, black/white outline swap, subtle shadow).
- Add keep-screen-on toggle (default on) that only applies during active control screens.
- Hide status bar on Touchpad screen to maximize usable area.

## 0.3.0
- Redesign home screen with modern hierarchy, status row, and streamlined actions.
- Add multi-display selector and refined display labeling on the main screen.
- Introduce macOS-inspired theming with softer accents and glass-like surfaces.
- Rebuild settings to inline controls (switches, sliders, previews) for faster tuning.
- Refresh touchpad UX with focused hints, gating, and OLED-friendly dark mode.

## 0.2.1-test
- Refresh phone UI to Material 3 layout with consistent toolbars and spacing.
- Replace settings screen with system-style preferences and simpler options.
- Improve cursor visuals (arrow, outline for white, auto-hide, speed scaling).
- Touchpad flow: launch app then open touchpad automatically.

## 0.2.0
- Fix accessibility overlay crash on enable by using a display-scoped window context.
- Improve text injection targeting and diagnostics logging.
- Remove send text and stop session UI per latest test feedback.
- Add diagnostics log buffer and display it on the diagnostics screen.
