#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/build
if [[ -n "${RASCHET_SIGNED_APK:-}" ]]; then
  APK="$RASCHET_SIGNED_APK"
  PACKAGE=dev.ventry.raschet
  test -s "$APK"
  adb install "$APK"
else
  gradle -p android assembleDebug --no-daemon
  APK=android/app/build/outputs/apk/debug/app-debug.apk
  PACKAGE=dev.ventry.raschet.nativev031
  adb install -r "$APK"
  # Instrumentation tests run only with the isolated debug package.
  gradle -p android connectedDebugAndroidTest --no-daemon
  adb install -r "$APK"
fi
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
if grep -Fq 'Фильтры клиентов' android/build/native-ui.xml; then
    echo 'Empty client list must not show filters'; exit 1
fi
adb exec-out screencap -p > android/build/native-clients.png
tap_text 'Книга'
sleep 1
dump_ui
grep -Fq 'Общая задолженность' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-home.png
tap_text 'История'
sleep 0.5
dump_ui
grep -Fq 'История пока пустая' android/build/native-ui.xml
if grep -Fq 'Период истории' android/build/native-ui.xml; then
    echo 'Empty history must not show period filters'; exit 1
fi
adb exec-out screencap -p > android/build/native-history.png
tap_text 'Настройки'
sleep 0.5
dump_ui
grep -Fq 'Ежедневно проверять сроки' android/build/native-ui.xml
grep -Fq 'Резервные копии' android/build/native-ui.xml
if grep -Fq 'Создать защищённую точку' android/build/native-ui.xml; then
    echo 'Backup settings should be collapsed initially'; exit 1
fi
adb exec-out screencap -p > android/build/native-settings.png
find_visible() {
  local target="$1" attempt
  for attempt in 1 2 3 4 5 6; do
    dump_ui
    if grep -Fq "$target" android/build/native-ui.xml; then return 0; fi
    adb shell input swipe 540 1500 540 740 280
    sleep 0.35
  done
  echo "Missing settings text: $target" >&2
  return 1
}
tap_text 'Резервные копии'
sleep 0.4
find_visible 'JSON-копия ещё не сохранена'
find_visible 'Восстановить из JSON'
for i in 1 2 3 4 5; do adb shell input swipe 540 620 540 1570 230; done
tap_text 'Резервные копии'
sleep 0.3
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
dump_ui
grep -Fq 'Остаток после записи: 800,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-payment-projection.png
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq '800,00' android/build/native-ui.xml
grep -Fq 'История клиента' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-client-compact.png
tap_text 'Ещё действия'
sleep 0.3
tap_text 'Погасить весь остаток'
sleep 0.4
dump_ui
python3 - <<'PY'
import xml.etree.ElementTree as ET
fields=[n.attrib.get('text','') for n in ET.parse('android/build/native-ui.xml').iter('node')
        if n.attrib.get('class')=='android.widget.EditText']
assert '800,00' in fields, 'Full repayment form must be prefilled, not executed automatically'
PY
tap_text 'Закрыть'
sleep 0.4
tap_text 'Книга'
sleep 0.4
dump_ui
grep -Fq '800,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-ledger-800.png
tap_text 'Клиенты'
sleep 0.4
tap_text 'Фильтры клиентов'
sleep 0.3
dump_ui
grep -Fq 'Выбрано' android/build/native-ui.xml
tap_text 'С долгом'
sleep 0.4
dump_ui
grep -Fq 'TestBuyer' android/build/native-ui.xml
tap_text 'Фильтры клиентов'
sleep 0.3
dump_ui
python3 - <<'PY'
import xml.etree.ElementTree as ET
nodes=list(ET.parse("android/build/native-ui.xml").iter("node"))
assert any(n.get("content-desc")=="С долгом" and n.get("selected")=="true" for n in nodes), "Current debt filter must be selected"
PY
tap_text 'Просрочка'
sleep 0.4
dump_ui
grep -Fq 'Просроченных долгов нет' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-clients-filtered.png
tap_text 'Фильтры клиентов'
sleep 0.3
tap_text 'Все'
sleep 0.4
tap_text 'Настройки'
sleep 0.6
tap_text 'Резервные копии'
sleep 0.3
tap_text 'Создать защищённую точку'
sleep 0.6
tap_text 'Восстановить защищённую точку'
sleep 0.4
dump_ui
grep -Fq 'Восстановить точку?' android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.2
tap_text 'Книга'

