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
  bash scripts/android-test-with-retry.sh -p android connectedDebugAndroidTest --no-daemon
  # Expose capacity benchmark measurements in CI before clearing logcat.
  adb logcat -d -s RaschetBenchmark:I '*:S' | tee android/build/raschet-benchmark.txt
  adb install -r "$APK"
fi
adb logcat -c
# Preserve the failing UI and runtime evidence even when an early assertion fails.
trap 'rc=$?; if [[ "$rc" -ne 0 ]]; then echo "Smoke failed at line ${LINENO} (exit $rc)" >&2; adb exec-out screencap -p > android/build/native-failure.png || true; adb logcat -d -s AndroidRuntime:E ActivityManager:W > android/build/android-runtime-errors.txt || true; fi' EXIT
adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/raschet-ui.xml >/dev/null
  adb exec-out cat /sdcard/raschet-ui.xml > android/build/native-ui.xml
}
find_text() {
  local target="$1" match_mode="${2:-exact}" action="${3:-tap}" coords attempt
  # Off-screen practice actions require scrolling on compact displays.
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    dump_ui
    if coords=$(python3 - "$target" "$match_mode" <<'PY'
import re, sys, xml.etree.ElementTree as ET
target, mode=sys.argv[1:3]
for node in ET.parse("android/build/native-ui.xml").iter("node"):
    description=node.attrib.get("content-desc","")
    if node.attrib.get("text")==target or description==target or (mode=="client" and description.startswith(target+", остаток ")):
        nums=[int(s) for s in re.findall(r"\d+",node.attrib.get("bounds",""))]
        if len(nums)==4 and nums[3] > nums[1]:
            print(f"{(nums[0]+nums[2])//2} {(nums[1]+nums[3])//2}")
            sys.exit(0)
sys.exit(1)
PY
    ); then
      if [[ "$action" == tap ]]; then adb shell input tap $coords; fi
      return 0
    fi
    if (( attempt <= 4 )); then
      adb shell input swipe 540 1490 540 730 300
    else
      adb shell input swipe 540 730 540 1490 300
    fi
    sleep 0.35
  done
  echo "Cannot find UI target after scrolling: $target" >&2
  return 1
}
tap_text() { find_text "$1" "${2:-exact}" tap; }
reveal_text() { find_text "$1" "${2:-exact}" reveal; }
# Wait for the actual onboarding screen; only recover the known unrelated launcher ANR.
ready=false
for attempt in 1 2 3 4 5 6; do
  sleep 1
  dump_ui
  if grep -Fq 'Знакомьтесь, Ventry' android/build/native-ui.xml; then ready=true; break; fi
  if grep -Fq "Pixel Launcher isn't responding" android/build/native-ui.xml; then
    echo "System Pixel Launcher ANR before onboarding; closing only that system dialog" >&2
    cp android/build/native-ui.xml android/build/native-launcher-anr.xml
    tap_text 'Close app'
    adb shell am start -W -n "$PACKAGE/dev.ventry.raschet.SplashActivity"
  fi
