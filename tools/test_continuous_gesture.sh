#!/usr/bin/env bash
set -euo pipefail

DEVICE_SERIAL="${1:-emulator-5554}"
ADB=(adb -s "$DEVICE_SERIAL")
APP_PACKAGE="com.deskcontrol"
SERVICE_COMPONENT="$APP_PACKAGE/$APP_PACKAGE.ControlAccessibilityService"
ACTIVITY_COMPONENT="$APP_PACKAGE/$APP_PACKAGE.GestureContinuationTestActivity"
RECEIVER_COMPONENT="$APP_PACKAGE/$APP_PACKAGE.GestureTestCommandReceiver"
RUN_ID="$(date +%s)"

if [[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    echo "Refusing to run: $DEVICE_SERIAL is not an Android emulator." >&2
    exit 2
fi

./gradlew assembleDirectDebug
"${ADB[@]}" install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk

PREVIOUS_OVERLAY="$("${ADB[@]}" shell settings get global overlay_display_devices | tr -d '\r')"
PREVIOUS_SERVICES="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
PREVIOUS_ACCESSIBILITY="$("${ADB[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"

cleanup() {
    "${ADB[@]}" shell am force-stop "$APP_PACKAGE" >/dev/null 2>&1 || true
    if [[ "$PREVIOUS_OVERLAY" == "null" ]]; then
        "${ADB[@]}" shell settings delete global overlay_display_devices >/dev/null
    else
        "${ADB[@]}" shell settings put global overlay_display_devices "$PREVIOUS_OVERLAY"
    fi
    if [[ "$PREVIOUS_SERVICES" == "null" ]]; then
        "${ADB[@]}" shell settings delete secure enabled_accessibility_services >/dev/null
    else
        "${ADB[@]}" shell settings put secure enabled_accessibility_services "$PREVIOUS_SERVICES"
    fi
    "${ADB[@]}" shell settings put secure accessibility_enabled "$PREVIOUS_ACCESSIBILITY"
}
trap cleanup EXIT

"${ADB[@]}" shell settings put global overlay_display_devices 1280x720/240

DISPLAY_ID=""
for _ in {1..30}; do
    DISPLAY_ID="$("${ADB[@]}" shell cmd display get-displays -i |
        tr -d '\r' |
        awk '$1 != 0 { print $1; exit }')"
    [[ -n "$DISPLAY_ID" ]] && break
    sleep 0.1
done
if [[ -z "$DISPLAY_ID" ]]; then
    echo "Secondary display did not appear." >&2
    exit 1
fi

"${ADB[@]}" shell settings put secure enabled_accessibility_services "$SERVICE_COMPONENT"
"${ADB[@]}" shell settings put secure accessibility_enabled 1
"${ADB[@]}" shell am start --display "$DISPLAY_ID" \
    -n "$ACTIVITY_COMPONENT" \
    --es mode reset \
    --es run_id "$RUN_ID" >/dev/null

FOCUSED=""
for _ in {1..30}; do
    FOCUSED="$("${ADB[@]}" logcat -d -s GestureDeviceTest:I '*:S')"
    grep -q "run=$RUN_ID focus=true" <<<"$FOCUSED" && break
    sleep 0.1
done
if ! grep -q "run=$RUN_ID focus=true" <<<"$FOCUSED"; then
    echo "Test activity did not gain focus on display $DISPLAY_ID." >&2
    exit 1
fi

STATUS=""
for _ in {1..50}; do
    STATUS="$("${ADB[@]}" shell am broadcast \
        -a com.deskcontrol.test.STATUS \
        -n "$RECEIVER_COMPONENT" | tr -d '\r')"
    grep -q "result=1" <<<"$STATUS" && break
    sleep 0.1
done
if ! grep -q "result=1" <<<"$STATUS"; then
    echo "Accessibility service did not attach to display $DISPLAY_ID." >&2
    exit 1
fi

send_gesture_command() {
    local action="$1"
    shift
    local output
    output="$("${ADB[@]}" shell am broadcast \
        -a "$action" \
        -n "$RECEIVER_COMPONENT" \
        "$@" | tr -d '\r')"
    if ! grep -q "result=1" <<<"$output"; then
        echo "Gesture command failed: $action" >&2
        echo "$output" >&2
        exit 1
    fi
}

assert_activity_result() {
    local mode="$1"
    local assertion="$2"
    "${ADB[@]}" shell am start --display "$DISPLAY_ID" \
        -f 0x20000000 \
        -n "$ACTIVITY_COMPONENT" \
        --es mode "$mode" \
        --es run_id "$RUN_ID" >/dev/null
    sleep 0.1
    local logs
    logs="$("${ADB[@]}" logcat -d -s GestureDeviceTest:I '*:S')"
    if ! grep -q "run=$RUN_ID assertion=$assertion result=PASS" <<<"$logs"; then
        echo "Assertion failed: $assertion" >&2
        grep "run=$RUN_ID" <<<"$logs" >&2 || true
        exit 1
    fi
    echo "PASS: $assertion"
}

# Pause for 500 ms, then reverse slowly at 120 ms intervals without lifting.
send_gesture_command com.deskcontrol.test.START --ef x 640 --ef y 468
send_gesture_command com.deskcontrol.test.UPDATE --ef x 640 --ef y 288
sleep 0.5
for y in 308 328 348 368 388; do
    send_gesture_command com.deskcontrol.test.UPDATE --ef x 640 --ef y "$y"
    sleep 0.12
done
send_gesture_command com.deskcontrol.test.END
assert_activity_result assert_paused_reversal paused_reversal

"${ADB[@]}" shell am start --display "$DISPLAY_ID" \
    -f 0x20000000 \
    -n "$ACTIVITY_COMPONENT" \
    --es mode reset \
    --es run_id "$RUN_ID" >/dev/null
sleep 0.1

# End one gesture and immediately start a second gesture in another direction.
send_gesture_command com.deskcontrol.test.START --ef x 640 --ef y 468
send_gesture_command com.deskcontrol.test.UPDATE --ef x 640 --ef y 348
send_gesture_command com.deskcontrol.test.END
send_gesture_command com.deskcontrol.test.START --ef x 640 --ef y 468
send_gesture_command com.deskcontrol.test.UPDATE --ef x 740 --ef y 468
send_gesture_command com.deskcontrol.test.END
assert_activity_result assert_two_gestures two_gestures

echo "Continuous gesture device tests passed on display $DISPLAY_ID."
