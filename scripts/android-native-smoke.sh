#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/build
gradle -p android assembleDebug --no-daemon -PraschetOrigin=https://raschet.135-106-227-179.sslip.io
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n dev.ventry.raschet.nativepreview/dev.ventry.raschet.MainActivity
sleep 6
adb shell uiautomator dump /sdcard/raschet-ui.xml
adb exec-out cat /sdcard/raschet-ui.xml > android/build/native-ui.xml
grep -F 'РАСЧЁТ' android/build/native-ui.xml
grep -F 'ЗАДОЛЖЕННОСТЬ' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-home.png
adb logcat -d -s AndroidRuntime:E > android/build/android-runtime-errors.txt
if grep -F 'FATAL EXCEPTION' android/build/android-runtime-errors.txt; then
  echo 'Android crash observed during cold startup'
  exit 1
fi
echo 'NATIVE_HOME_SMOKE_PASS'
