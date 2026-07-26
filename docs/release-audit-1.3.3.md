# DeskControl 1.3.3 release audit

Date: July 26, 2026

## Release identity

- Google Play application ID: `com.suspace.deskcontrol`
- Version code: `23`
- Version name: `1.3.3`
- Minimum SDK: Android 11 (API 30)
- Compile and target SDK: Android 16 (API 36)
- Upload artifact:
  `app/build/outputs/bundle/playRelease/app-play-release.aab`
- SHA-256:
  `955bfbd20be05b0b9621008fa395455e5cd0f2fc7c2f05651e5546a5ad48556d`

Do not upload the older
`deskcontrol-1.3.2-play-release.aab` file that remains in the generated build
directory.

## Confirmed implementation

- The accessibility disclosure describes external-window inspection, automatic
  focus, the focus-probe gesture, user-directed gestures, Back forwarding,
  calibration input, local diagnostics, and no transmission.
- Seven title taps enable review access without changing the cached or displayed
  purchase state.
- Review access permits Default, White, and Gold launcher icons while retaining
  the real purchase button and status.
- A failed or unavailable Google Play purchase query keeps the last confirmed
  local entitlement. Only a successful authoritative query can revoke it.
- The centered Touchpad and Motion Mouse instructions hide after auto-dim and
  reappear when control is deactivated and brightness is restored.
- The interactive tutorial button has standard click semantics; the Touchpad
  gesture implementation was not changed.
- The reviewer video URL remains an intentional placeholder.

## Build and package verification

- `./gradlew lint bundlePlayRelease assembleDirectRelease`: passed.
- Android Lint: 0 errors and 35 non-blocking warnings.
- Play AAB signature: verified.
- Merged Play manifest: version 23 / 1.3.3 / target SDK 36.
- Merged Direct manifest: version 23 / 1.3.3 / target SDK 36.
- Play AAB contains Billing and supporter-icon code and contains no Shizuku
  symbols.
- Direct APK contains Shizuku and contains no Billing, supporter entitlement,
  review-mode, or alternate-icon manager symbols.
- All business activities and the accessibility service are non-exported.
  Exported components are limited to launcher aliases, the permission-protected
  Shizuku provider in Direct, and library components protected by system or
  signature permissions.
- No nested-intent forwarding or mutable `PendingIntent` path was found.

## Device regression

Test device: Android 17 / API 37 emulator with a 720 × 720 virtual external
display.

- Installed Play debug package reports `targetSdk=36`.
- App launch, Home, Settings, supporter page, Touchpad, Motion Mouse, and app
  picker opened without an app crash.
- With Wi-Fi and mobile data disabled, local app enumeration and the control
  pages remained usable.
- Chrome launched successfully onto display 2 while DeskControl remained on
  display 0.
- Review mode showed “not purchased,” retained the purchase button, and allowed
  all three launcher-icon cards.
- Touchpad activation started auto-dim; after the delay the centered gesture
  copy was hidden. Tapping a page control deactivated the Touchpad, restored
  brightness, and showed the copy again.
- Test-only network and accessibility settings were restored afterward.

## Remaining release gates

1. Upload the new AAB to an internal testing track and exercise successful,
   cancelled, pending, already-owned, and restored purchases with a license
   tester. A sideloaded build cannot fully validate the production Play Billing
   account and product path.
2. Publish `site/privacy.html` through the website workflow and verify
   `https://exiarepairii.github.io/deskcontrol/privacy.html` before entering the
   URL in Play Console.
3. Replace the reviewer-video placeholder when the video is ready.
4. Keep review mode for the submission and any resubmission. Remove it in a
   later update only after the release has completed review.
5. The project has no automated unit or instrumentation test sources. This
   audit therefore combines build, Lint, package inspection, and manual device
   regression rather than claiming automated coverage.

