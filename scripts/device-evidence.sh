#!/bin/sh
# Read-only evidence capture. Does not grant permissions or modify another app.
set -eu
cd "$(dirname "$0")/.."
mkdir -p test-results
report="test-results/device-$(date +%Y%m%d-%H%M%S).txt"
{
    printf 'UWB Pulse device evidence\n'
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.release
    adb shell getprop ro.build.version.sdk
    adb shell getprop ro.build.version.oneui
    adb shell getprop ro.build.display.id
    adb shell run-as dev.uwbpulse.monitor cat files/probe-journal.txt
} > "$report"
printf 'Saved %s\n' "$report"
