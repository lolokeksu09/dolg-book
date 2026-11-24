import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
const read=(path)=>readFileSync(resolve(import.meta.dirname,'..',path),'utf8');

test('recovery uses authenticated Android Keystore encryption and fail-closed import',()=>{
  const vault=read('android/app/src/main/java/dev/ventry/raschet/RecoveryVault.java');
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.match(vault,/AES\/GCM\/NoPadding/);
  assert.match(vault,/AndroidKeyStore/);
  assert.match(vault,/setRandomizedEncryptionRequired\(true\)/);
  const start=activity.indexOf('String backup=bytes.toString');
  const snapshot=activity.indexOf('vault.create(ledger)',start);
  const mutate=activity.indexOf('int[] count=ledger.importJson(backup)',start);
  assert.ok(start>=0 && snapshot>start && mutate>snapshot);
  assert.ok(activity.includes('vault.readLatest()'));
});

test('due reminders are opt-in, permission-gated, local-only',()=>{
  const job=read('android/app/src/main/java/dev/ventry/raschet/DebtReminderService.java');
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.ok(job.includes('setPeriodic('));
  assert.ok(job.includes('Notification.VISIBILITY_PRIVATE'));
  assert.ok(job.includes('if(!hasPermission(context))return false'));
  assert.ok(manifest.includes('android.permission.POST_NOTIFICATIONS'));
  assert.ok(manifest.includes('android.permission.RECEIVE_BOOT_COMPLETED'));
  assert.ok(!manifest.includes('android.permission.INTERNET'));
  assert.ok(!manifest.includes('android.permission.SEND_SMS'));
});

test('PDF export uses user-selected file and drafts clear after saving',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const draft=read('android/app/src/main/java/dev/ventry/raschet/LedgerDrafts.java');
  const pdf=read('android/app/src/main/java/dev/ventry/raschet/CustomerStatementPdf.java');
  assert.ok(activity.includes('pendingPdfClientId'));
  assert.ok(activity.includes('Intent.ACTION_CREATE_DOCUMENT'));
  assert.ok(activity.includes('drafts.clear(clientId,type)'));
  assert.ok(draft.includes('raschet_operation_drafts'));
  assert.ok(pdf.includes('PdfDocument'));
  assert.ok(pdf.includes('MAX_LINES'));
});

test('tablet adaptation and bounded history belong to 0.5 beta',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('tabletClientsView()'));
  assert.ok(activity.includes('screenWidthDp>=1100'));
  assert.ok(activity.includes('shown>=historyVisibleLimit'));
  assert.ok(activity.includes('shown>=detailVisibleLimit'));
  const gradle=read('android/app/build.gradle.kts');
  assert.ok(gradle.includes('versionCode = 33'));
  assert.ok(gradle.includes('versionName = "0.28.0-beta.1"'));
});
