# Google Play release and declaration

## Build identity

- Flavor: `play`
- Application ID: `com.suspace.deskcontrol`
- Bundle command: `./gradlew bundlePlayRelease`
- One-time product ID: `supporter_icon_pack`
- Product type: non-consumable one-time product
- Suggested product name: `Supporter Icon Pack`

The Play Console app package must exactly match
`com.suspace.deskcontrol`. Upload only the `playRelease` bundle.

During the 1.3 testing cycle, increment the patch version for every generated
test bundle (`1.3.1`, `1.3.2`, `1.3.3`, ...), and increment `versionCode` at
the same time.

## Accessibility declaration

- Is this an accessibility tool? **No**
- Purpose: **App functionality**

Suggested English explanation:

> DeskControl turns the phone into a user-operated touchpad and motion mouse
> for apps running on a connected secondary display. AccessibilityService
> draws a cursor overlay on the secondary display, injects tap, drag and scroll
> gestures only in direct response to the user's real-time input, forwards
> user-initiated Back actions and the Volume Down calibration shortcut, and
> inspects the external-display window hierarchy only to identify and focus
> the target window. It does not autonomously initiate actions or transmit
> accessibility data.

The declaration video must show:

1. opening DeskControl;
2. the complete in-app prominent disclosure;
3. declining the disclosure;
4. triggering it again, accepting it, and manually enabling the service;
5. connecting a real external display;
6. launching an app and demonstrating cursor movement, click, drag, scroll,
   Back forwarding, and Motion Mouse calibration;
7. English captions or narration.

## Sign-in details / app access

Select **Yes** because review requires Accessibility settings, an external
display, and access to a paid one-time product.

Name:

`Accessibility, external display, and paid icons`

Leave username and password blank. Put this in the additional instructions:

> No sign-in is required. First open Touchpad, read and accept the in-app
> Accessibility disclosure, tap Open accessibility settings, select
> DeskControl, and enable it. The primary feature uses a connected secondary
> display. If hardware is unavailable: Settings > Support & app icon, tap the
> toolbar title 7 times, then open External-display demo. Review mode also
> unlocks both optional icons without purchase. No account or purchase is
> required. Hardware demo: [UNLISTED VIDEO URL]

Replace the final placeholder with a stable, reviewer-accessible video URL.

## App content answers

- Ads: **No**
- Government app: **No**
- Financial features: **No**
- Health features: **No**
- Category: **Tools**
- Target audience: **18 and over**, if that matches the intended store
  positioning; do not select child age groups.
- Data collected: **No**
- Data shared: **No**
- Account creation: **No**
- Data deletion: users clear app storage or uninstall; there is no server-side
  account or data.

The privacy-policy text is in `docs/privacy-policy.md`. Publish it at a stable
public HTTPS URL before completing the Play Console privacy-policy field.

## Billing setup and testing

1. Create and activate `supporter_icon_pack` in Play Console.
2. Add at least one active buy purchase option in every target country.
3. Upload a signed `playRelease` bundle to internal testing.
4. Add license testers.
5. Test successful, cancelled, pending, restored, and already-owned purchases.
6. Verify purchases are acknowledged and survive app restart/reinstall.
7. Verify default, white, and gold aliases switch correctly.

## Outside-Play distribution

Build with `./gradlew assembleDirectRelease`.

The `direct` flavor keeps application ID `com.deskcontrol` for upgrade
compatibility, includes Shizuku, and does not compile or package Google Play
Billing, supporter UI, review mode, or alternate icon assets.
