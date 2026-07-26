# Changelog

## 1.3.3
- Android compatibility: target Android 16 (API 36) for the August 2026 Google Play requirement.
- Google Play Billing: keep the last confirmed supporter entitlement while offline, and restore the Default launcher icon only after Google Play successfully confirms that the entitlement is no longer owned.
- Google Play review access: unlock all launcher icons without changing or hiding the real purchase state and purchase action.
- Accessibility: restore standard click semantics for interactive tutorial buttons without changing the touchpad gesture surface.
- Control surfaces: hide the centered gesture instructions after auto-dim and show them again when brightness is restored.

## 1.3.2
- Google Play Billing: prevent duplicate connection attempts, wait for a usable product offer before enabling purchase, and add branch-specific error messages and diagnostics.
- Accessibility: clarify that control-page activation may automatically focus the external window and send a short focus-probe gesture.
- UI: align the Supporter Icons and Privacy Policy toolbars with the rest of the app and correctly handle edge-to-edge system insets.

## 1.3.1
- Google Play testing: rebuild the Play bundle with an incremented test version.

## 1.3.0
- Distribution: split Google Play (`play`) and outside-Play (`direct`) product flavors with separate package names and dependencies.
- Accessibility: add a required in-app prominent disclosure, reduce event scope, and remove unused text injection.
- Google Play: remove Shizuku, add a non-consumable Supporter Icon Pack, purchase restoration, default/white/gold launcher icons, and a hidden reusable review mode.
- Direct distribution: retain Shizuku while excluding Play Billing, supporter UI, and alternate icon assets.
- Review: add an on-device external-display interaction demo for reviewers without compatible display hardware.
- Packaging: keep all language resources in the App Bundle and disable backup/device transfer of local settings and diagnostics.

## 1.2.0
- Motion Mouse: add sensor-driven cursor control with rotation-aware aiming, quick Volume Down calibration, haptic feedback, and dedicated tuning controls.
- Control surfaces: unify Touch and Motion navigation, gesture handling, back forwarding, screen blackout, auto-dim, and keep-screen-on behavior.
- Onboarding: add an accessible first-run mode chooser, activation guidance, replay action, and blocking interactive lessons on the external display.
- Interactive lessons: practice cursor movement, clicking, long-pressing, dragging, scrolling, and Motion calibration without affecting the projected app.
- Settings: reorganize preferences into focused overview pages for appearance, display, Dock, Touchpad, Motion Mouse, cursor, and developer tools.
- Appearance: add alternate white and gold launcher icon previews and refreshed control/settings iconography.

## 1.1.5
- Scroll architecture: decouple legacy two-finger scrolling and direct gesture scrolling into separate controllers for easier maintenance.
- Touchpad settings: reorganize scroll settings into clear mode-based sections (default two-finger vs direct gesture experimental), with clearer naming and tuning guidance.
- Touchpad settings: add direct-gesture step-length slider to control per-segment injected travel independently from gain.
- Scroll behavior: improve anti-misfire handling around two-finger exit and direct gesture injection edge cases.
- Legacy scrolling: rebalance up/down travel and pull-to-refresh behavior for feed-style apps.
- Cursor: refine tip hotspot alignment to better match perceived pointer tip and tap location.

## 1.1.4
- Accessibility: optional Shizuku flow to auto-enable the service, with fallback to manual settings.
- Touchpad: accessibility gate refreshed with a primary manual button and Shizuku info for advanced users.
- Settings: add a Touchpad section header and subtle dividers; section titles are bold.

## 1.1.3
- Touchpad: revamped two-finger scroll tuning (natural scrolling, speed baseline, adaptive distance/velocity, micro-precision).
- Touchpad: toolbar layout refresh and hint copy simplification.
- Dock: hot-plug recovery, cursor overshoot and trigger tuning, drawer close button.
- Build: release signing via CI/local properties; ignore keystore files; warning cleanup.

## 1.1.2.post2
- Build: release signing via CI/local properties; ignore keystore files.
- Maintenance: address Kotlin/Gradle warnings.

## 1.1.2.post1
- External display: retry overlay attach after hot-plug to restore cursor/Dock.
- Touchpad: immersive top area on cutout devices; reduce system bar interference with edge back.
- Touchpad: add scroll speed control and natural scrolling toggle (default on).
- Touchpad: improve top spacing and keep gesture bar area consistent with touchpad background.
- Scroll: lower default speed, narrower range, and stop/flip behavior now tracks finger changes.

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
