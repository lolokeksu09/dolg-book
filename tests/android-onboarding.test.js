import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const root=resolve(import.meta.dirname,'..');
const read=p=>readFileSync(resolve(root,p),'utf8');

test('splash is launcher; onboarding/main are non-exported and remain offline',()=>{
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.match(manifest,/<activity android:name="\.SplashActivity" android:exported="true">/);
  assert.match(manifest,/<activity android:name="\.IntroActivity" android:exported="false"\s*\/>/);
  assert.match(manifest,/<activity android:name="\.MainActivity" android:exported="false"\s*\/>/);
  assert.ok(!manifest.includes('android.permission.INTERNET'));
  const splash=read('android/app/src/main/java/dev/ventry/raschet/SplashActivity.java');
  assert.ok(splash.includes('IntroActivity.isCompleted(this)'));
  assert.ok(splash.includes('MainActivity.class'));
  assert.ok(splash.includes('IntroActivity.class'));
  assert.ok(splash.includes('DISPLAY_MS'));
  assert.ok(splash.includes('removeCallbacksAndMessages(null)'));
});

test('intro first presents Ventry then preserves three interactive lessons',()=>{
  const intro=read('android/app/src/main/java/dev/ventry/raschet/IntroActivity.java');
  for(const text of ['Знакомьтесь, Ventry','Ventry — проект цифровых продуктов',
    'ПЕРВЫЙ ПРОДУКТ VENTRY','Долговая книга для небольшого магазина.',
    'Записывайте долги быстро','Принимайте оплаты без путаницы',
    'Ваша книга всегда под рукой','Далее','Начать работу','Пропустить'])
    assert.ok(intro.includes(text), 'Missing introduction text: '+text);
  assert.match(intro,/if\s*\(state\s*!=\s*null\)/);
  assert.ok(intro.includes('R.drawable.ic_ventry'));
  assert.ok(intro.includes('step == 0) practice = ventryIntroduction()'));
  assert.ok(intro.includes('step == 1) practice = debtPractice()'));
  assert.ok(intro.includes('step == 2) practice = paymentPractice()'));
  assert.ok(intro.includes('brandDetailsExpanded = !brandDetailsExpanded'));
  assert.ok(intro.includes('out.putBoolean("brandDetailsExpanded", brandDetailsExpanded)'));
  assert.ok(intro.includes('step == TITLES.length - 1'));
  assert.ok(intro.includes('KEY_VENTRY_SHOWN'));
  assert.ok(intro.includes('brandOnlyForExisting ? "Открыть книгу"'));
  assert.ok(intro.includes('.putBoolean(KEY_VENTRY_SHOWN,true)'));
  assert.ok(intro.includes('step = brandOnlyForExisting ? 0'));
  assert.ok(read('android/app/src/main/res/drawable/ic_ventry.xml').includes('Faithful Android vector'));
  assert.ok(intro.includes('finishOnboarding()'));
  assert.ok(intro.includes('putBoolean(KEY_COMPLETED,true)'));
  assert.match(intro,/getSharedPreferences\(PREFS,\s*MODE_PRIVATE\)/);
  assert.ok(!intro.includes('WebView'));
});

test('native navigation and empty states are accessible, with actionable call-to-action',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('private int navHeight(){return largeText?68:56;}'), 'navigation has compact and large-text heights');
  assert.ok(activity.includes('setSingleLine(true)'));
  assert.ok(activity.includes('setContentDescription(caption)'));
  assert.ok(activity.includes('item.setSelected(active)'));
  assert.ok(activity.includes('tab.equals("Возврат")||tab.equals("Отчёт")'));
  assert.ok(activity.includes('emptyState('));
  assert.ok(activity.includes('Добавить первого клиента'));
  assert.ok(activity.includes('История пока пустая'));
  assert.ok(activity.includes('Записать долг'));
  assert.ok(activity.includes('android.widget.Switch') || activity.includes('import android.widget.Switch;'));
  assert.ok(activity.includes('EXTRA_FIRST_START'));
});

test('upgrade keeps native preview package and ledger persistence unchanged',()=>{
  const gradle=read('android/app/build.gradle.kts');
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  assert.ok(gradle.includes('versionName = "0.27.0-beta.1"'));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativev031"'));
  assert.ok(store.includes('raschet-ledger.db'));
  assert.ok(!gradle.includes('webkit'));
});

test('interactive onboarding practices debt, partial payments and backup without ledger writes',()=> {
  const intro=read('android/app/src/main/java/dev/ventry/raschet/IntroActivity.java');
  for (const text of [
    'Добавить пример клиента', 'Записать долг · 1 200 ₽',
    'Внести 400 ₽', 'Погасить полностью',
    'Показать состав JSON', 'Клиенты и контакты',
    'Здесь файл не создаётся', 'ИНТЕРАКТИВНЫЙ ПРИМЕР · НЕ СОХРАНЯЕТСЯ'
  ]) assert.ok(intro.includes(text), 'Missing practice control or disclosure: '+text);
  assert.ok(intro.includes('examplePaid = Math.min(1200, examplePaid + 400)'));
  assert.ok(intro.includes('examplePaid = 1200'));
  assert.ok(intro.includes('examplePaid = 0'));
  assert.ok(intro.includes('exampleBackupExpanded = !exampleBackupExpanded'));
  assert.ok(intro.includes('previewHost.removeAllViews()'), 'practice controls must update in place');
  for (const state of ['exampleClientAdded','exampleDebtAdded','examplePaid','exampleBackupExpanded']) {
    assert.ok(intro.includes('out.put') && intro.includes('"' + state + '"'), 'rotation state missing: '+state);
  }
  assert.ok(intro.includes('ACCESSIBILITY_LIVE_REGION_POLITE'));
  assert.ok(intro.includes('setMinHeight(dp(48))'));
  // Do not treat an explanatory comment about LedgerStore as an actual database write.
  assert.doesNotMatch(intro, /\bimport\s+[^;]*(?:LedgerStore|SQLiteDatabase)\s*;/);
  assert.doesNotMatch(intro, /\b(?:new\s+LedgerStore\s*\(|getWritableDatabase\s*\(|getReadableDatabase\s*\(|execSQL\s*\()/);
});

test('large-text preference affects native labels and remains user-controlled',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('largeText && size<26'), 'ordinary native labels must honor the large-text switch');
  assert.ok(activity.includes('getPreferences(MODE_PRIVATE).edit().putBoolean("large",checked)'));
});
