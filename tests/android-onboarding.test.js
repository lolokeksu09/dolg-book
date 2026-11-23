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

test('intro shows three real steps and completes only after action',()=>{
  const intro=read('android/app/src/main/java/dev/ventry/raschet/IntroActivity.java');
  for(const text of ['Записывайте долги быстро','Принимайте оплаты без путаницы',
    'Ваша книга всегда под рукой','Далее','Начать работу','Пропустить'])
    assert.ok(intro.includes(text), 'Missing introduction text: '+text);
  assert.match(intro,/if\s*\(state\s*!=\s*null\)/);
  assert.ok(intro.includes('finishOnboarding()'));
  assert.ok(intro.includes('putBoolean(KEY_COMPLETED,true)'));
  assert.match(intro,/getSharedPreferences\(PREFS,\s*MODE_PRIVATE\)/);
  assert.ok(!intro.includes('WebView'));
});

test('native navigation and empty states are accessible, with actionable call-to-action',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('dp(88)'), 'bottom bar must not truncate labels');
  assert.ok(activity.includes('setSingleLine(true)'));
  assert.ok(activity.includes('setContentDescription(destination)'));
  assert.ok(activity.includes('setSelected(destination.equals(tab))'));
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
  assert.ok(gradle.includes('versionName = "0.3.2"'));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativev031"'));
  assert.ok(store.includes('raschet-ledger.db'));
  assert.ok(!gradle.includes('webkit'));
});
