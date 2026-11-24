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
  assert.ok(gradle.includes('versionName = "0.7.0-beta.1"'));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativev031"'));
  assert.ok(!gradle.includes('webkit')&&!gradle.includes('syncOfflineBook'));
  assert.ok(!gradle.includes('index.html')&&!gradle.includes('app.js'));
  assert.ok(!activity.includes('android.webkit')&&!activity.includes('WebView'));
  for (const unwanted of ['ЭКОНОМИКА', 'ЛОКАЛЬНАЯ КНИГА', '● ОФЛАЙН', 'Общая книга онлайн', 'Нативный Android'])
    assert.ok(!activity.includes(unwanted),'Development or website section leaked into customer app: '+unwanted);
  assert.ok(activity.includes('ic_nav_home')&&activity.includes('ic_nav_people'));
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

test('release signing is opt-in and test package does not change',()=>{
  const gradle=read('android/app/build.gradle.kts');
  assert.ok(gradle.includes('applicationId = "dev.ventry.raschet"'));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativev031"'));
  assert.ok(gradle.includes('RASCHET_SIGNING_STORE_FILE'));
  assert.ok(gradle.includes('signingConfigs.create("raschetRelease")'));
  assert.ok(!gradle.includes('storePassword = "'));
});

test('RC signing is isolated from PR and verifies the fixed certificate and candidate',()=>{
  const workflow=read('.github/workflows/android-release-candidate.yml');
  const smoke=read('scripts/android-native-smoke.sh');
  assert.ok(workflow.includes("'raschet-v*-rc.*'"));
  assert.ok(workflow.includes('vars.RASCHET_SIGNING_CERT_SHA256'));
  assert.ok(workflow.includes('Release signer mismatch'));
  assert.ok(workflow.includes("github.event_name == 'pull_request'"));
  assert.ok(workflow.includes('signed-smoke:'));
  assert.ok(workflow.includes('sha256sum -c SHA256SUMS'));
  assert.ok(smoke.includes('RASCHET_SIGNED_APK'));
  assert.ok(smoke.includes('PACKAGE=dev.ventry.raschet\n'));
});
