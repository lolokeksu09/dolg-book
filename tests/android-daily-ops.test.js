import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
const read=(path)=>readFileSync(resolve(import.meta.dirname,'..',path),'utf8');

test('recovery uses authenticated Android Keystore encryption and fail-closed import',()=>{
  const vault=read('android/app/src/main/java/dev/ventry/raschet/RecoveryVault.java');
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.match(vault,/AES\/GCM\/NoPadding/);
  const frames=read('android/app/src/main/java/dev/ventry/raschet/RecoveryFrames.java');
  assert.match(frames,/cipher\.updateAAD\(aad\(id,index,length\)\)/);
  assert.ok(vault.includes('encrypted.finish()'));
  assert.ok(frames.includes('frame(0)'));
  assert.ok(vault.includes('raschet.recovery.aes.v1'));
  assert.ok(vault.includes('raschet.recovery.aes.v2'));
  assert.match(vault,/AndroidKeyStore/);
  assert.match(vault,/setRandomizedEncryptionRequired\(true\)/);
  const service=read('android/app/src/main/kotlin/dev/ventry/raschet/BookFileService.kt');
  const vm=read('android/app/src/main/kotlin/dev/ventry/raschet/presentation/BookViewModel.kt');
  const writer=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientWriteService.kt');
  const start=service.indexOf('private fun restore(');
  const snapshot=service.indexOf('vault.create(ledger)',start);
  const mutate=service.indexOf('val count = ledger.importJson(backup)',start);
  assert.ok(start>=0 && snapshot>start && mutate>snapshot);
  assert.ok(service.includes('vault.openLatest().use'));
  assert.ok(service.includes('BackupSize.openUtf8(stream).use'));
  const pending=read('android/app/src/main/kotlin/dev/ventry/raschet/PendingFileRequests.kt');
  assert.ok(activity.includes('pendingFiles.requestImport()'));
  assert.ok(activity.includes('pendingFiles.consume(request,result==RESULT_OK,authorized,'));
  assert.match(activity,/task instanceof ClientCommand\.FileTask\.Import[\s\S]*?showDialog\("Восстановить книгу\?",form,null,\(\)->task\)/);
  assert.ok(pending.includes('REQUEST_IMPORT -> if (pendingImport) FileTask.Import(uri, preferences) else null'));
  assert.ok(pending.includes('if (!accepted || !authorized || uri.isNullOrEmpty()) return null'));
  assert.match(pending,/finally\s*\{\s*clear\(request\)/);
  assert.ok(activity.includes('new ClientCommand.FileTask.RestorePoint(getLocalClassName())'));
  assert.ok(!activity.includes('ledger.importJson('));
  assert.ok(!activity.includes('openInputStream('));
  assert.ok(!activity.includes('openOutputStream('));
  assert.ok(writer.includes('return BookFileService.execute(application, command, store)'));
  assert.ok(vm.includes('readMutex.withLock { ClientWriteService.execute(application, command) }'));
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
  const pending=read('android/app/src/main/kotlin/dev/ventry/raschet/PendingFileRequests.kt');
  assert.ok(activity.includes('pendingFiles.pdf(clientId)'));
  assert.ok(activity.includes('launchFileRequest(REQUEST_PDF,createDocument("application/pdf",'));
  assert.ok(pending.includes('REQUEST_PDF -> pdfClient?.let { FileTask.Pdf(uri, it, shop) }'));
  assert.ok(pending.includes('REQUEST_PDF -> pdfClient = null'));
  assert.ok(activity.includes('Intent.ACTION_CREATE_DOCUMENT'));
  const service=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientWriteService.kt');
  assert.ok(service.includes('drafts.get(clientId, kind)?.id == id'));
  assert.ok(service.includes('drafts.clear(clientId, kind)'));
  assert.ok(draft.includes('raschet_operation_drafts'));
  assert.ok(pdf.includes('PdfDocument'));
  assert.ok(pdf.includes('MAX_LINES'));
});

test('tablet adaptation and bounded history belong to 0.5 beta',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('tabletClientsView(data)'));
  assert.ok(activity.includes('screenWidthDp>=1100'));
  const history=read('android/app/src/main/kotlin/dev/ventry/raschet/HistoryCompose.kt');
  assert.ok(history.includes('val shown = result.take(limit)'));
  assert.ok(history.includes('limit = minOf(100000, limit + 100)'));
  const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
  assert.ok(detail.includes('model.rows.take(visibleLimit)'));
  assert.ok(detail.includes('visibleLimit = minOf(100000, visibleLimit + 80)'));
  const gradle=read('android/app/build.gradle.kts');
  assert.ok(gradle.includes('versionCode = 35'));
  assert.ok(gradle.includes('versionName = "0.30.0-beta.1"'));
});

// A weighted horizontal LinearLayout must not baseline-measure a lazy Compose child with UNSPECIFIED height.
test('Compose page and tablet panes receive bounded weighted measurement',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.match(activity,/LinearLayout body=row\(\);[\s\S]*?body\.setBaselineAligned\(false\);[\s\S]*?body\.addView\(page,new LinearLayout\.LayoutParams\(0,-1,1\)\)/);
  assert.match(activity,/LinearLayout columns=row\(\);\s*columns\.setBaselineAligned\(false\);/);
  assert.ok(activity.includes('page.addView(contents,new FrameLayout.LayoutParams(-1,-1))'));
});
