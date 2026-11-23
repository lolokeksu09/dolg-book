#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/build
gradle -p android assembleDebug --no-daemon
APK=android/app/build/outputs/apk/debug/app-debug.apk
PACKAGE=dev.ventry.raschet.nativev031
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
sleep 4

dump_ui() {
  adb shell uiautomator dump /sdcard/raschet-ui.xml >/dev/null
  adb exec-out cat /sdcard/raschet-ui.xml > android/build/native-ui.xml
}
tap_text() {
  local target="$1" coords attempt
  # Off-screen practice actions require scrolling on compact displays.
  for attempt in 1 2 3 4; do
    dump_ui
    if coords=$(python3 - "$target" <<'PY'
import re, sys, xml.etree.ElementTree as ET
target=sys.argv[1]
for node in ET.parse("android/build/native-ui.xml").iter("node"):
    if node.attrib.get("text")==target or node.attrib.get("content-desc")==target:
        nums=[int(s) for s in re.findall(r"\d+",node.attrib.get("bounds",""))]
        if len(nums)==4 and nums[3] > nums[1]:
            print(f"{(nums[0]+nums[2])//2} {(nums[1]+nums[3])//2}")
            sys.exit(0)
sys.exit(1)
PY
    ); then
      adb shell input tap $coords
      return 0
    fi
    adb shell input swipe 540 1490 540 730 300
    sleep 0.35
  done
  echo "Cannot find tap target after scrolling: $target" >&2
  return 1
}
dump_ui
grep -Fq 'Записывайте долги быстро' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-1.png
tap_text 'Добавить пример клиента'
sleep 0.4
dump_ui
grep -Fq 'Клиент добавлен · остаток 0 ₽' android/build/native-ui.xml
tap_text 'Записать долг · 1 200 ₽'
sleep 0.4
dump_ui
grep -Fq 'Долг: 1 200 ₽' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-1-practice.png
tap_text 'Далее'
sleep 1
dump_ui
grep -Fq 'Принимайте оплаты без путаницы' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-2.png
tap_text 'Внести 400 ₽'
sleep 0.4
dump_ui
grep -Fq '800 ₽' android/build/native-ui.xml
tap_text 'Погасить полностью'
sleep 0.4
dump_ui
grep -Fq 'Долг погашен.' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-2-practice.png
tap_text 'Далее'
sleep 1
dump_ui
grep -Fq 'Ваша книга всегда под рукой' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-3.png
tap_text 'Показать состав JSON'
sleep 0.4
dump_ui
grep -Fq 'Клиенты и контакты' android/build/native-ui.xml
if ! grep -Fq 'Здесь файл не создаётся' android/build/native-ui.xml; then
  adb shell input swipe 540 1490 540 730 300
  sleep 0.35
  dump_ui
fi
grep -Fq 'Здесь файл не создаётся' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-3-practice.png
tap_text 'Начать работу'
sleep 2
dump_ui
grep -Fq 'Добавить первого клиента' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-clients.png
tap_text 'Главная'
sleep 1
dump_ui
grep -Fq 'ОБЩАЯ ЗАДОЛЖЕННОСТЬ' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-home.png

# Reopen: the onboarding must not be shown twice.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
sleep 3
dump_ui
if grep -Fq 'Записывайте долги быстро' android/build/native-ui.xml; then
    echo 'Onboarding shown again after completion'
    exit 1
fi
grep -Fq 'ОБЩАЯ ЗАДОЛЖЕННОСТЬ' android/build/native-ui.xml

adb logcat -d -s AndroidRuntime:E > android/build/android-runtime-errors.txt
if grep -F 'FATAL EXCEPTION' android/build/android-runtime-errors.txt; then
  echo 'Android crash observed during onboarding smoke test'
  exit 1
fi
echo 'NATIVE_ONBOARDING_SMOKE_PASS'