done
if [[ "$ready" != true ]]; then echo 'Expected fresh onboarding did not appear' >&2; exit 1; fi
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
# Capture the actual new card and verify its navigation on the empty book.
tap_text 'Статистика'
sleep 1
reveal_text 'Общая задолженность на сегодня'
adb exec-out screencap -p > android/build/native-analytics-empty.png
tap_text '← Книга'
sleep 0.5
reveal_text 'Долговая книга'
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
  echo "Missing visible text: $target" >&2
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
replace_form_input() {
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
  adb shell input keycombination 113 29
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
tap_text 'TestBuyer' client
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
# Open each debt/payment record and verify its actual remaining/current balance.
tap_text 'Запись · 1 200,00 ₽'
sleep 0.3
dump_ui
grep -Fq 'Осталось по записи: 800,00 ₽' android/build/native-ui.xml
grep -Fq 'Текущий долг клиента: 800,00 ₽' android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.3
# The latest payment can be corrected; its preview must match the transaction guard.
tap_text 'Запись · 400,00 ₽'
sleep 0.3
tap_text 'Исправить эту запись'
sleep 0.3
replace_form_input 0 300
sleep 0.3
dump_ui
grep -Fq 'Остаток после исправления: 900,00 ₽' android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.3
# Reopen the unchanged payment and inspect the reversal projection without saving it.
tap_text 'Запись · 400,00 ₽'
sleep 0.3
tap_text 'Отменить эту запись'
sleep 0.3
dump_ui
grep -Fq 'Остаток после отмены: 1 200,00 ₽' android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.3
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
tap_text 'ArchiveBuyer' client
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
tap_text 'ArchiveBuyer' client
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

# The catalog belongs to settings, not home; manual itemized debt remains optional.
dump_ui
if grep -Fq 'Каталог товаров' android/build/native-ui.xml; then
  echo 'Unexpected catalog on home'; exit 1
fi
tap_text 'Клиенты'
sleep 0.4
tap_text 'TestBuyer' client
sleep 0.4
tap_text 'Ещё действия'
sleep 0.3
tap_text 'Долг по товарам'
sleep 0.4
dump_ui
if grep -Fq 'Выбрать из каталога' android/build/native-ui.xml; then
  echo 'Unexpected catalog picker in a clean empty catalog'; exit 1
fi
tap_text 'Название товара'
adb shell input text QA-Apple
adb shell input keyevent 4
sleep 0.3
tap_text '0,00'
adb shell input text 150
adb shell input keyevent 4
sleep 0.4
# Closing and reopening must retain the uncommitted purchase without changing the ledger.
tap_text 'Закрыть'
sleep 0.4
tap_text 'Ещё действия'
sleep 0.3
tap_text 'Долг по товарам'
sleep 0.4
dump_ui
grep -Fq 'Черновик покупки восстановлен.' android/build/native-ui.xml
grep -Fq 'QA-Apple' android/build/native-ui.xml
grep -Fq 'Итого к записи: 150,00' android/build/native-ui.xml
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

# Quick debt creates a new customer and transaction without a separate client form.
tap_text '+ Записать долг'
sleep 0.5
fill_form_input 0 QuickBuyer
fill_form_input 1 321
dump_ui
grep -Fq 'Сумма новой записи: 321,00' android/build/native-ui.xml
grep -Fq 'Добавить телефон' android/build/native-ui.xml
tap_text 'Добавить телефон'
sleep 0.3
dump_ui
grep -Fq 'Телефон (необязательно)' android/build/native-ui.xml
tap_text 'Скрыть телефон'
sleep 0.2
tap_text 'Сохранить'
sleep 0.6
reveal_text 'QuickBuyer'
grep -Fq '321,00' android/build/native-ui.xml
day=$(adb shell date +%d.%m.%y | tr -d '\r')
tap_text "$day"
sleep 0.3
dump_ui
grep -Fq 'раскрыть' android/build/native-ui.xml
if grep -Fq 'QuickBuyer' android/build/native-ui.xml; then
  echo 'Collapsed date still exposes its entries'; exit 1
fi
tap_text "$day"
sleep 0.3
reveal_text 'QuickBuyer'
tap_text 'QuickBuyer' client
sleep 0.4
dump_ui
grep -Fq 'Остаток долга' android/build/native-ui.xml
grep -Fq '321,00' android/build/native-ui.xml
# System Back returns to the originating book instead of the client directory.
adb shell input keyevent 4
sleep 0.4
dump_ui
grep -Fq 'Долговая книга' android/build/native-ui.xml
reveal_text 'QuickBuyer'

# Client directory plus can create a customer and the first debt in the same form.
tap_text 'Клиенты'
sleep 0.4
tap_text '+ Добавить клиента'
sleep 0.4
dump_ui
grep -Fq 'Дата записи' android/build/native-ui.xml
fill_form_input 0 DirectoryBuyer
fill_form_input 1 222
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'DirectoryBuyer' android/build/native-ui.xml
grep -Fq '222,00' android/build/native-ui.xml
tap_text 'DirectoryBuyer' client
sleep 0.4
tap_text '+ Долг'
sleep 0.4
dump_ui
grep -Fq 'Дата записи' android/build/native-ui.xml
fill_form_input 0 100
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq '322,00' android/build/native-ui.xml
tap_text 'Книга'
sleep 0.4
reveal_text 'DirectoryBuyer'

# Filter by debtor; matching rows retain their full-book outstanding amount.
tap_text 'Фильтры книги'
sleep 0.4
fill_form_input 0 DirectoryBuyer
tap_text 'Сохранить'
sleep 0.5
reveal_text 'DirectoryBuyer'
grep -Fq '322,00' android/build/native-ui.xml
if grep -Fq 'QuickBuyer' android/build/native-ui.xml; then
  echo 'Book search leaked another client'; exit 1
fi
tap_text 'Фильтры книги'
sleep 0.4
tap_text 'Сбросить все условия'
tap_text 'Сохранить'
sleep 0.4
tap_text "$day"
sleep 0.4
tap_text '+ Долг за этот день'
sleep 0.4
dump_ui
grep -Fq "$day" android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.3

# Payments journal: full-balance shortcut prefills only, explicit save posts once.
tap_text 'Книга'
sleep 0.4
tap_text 'Оплаты по датам'
sleep 0.4
dump_ui
grep -Fq 'Полученные оплаты' android/build/native-ui.xml
grep -Fq '400,00' android/build/native-ui.xml
tap_text 'Принять оплату'
sleep 0.4
tap_text 'Выбрать · DirectoryBuyer'
sleep 0.4
tap_text 'Весь остаток'
sleep 0.4
dump_ui
grep -Fq 'Остаток после записи: 0,00' android/build/native-ui.xml
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'Полученные оплаты' android/build/native-ui.xml
reveal_text 'DirectoryBuyer'
grep -Fq '322,00' android/build/native-ui.xml
tap_text 'Долги по датам'
sleep 0.4

# Repeat the last amount for a fully paid client without creating a duplicate customer.
tap_text 'Новый долг · DirectoryBuyer'
sleep 0.4
tap_text 'Повторить сумму: 100,00 ₽'
sleep 0.3
dump_ui
grep -Fq 'Остаток после записи: 100,00' android/build/native-ui.xml
tap_text 'Закрыть'
sleep 0.4
tap_text 'Новый долг · DirectoryBuyer'
sleep 0.4
tap_text 'Повторить сумму: 100,00 ₽'
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'Долговая книга' android/build/native-ui.xml
reveal_text 'DirectoryBuyer'
tap_text 'DirectoryBuyer' client
sleep 0.4
dump_ui
grep -Fq 'Остаток долга' android/build/native-ui.xml
grep -Fq '100,00' android/build/native-ui.xml
tap_text 'Книга'

# History search and inspection of the exact operation.
tap_text 'История'
sleep 0.4
tap_text 'Имя, телефон или комментарий'
adb shell input text DirectoryBuyer
adb shell input keyevent 4
sleep 0.4
dump_ui
grep -Fq 'DirectoryBuyer' android/build/native-ui.xml
if grep -Fq 'QuickBuyer' android/build/native-ui.xml; then
  echo 'History search leaked another client'; exit 1
fi
tap_text 'Запись · 100,00 ₽'
sleep 0.4
dump_ui
grep -Fq 'Сведения об операции' android/build/native-ui.xml
grep -Fq 'Исправить эту запись' android/build/native-ui.xml
tap_text 'Все операции клиента'
sleep 0.4
dump_ui
grep -Fq 'Клиент: DirectoryBuyer' android/build/native-ui.xml
tap_text 'Книга'

# Client-specific debt limit: denial first, explicit confirmation posts once.
tap_text 'Клиенты'
sleep 0.4
tap_text 'DirectoryBuyer' client
sleep 0.4
tap_text 'Ещё действия'
sleep 0.4
tap_text 'Лимит задолженности'
sleep 0.4
fill_form_input 0 150
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'Доступно 50,00' android/build/native-ui.xml
tap_text '+ Долг'
sleep 0.4
tap_text 'Повторить сумму: 100,00 ₽'
sleep 0.4
dump_ui
grep -Fq 'После записи превышение 50,00' android/build/native-ui.xml
tap_text 'Сохранить'
sleep 0.4
dump_ui
grep -Fq 'Превышен лимит задолженности' android/build/native-ui.xml
tap_text 'Подтверждаю превышение лимита'
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'Превышение 50,00' android/build/native-ui.xml
tap_text 'Книга'

# Quick debt draft must survive closing without creating a client or operation.
tap_text '+ Записать долг'
sleep 0.4
fill_form_input 0 DraftBuyer
fill_form_input 1 123
tap_text 'Закрыть'
sleep 0.4
tap_text '+ Записать долг'
sleep 0.4
tap_text 'Продолжить черновик'
sleep 0.4
dump_ui
grep -Fq 'DraftBuyer' android/build/native-ui.xml
grep -Fq '123' android/build/native-ui.xml
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq 'DraftBuyer' android/build/native-ui.xml
tap_text '+ Записать долг'
sleep 0.4
dump_ui
if grep -Fq 'Продолжить черновик' android/build/native-ui.xml; then
  echo 'Committed draft still offered'; exit 1
fi
tap_text 'Закрыть'
sleep 0.3

# Collections workspace: plan a real contact without changing the debt.
tap_text 'Возврат долгов'
sleep 0.4
dump_ui
grep -Fq 'План работы с непогашенными долгами' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-collections.png
tap_text 'Имя или телефон'
adb shell input text DraftBuyer
adb shell input keyevent 4
sleep 0.4
dump_ui
grep -Fq 'DraftBuyer' android/build/native-ui.xml
tap_text 'Запланировать'
sleep 0.4
tap_text 'Сегодня'
tap_text 'Сохранить'
sleep 0.5
find_visible 'Связаться сегодня или раньше'
find_visible '123,00'
tap_text 'Требуют внимания'
sleep 0.4
find_visible 'DraftBuyer'
tap_text 'Изменить план'
sleep 0.4
tap_text 'Удалить план'
sleep 0.4
find_visible 'Нет должников по выбранным условиям.'
tap_text 'Книга'

# Promise for DraftBuyer: partial receipt, then complete promised amount.
tap_text 'Возврат долгов'
sleep 0.4
tap_text 'Имя или телефон'
adb shell input text DraftBuyer
adb shell input keyevent 4
sleep 0.4
tap_text 'Запланировать'
sleep 0.4
fill_form_input 0 100
tap_text 'Сохранить'
sleep 0.5
find_visible 'Обещано 100,00'
find_visible 'Осталось по обещанию: 100,00'
tap_text 'Принять оплату'
sleep 0.4
fill_form_input 0 40
tap_text 'Сохранить'
sleep 0.5
find_visible 'Осталось по обещанию: 60,00'
tap_text 'Принять оплату'
sleep 0.4
tap_text 'По обещанию: 60,00 ₽'
tap_text 'Сохранить'
sleep 0.5
find_visible 'Выполнено · получено 100,00'
tap_text 'Книга'

# A payment draft must not post on close, and reopening keeps the entered amount.
tap_text 'Клиенты'
sleep 0.4
tap_text 'Поиск по имени или телефону'
adb shell input text DraftBuyer
adb shell input keyevent 4
sleep 0.4
# Select the actual customer row, not the search EditText with identical text.
tap_text 'DraftBuyer, остаток 23,00 ₽'
sleep 0.4
dump_ui
grep -Fq 'История клиента' android/build/native-ui.xml
tap_text 'Оплата'
sleep 0.4
fill_form_input 0 10
tap_text 'Закрыть'
sleep 0.4
tap_text 'Оплата'
sleep 0.4
dump_ui
grep -Fq 'Незавершённый черновик восстановлен' android/build/native-ui.xml
grep -Fq 'Остаток после записи: 13,00' android/build/native-ui.xml
tap_text 'Сохранить'
sleep 0.5
dump_ui
grep -Fq '13,00' android/build/native-ui.xml
tap_text 'Книга'

# Analytics: use real transactions from this smoke run, never sample dashboard data.
tap_text 'Статистика'
sleep 1
tap_text 'День'
sleep 1
reveal_text 'Общая задолженность на сегодня'
adb exec-out screencap -p > android/build/native-analytics-overview.png
tap_text 'Вкладка Клиенты'
sleep 0.5
reveal_text 'Рейтинг клиентов'
adb exec-out screencap -p > android/build/native-analytics-ranking.png
tap_text 'DraftBuyer'
sleep 0.8
reveal_text 'Остаток долга на сегодня'
grep -Fq '13,00' android/build/native-ui.xml
adb exec-out screencap -p > android/build/native-analytics-client.png
tap_text 'Вкладка Детали'
sleep 0.4
reveal_text 'Движение задолженности'
tap_text 'Вкладка Экспорт'
sleep 0.4
reveal_text 'Сохранить CSV'
tap_text 'Сохранить CSV'
sleep 0.4
dump_ui
grep -Fq 'Экспорт данных клиента' android/build/native-ui.xml
tap_text 'Отмена'
tap_text '← Статистика'
sleep 0.7
reveal_text 'Рейтинг клиентов'
tap_text '← Книга'
sleep 0.4
reveal_text 'Долговая книга'
echo 'NATIVE_ANALYTICS_SMOKE_PASS'

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