# On a clean CI emulator only: reinstall the same signed candidate without
# uninstall/clear. This tests persistence across reinstall, not a version upgrade.
if [[ -n "${RASCHET_SIGNED_APK:-}" ]]; then
  adb install -r "$APK"
fi
# Reopen: the onboarding must not be shown twice and the remaining debt persists.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
sleep 3
dump_ui
if grep -Fq 'Знакомьтесь, Ventry' android/build/native-ui.xml || grep -Fq 'Записывайте долги быстро' android/build/native-ui.xml; then
    echo 'Onboarding shown again after completion'
    exit 1
fi
grep -Fq 'Общая задолженность' android/build/native-ui.xml
grep -Fq '800,00' android/build/native-ui.xml
tap_text 'Финансовый отчёт'
sleep 0.5
dump_ui
grep -Fq 'Остаток на конец' android/build/native-ui.xml
grep -Fq '800,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-period-report.png
tap_text 'Период отчёта'
sleep 0.3
tap_text 'Сегодня'
sleep 0.4
dump_ui
grep -Fq '800,00' android/build/native-ui.xml
tap_text 'Операции периода'
sleep 0.4
tap_text 'Сбросить даты'
sleep 0.3
tap_text 'Клиенты'
sleep 0.4
tap_text 'Фильтры клиентов'
sleep 0.3
tap_text 'Рабочие'
sleep 0.3
tap_text '+ Добавить клиента'
sleep 0.4
fill_form_input 0 ArchiveBuyer
tap_text 'Сохранить'
sleep 0.5
tap_text 'ArchiveBuyer'
sleep 0.4
tap_text 'Ещё действия'
sleep 0.3
tap_text 'В избранное'
sleep 0.4
tap_text 'Ещё действия'
sleep 0.3
tap_text 'В архив'
sleep 0.5
dump_ui
grep -Fq 'ArchiveBuyer' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-client-archive.png
tap_text 'ArchiveBuyer'
sleep 0.4
tap_text 'Вернуть из архива'
sleep 0.4
tap_text 'Фильтры клиентов'
sleep 0.3
tap_text 'Избранные'
sleep 0.4
dump_ui
grep -Fq 'ArchiveBuyer' android/build/native-ui.xml
tap_text 'Фильтры клиентов'
sleep 0.3
tap_text 'Рабочие'
sleep 0.3
tap_text 'Книга'
sleep 0.4
dump_ui
grep -Fq '800,00' android/build/native-ui.xml

# Simplified book: catalog no longer exists, manual itemized debt remains optional.
dump_ui
if grep -Fq 'Каталог товаров' android/build/native-ui.xml; then
  echo 'Unexpected catalog on home'; exit 1
fi
tap_text 'Клиенты'
sleep 0.4
tap_text 'TestBuyer'
sleep 0.4
tap_text 'Ещё действия'
sleep 0.3
tap_text 'Долг по товарам'
sleep 0.4
dump_ui
if grep -Fq 'Выбрать из каталога' android/build/native-ui.xml; then
  echo 'Unexpected catalog picker'; exit 1
fi
tap_text 'Название товара'
adb shell input text QA-Apple
adb shell input keyevent 4
sleep 0.3
tap_text '0,00'
adb shell input text 150
adb shell input keyevent 4
sleep 0.4
adb exec-out screencap -p > android/build/native-purchase-form.png
tap_text 'Сохранить'
sleep 0.6
dump_ui
grep -Fq '950,00' android/build/native-ui.xml
tap_text 'Состав покупки · 1 поз.'
sleep 0.4
dump_ui
grep -Fq 'QA-Apple' android/build/native-ui.xml
grep -Fq '150,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-purchase-details.png
tap_text 'Закрыть'
sleep 0.3
tap_text 'Книга'

adb logcat -d -s AndroidRuntime:E > android/build/android-runtime-errors.txt
if grep -F 'FATAL EXCEPTION' android/build/android-runtime-errors.txt; then
  echo 'Android crash observed during onboarding smoke test'
  exit 1
fi
# Wide tablet: verify real dual-pane layout with existing QA book.
adb shell wm size 2000x1200
adb shell wm density 160
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
sleep 3
tap_text 'Клиенты'
sleep 0.6
dump_ui
grep -Fq 'Выберите клиента' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-tablet-clients.png
adb shell wm size reset
adb shell wm density reset
echo 'NATIVE_ONBOARDING_SMOKE_PASS'
