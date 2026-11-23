import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const root=resolve(import.meta.dirname,'..');
const read=path=>readFileSync(resolve(root,path),'utf8');

test('Android UI is native and does not contain web UI runtime',()=>{
  const gradle=read('android/app/build.gradle.kts');
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.ok(gradle.includes('versionName = "0.3.0"'));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativepreview"'));
  assert.ok(!gradle.includes('webkit')&&!gradle.includes('syncOfflineBook'));
  assert.ok(!gradle.includes('index.html')&&!gradle.includes('app.js'));
  assert.ok(!activity.includes('android.webkit')&&!activity.includes('WebView'));
  assert.ok(!manifest.includes('android.permission.INTERNET'));
  for(const screen of ['homeView()', 'clientsView()', 'detailView(', 'historyView()', 'settingsView()'])
    assert.ok(activity.includes(screen),'Missing native view '+screen);
});

test('Native SQLite ledger implements balances, reversals and atomic JSON restore',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  for(const part of ['SQLiteOpenHelper','beginTransaction()','setTransactionSuccessful()',
    'importJson(', 'exportJson()', 'reverseLast(', 'overdue(', 'MAX_AMOUNT-available',
    '"payment".equals(type)&&amount>available', 'rawAmount.toString().matches'])
    assert.ok(store.includes(part),'Missing '+part);
});

test('No automatic off-device backup of financial data',()=>{
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  const rules=read('android/app/src/main/res/xml/data_extraction_rules.xml');
  assert.ok(manifest.includes('android:allowBackup="false"'));
  assert.ok(manifest.includes('android:dataExtractionRules="@xml/data_extraction_rules"'));
  assert.ok(rules.includes('cloud-backup')&&rules.includes('device-transfer'));
});
