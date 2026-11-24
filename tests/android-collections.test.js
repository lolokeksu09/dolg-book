import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const read=path=>readFileSync(resolve(import.meta.dirname,'..',path),'utf8');

test('collections insight model is derived from active entries with oldest-first payment allocation',()=>{
  const source=read('android/app/src/main/java/dev/ventry/raschet/CollectionsInsights.java');
  assert.match(source,/LedgerStore\.canceled\(entries\)/);
  assert.match(source,/Math\.min\(payments, entry\.amount\)/);
  assert.match(source,/today\.plusDays\(7\)/);
  assert.match(source,/visible\(/);
  assert.doesNotMatch(source,/getWritableDatabase\(|SQLiteDatabase|startActivity\(/);
});

test('customer filters, full repayment form and explicit Android sharing are wired',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  for(const text of ['Просроченные долги','Просроченных долгов нет','Нет ближайших сроков',
    'Погасить весь остаток','Поделиться сводкой','Сортировка: по остатку ↓']){
    assert.ok(activity.includes(text),'Missing customer workflow: '+text);
  }
  assert.ok(activity.includes('CollectionsInsights.visible('));
  assert.ok(activity.includes('CollectionsInsights.analyze('));
  assert.ok(activity.includes('showEntryForm(id,"payment",amount)'));
  assert.ok(activity.includes('Intent.createChooser(share'));
  assert.ok(activity.includes('share.setType("text/plain")'));
  assert.ok(activity.includes('clientsAmountFirst=state.getBoolean'));
  assert.ok(activity.includes('out.putString("client_filter"'));
});

test('book exports stay local and preserve entries',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.ok(store.includes('raschet-ledger.db'));
  assert.ok(store.includes('return root.put("clients",cs).put("entries",es).toString(2)'));
  assert.ok(!manifest.includes('android.permission.INTERNET'));
});

test('near-term debt totals are not presented as entirely due on the earliest date',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(activity.includes('Ближайший срок: '));
  assert.ok(activity.includes('за 7 дней: '));
  assert.ok(!activity.includes('К возврату до "+status.nextDue'));
  assert.ok(activity.includes('openClientFilter(CollectionsInsights.SOON)'));
});
