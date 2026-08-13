# Changelog

## 1.3.20
- External display selection: automatically ignore an unavailable untrusted recording display and switch to an active system-approved display when one appears, while retaining a lone active legacy display as a compatibility fallback.
- Display lifecycle: suspend display-scoped controls while a selected HDMI display sleeps, then rebuild a fresh bounded session when it wakes without allowing stale gestures or retries to return.
- App launch: use the exact launcher component and the production direct-display path only; remove the A/B/C phone-prewarm compatibility test from Home and from persisted launch behavior.
- Diagnostics: record display trust, policy probes, Xiaomi projection components, target-app capabilities, and automatic selection decisions, and allow the complete report to be saved through the system file picker.
- Accessibility: distinguish configured, connected, and ready states so controls only run after the service and current display session are both available.
- Tutorial lifecycle: restore the cursor's previous auto-hide behavior when an external display sleeps or disconnects during the interactive tutorial.

## 1.3.19-launch-test
- Display lifecycle: treat a present non-ON display as suspended, tear down its display-scoped controls once, and recreate a fresh generation when it returns to ON even if its display ID is unchanged.
- Display selection: keep trusted or policy-approved HDMI selected while it sleeps, while still excluding suspended untrusted recording displays that both launch-policy probes explicitly deny.
- Accessibility: keep Debug control Activities, the service, and the device-test receiver in one process; protect the ADB gesture receiver with the system DUMP permission; distinguish configured, connected, and ready states in diagnostics and require a ready display session for actual controls.
- Input recovery: keep overlay attach retries bounded despite unrelated display callbacks, cancel display-scoped scroll state on suspend, and ignore late gesture callbacks from an older session generation.

## 1.3.18-launch-test
- Display selection feedback: show a one-time message when DeskControl ignores an unusable display while keeping a working one, or when every detected external display is currently unavailable.
- Diagnostics: retain only the latest display-selection notice until Home consumes it, while recording queued, superseded, cleared, and consumed states in the saved log.

## 1.3.17-launch-test
- External display selection: exclude invalid and powered-off recording displays, compare every public non-default display, and automatically move to a system-approved active display when one appears.
- Compatibility: keep a lone active legacy or MiPlay display as a fallback when no display passes the generic launch-policy probe, preserving OEM and privileged launch paths.
- Diagnostics: record raw versus selectable displays, policy and MediaRoute signals, exclusion reasons, and every automatic or manual selection decision.

## 1.3.16-launch-test
- External display: make disconnect cleanup and reconnect initialization one reusable session lifecycle, retry transient overlay attach failures without losing the target, and report connected only after the cursor overlay is actually attached.
- Diagnostics: add fresh ROM/build, display trust and mode, selected system setting, Xiaomi projection component, USB/media-route, target-app version, and direct-build Shizuku state snapshots for normal-versus-failing Xiaomi comparisons.
- Display diagnostics: print flags as unsigned eight-digit hex, decode `TRUSTED` and other stable policy bits, preserve unknown OEM bits, and compare standard versus `allowEmbedded` Activity preflight without using hidden APIs.
- Logging: retain the last connected external-display snapshot after disconnect and deduplicate unchanged display callbacks so brightness events cannot evict launch evidence before export.

## 1.3.15-launch-test
- Compatibility test: replace the ineffective external proxy with two phone-prewarm variants. B retries the warmed launcher task on the external display; C retries with `MULTIPLE_TASK` to force a separate external task.
- Launch flow: keep phone prewarm and external handoff as separate outcomes, persist the pending second-tap flow for ten minutes, and never report an accepted API call as a verified external launch.
- Diagnostics: correlate both stages with one flow ID, record target task/embedding capabilities and decoded intent flags, compare external-display preflight before and after phone prewarm, and sample phone/external windows from dispatch through 120 seconds without treating a slow confirmation as final failure.

## 1.3.14-launch-test
- Compatibility test: add selectable A/B/C app-launch strategies for direct display launch, `LauncherApps` display launch, and launch from an external-display activity.
- Diagnostics: correlate each launch attempt across the selected strategy, proxy activity, source and target displays, display-start preflight, resolved component, sanitized exceptions, and a delayed external-window observation that detects silent system blocking; mirror persistent diagnostics to the `DeskControlDiag` Logcat tag for ADB capture.
- Diagnostics: add a Save action that opens the system document picker in Download and exports the complete report as a UTF-8 text file without storage permissions.

## 1.3.13
- Interactive tutorials: keep the external volume and hold HUD above the tutorial scrim so calibration, lock, and unlock progress remain visible while practicing hardware-key shortcuts.

## 1.3.12
- Motion Mouse: after holding Volume Up to unlock blackout, immediately recalibrate from the phone's current pose after restoring control-area focus, preventing locked-screen movement from carrying a stale aiming baseline into the unlocked session.

## 1.3.11
- Control surfaces: after holding Volume Up to unlock blackout, automatically focus and reactivate the Touchpad or Motion control area and warm up external-display Back forwarding.

## 1.3.10
- Volume shortcuts: reduce accidental calibration and blackout triggers by increasing the shared hold threshold from 600ms to 660ms and requiring the full app-defined duration instead of accepting the earlier system long-press flag.
- External HUD: delay circular hold feedback for 120ms so quick volume adjustments show only the volume indicator instead of flashing the calibration or lock ring.

## 1.3.9
- Interactive tutorials: both Touch and Motion modes now require a real Volume Down hold for cursor centering or calibration, followed by real Volume Up holds to lock and unlock the phone blackout screen.
- Tutorial architecture: keep shared cursor, gesture, volume, blackout, and completion steps in one flow while preserving mode-specific drag, calibration, and scrolling instructions.

## 1.3.8
- Control surfaces: share Volume Up/Down handling, hold timing, external-display HUD feedback, and blackout toggling between Touch and Motion modes.
- Touch mode: show the projected volume HUD for short presses, center the cursor after holding Volume Down, and lock or unlock blackout after holding Volume Up.

## 1.3.7
- External display: add a minimal Material volume icon beneath the animated volume bar.

## 1.3.6
- External display: show an animated white volume indicator for short Volume Up and Volume Down presses in Motion Mouse mode.
- Motion Mouse: show circular hold progress with a calibration icon for Volume Down calibration and place the cursor precisely at the ring center when calibration completes.
- Screen blackout: hold Volume Up to show open-source Material lock or unlock icons on the external display, toggle the phone blackout state, and hide the projected cursor while blacked out.
- Motion Mouse gestures: keep forwarding movement after a pause or direction reversal and serialize a following touch until the previous injected gesture has ended.

## 1.3.5
- Screen blackout: ignore repeated built-in display change callbacks when no external display is connected, preventing the blackout overlay from closing after a tap.
- Diagnostics: persist recent logs across process restarts and record blackout touch, hint animation, display, and activity lifecycle events.

## 1.3.4
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
