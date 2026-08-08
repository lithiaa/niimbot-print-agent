#!/bin/sh

# Niimbot Print Agent - Build Instructions
# ========================================

# REQUIRED:
# 1. Android Studio (Ladybug or newer) OR Android SDK + Java 17
# 2. Android SDK Platform 34
# 3. Android SDK Build-Tools 34.0.0

## Option A: Build with Android Studio (RECOMMENDED)
# 1. Open project: File → Open → select niimbot-print-agent folder
# 2. Wait for Gradle sync (downloads dependencies, ~2-5 min)
# 3. Build → Build Bundle(s)/APK(s) → Build APK(s)
# 4. APK located at: app/build/outputs/apk/debug/app-debug.apk

## Option B: Build with command line
# 1. Set ANDROID_HOME:
#    export ANDROID_HOME=$HOME/Android/Sdk
# 2. Set local.properties:
#    echo "sdk.dir=$ANDROID_HOME" > local.properties
# 3. Build:
#    ./gradlew assembleDebug
# 4. APK: app/build/outputs/apk/debug/app-debug.apk

## INSTALL TO TABLET:
# 1. Enable Developer Options on tablet
#    Settings → About → tap "Build Number" 7x
# 2. Enable USB Debugging
#    Settings → Developer Options → USB Debugging ON
# 3. Connect tablet via USB
# 4. Install:
#    adb install app/build/outputs/apk/debug/app-debug.apk

## FIRST RUN:
# 1. Open app → Settings tab
# 2. Grant Bluetooth + Location permissions (Android 12+: Nearby Devices)
# 3. Go to Printer tab → Scan Devices
# 4. Select "NIIMBOT B1 Pro" from list
# 5. Tap Test Print to verify
# 6. Note the server port (default 8080)

## CLOUD POS INTEGRATION:
# The app exposes REST API:
#   POST http://<tablet-ip>:8080/print
#   Body: multipart/form-data
#     - file: label.png (optional, raw bitmap)
#     - OR JSON fields: nama, hargaJual, sku, stok, satuan, qty
#
#   GET http://<tablet-ip>:8080/health  → health check
#   GET http://<tablet-ip>:8080/status  → full status
#   POST http://<tablet-ip>:8080/test-print

# For LAN-only (same WiFi): use tablet IP directly
# For cloud access: use Tailscale app on tablet (get tailnet IP)

## TROUBLESHOOTING:
# - "App won't start" → check logcat: adb logcat -s PrintService
# - "No devices found" → ensure printer is ON, nearby, Bluetooth ON
# - "Print failed" → check paper, cover closed, printer battery
# - "Port already in use" → change port in Settings tab