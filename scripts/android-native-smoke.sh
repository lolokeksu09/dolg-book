#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/build
gradle -p android assembleDebug --no-daemon
APK=android/app/build/outputs/apk/debug/app-debug.apk
PACKAGE=dev.ventry.raschet.nativev031
adb install -r "$APK"
# Real instrumented SQLite tests execute against the test APK's isolated context.
gradle -p android connectedDebugAndroidTest --no-daemon
# Gradle may uninstall the target package after instrumentation on some runners.
# Reinstall the identical debug APK before performing the end-to-end UI actions.
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
grep -Fq 'Знакомьтесь, Ventry' android/build/native-ui.xml
grep -Fq 'ПЕРВЫЙ ПРОДУКТ VENTRY' android/build/native-ui.xml
grep -Fq 'Долговая книга для небольшого магазина' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-ventry.png
tap_text 'Посмотреть возможности'
sleep 0.35
dump_ui
grep -Fq 'Скрыть возможности' android/build/native-ui.xml
grep -Fq 'Принимайте оплаты и проверяйте остаток' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-intro-ventry-expanded.png
tap_text 'Как это работает'
sleep 0.5
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
tap_text 'История'
sleep 0.5
dump_ui
grep -Fq 'История пока пустая' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-history.png
tap_text 'Настройки'
sleep 0.5
dump_ui
grep -Fq 'Восстановить из JSON' android/build/native-ui.xml
grep -Fq 'JSON-копия ещё не сохранена' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-settings.png
tap_text 'Крупный текст'
sleep 0.5
adb exec-out screencap -p > android/build/native-settings-large.png
tap_text 'Крупный текст'
tap_text 'Клиенты'

# Full native UI transaction path, with fictitious records on the fresh CI emulator.
fill_form_input() {
  local index="$1" value="$2" coords
  dump_ui
  coords=$(python3 - "$index" <<'PY'
import re, sys, xml.etree.ElementTree as ET
index=int(sys.argv[1])
fields=[node for node in ET.parse("android/build/native-ui.xml").iter("node")
        if node.attrib.get("class")=="android.widget.EditText"]
if len(fields)<=index: sys.exit("Input missing at index "+str(index))
nums=[int(s) for s in re.findall(r"\d+",fields[index].attrib.get("bounds",""))]
if len(nums)!=4: sys.exit("No input coordinates")
print(f"{(nums[0]+nums[2])//2} {(nums[1]+nums[3])//2}")
PY
  )
  adb shell input tap $coords
  adb shell input text "$value"
  adb shell input keyevent 4
}

tap_text 'Добавить первого клиента'
sleep 0.4
fill_form_input 0 TestBuyer
tap_text 'Сохранить'
sleep 0.6
dump_ui
grep -Fq 'TestBuyer' android/build/native-ui.xml
tap_text 'TestBuyer'
sleep 0.4
tap_text '+ Долг'
sleep 0.4
fill_form_input 0 1200
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq '200,00' android/build/native-ui.xml
tap_text 'Оплата'
sleep 0.4
fill_form_input 0 400
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq '800,00' android/build/native-ui.xml
tap_text 'Главная'
sleep 0.4
dump_ui
grep -Fq '800,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-ledger-800.png

# Reopen: the onboarding must not be shown twice and the remaining debt persists.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
sleep 3
dump_ui
if grep -Fq 'Знакомьтесь, Ventry' android/build/native-ui.xml || grep -Fq 'Записывайте долги быстро' android/build/native-ui.xml; then
    echo 'Onboarding shown again after completion'
    exit 1
fi
grep -Fq 'ОБЩАЯ ЗАДОЛЖЕННОСТЬ' android/build/native-ui.xml
grep -Fq '800,00' android/build/native-ui.xml

adb logcat -d -s AndroidRuntime:E > android/build/android-runtime-errors.txt
if grep -F 'FATAL EXCEPTION' android/build/android-runtime-errors.txt; then
  echo 'Android crash observed during onboarding smoke test'
  exit 1
fi
echo 'NATIVE_ONBOARDING_SMOKE_PASS'
