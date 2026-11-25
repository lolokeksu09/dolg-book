import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const read=path=>readFileSync(resolve(import.meta.dirname,'..',path),'utf8');

test('collections insight model is derived from active entries with oldest-first payment allocation',()=>{
  const source=read('android/app/src/main/java/dev/ventry/raschet/CollectionsInsights.java');
  const kotlin=read('android/app/src/main/kotlin/dev/ventry/raschet/domain/CollectionProjection.kt');
  assert.ok(source.includes('CollectionProjection.analyze(entries,today)'));
  assert.ok(kotlin.includes('LedgerProjection.canceled(entries)'));
  assert.ok(kotlin.includes('minOf(payments, row.amountKopecks)'));
  assert.ok(kotlin.includes('today.plusDays(7)'));
  assert.doesNotMatch(kotlin,/getWritableDatabase\(|SQLiteDatabase|startActivity\(/);
  assert.match(source,/visible\(/);
  assert.doesNotMatch(source,/getWritableDatabase\(|SQLiteDatabase|startActivity\(/);
});

test('customer filters, full repayment form and explicit Android sharing are wired',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const compose=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
  for(const text of ['Просрочено','Просроченных долгов нет','Нет ближайших сроков',
    'Погасить весь остаток','Поделиться сводкой','Сортировка: по остатку ↓']){
    assert.ok(activity.includes(text)||compose.includes(text),'Missing customer workflow: '+text);
  }
  const queue=read('android/app/src/main/kotlin/dev/ventry/raschet/CollectionsCompose.kt');
  assert.ok(queue.includes('LedgerMetric("Просрочено", ledgerMoney(model.overdue)'));
  assert.ok(queue.includes('actions.filter(CollectionsInsights.LATE)'));
  assert.ok(activity.includes('limit->collectionLimit=limit,this::openClientFilter'));
  assert.ok(compose.includes('CollectionsInsights.visible(model.scoped, filter, amountFirst, recentFirst, query)'));
  const viewModel=read('android/app/src/main/kotlin/dev/ventry/raschet/presentation/BookViewModel.kt');
  assert.ok(viewModel.includes('CollectionsInsights.analyze(snapshot.clients, snapshot.entries, today)'));
  assert.ok(activity.includes('data.getStatuses()'));
  assert.ok(viewModel.includes('Dispatchers.IO'));
  assert.ok(viewModel.includes('repeatOnLifecycle(Lifecycle.State.STARTED)'));
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
  assert.ok(store.includes('root.put("clients",cs).put("entries",es).toString()'));
  assert.ok(!manifest.includes('android.permission.INTERNET'));
});

test('near-term debt totals are not presented as entirely due on the earliest date',()=>{
  const activity=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const list=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
  const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
  const queue=read('android/app/src/main/kotlin/dev/ventry/raschet/CollectionsCompose.kt');
  for(const screen of [list,detail]){
    assert.ok(screen.includes('Ближайший срок: '));
    assert.ok(screen.includes('за 7 дней: '));
    assert.ok(!screen.includes('К возврату до '));
  }
  assert.ok(queue.includes('За 7 дней: '));
  assert.ok(queue.includes('actions.filter(CollectionsInsights.SOON)'));
  assert.ok(activity.includes('limit->collectionLimit=limit,this::openClientFilter'));
});
