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
  assert.ok(gradle.includes("versionName = releaseVersionName"));
  assert.ok(gradle.includes('applicationIdSuffix = ".nativev031"'));
  assert.ok(!gradle.includes('webkit')&&!gradle.includes('syncOfflineBook'));
  assert.ok(!gradle.includes('index.html')&&!gradle.includes('app.js'));
  assert.ok(!activity.includes('android.webkit')&&!activity.includes('WebView'));
  for (const unwanted of ['ЭКОНОМИКА', 'ЛОКАЛЬНАЯ КНИГА', '● ОФЛАЙН', 'Общая книга онлайн', 'Нативный Android'])
    assert.ok(!activity.includes(unwanted),'Development or website section leaked into customer app: '+unwanted);
  assert.ok(activity.includes('ic_nav_home')&&activity.includes('ic_nav_people'));
  assert.ok(!manifest.includes('android.permission.INTERNET'));
  for(const screen of ['homeView(data)', 'clientsView(data)', 'detailView(', 'historyView(data)', 'settingsView()'])
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

test('unified release includes cloud backups and uses the pinned signer',()=>{
  const gradle=read('android/app/build.gradle.kts');
  const profile=read('android/release-profile.properties');
  const workflow=read('.github/workflows/raschet-public-licensed-candidate.yml');
  assert.match(profile,/^versionCode=[1-9][0-9]*$/m);
  assert.match(profile,/^deliveryPublicKey=[0-9a-f]{64}$/m);
  assert.ok(gradle.includes('versionCode = releaseVersionCode'));
  assert.ok(gradle.includes('LICENSE_PUBLIC_PROFILE", "true"'));
  assert.ok(gradle.includes('.manifest.srcFile("src/licensedRelease/AndroidManifest.xml")'));
  assert.ok(workflow.includes("github.event_name != 'pull_request' && github.ref == 'refs/heads/main'"));
  assert.ok(workflow.includes("paths: ['android/release-profile.properties']"));
  assert.ok(workflow.includes("vars.RASCHET_SIGNING_CERT_SHA256"));
  assert.ok(workflow.includes('Release signer mismatch'));
  assert.ok(workflow.includes('.cloud.CloudBackupJob'));
  assert.ok(workflow.includes('sha256sum Raschet-licensed-candidate.apk'));
  assert.ok(!workflow.includes('gh release create'));
});

test('itemized debts use an additive migration and versioned validated purchase export',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const purchase=read('android/app/src/main/java/dev/ventry/raschet/Purchase.java');
  assert.ok(store.includes("ALTER TABLE entries ADD COLUMN purchase"));
  assert.ok(store.includes('Math.max(version,3)'));
  assert.ok(store.includes('purchase.total!=amount'));
  assert.ok(store.includes('last.purchase!=null&&amount!=last.amount'));
  assert.ok(purchase.includes('RoundingMode.HALF_UP'));
  assert.ok(ui.includes('new ClientCommand.ItemizedDebt(purchaseId,clientId,purchase.encode()'));
  const files=read('android/app/src/main/kotlin/dev/ventry/raschet/BookFileService.kt');
  assert.ok(files.includes('entry.purchase?.summary().orEmpty()'));
  assert.ok(files.includes('writer.write(listOf('));
});

test('catalog data stays portable and real editing/picking is routed to the repository',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const settings=read('android/app/src/main/kotlin/dev/ventry/raschet/SettingsCompose.kt');
  const service=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientWriteService.kt');
  assert.ok(store.includes('CREATE TABLE products'));
  assert.ok(store.includes('for(Product product:catalog)'));
  assert.ok(store.includes('version=4;'));
  assert.ok(store.includes('for(Product p:newProducts)'));
  assert.ok(settings.includes('LedgerMetric("Каталог товаров"'));
  assert.ok(ui.includes('new SqliteLedgerRepository(ledger).catalog()'));
  assert.ok(ui.includes('new ClientCommand.Catalog(new CatalogRecord('));
  assert.ok(service.includes('repository.saveCatalogProduct(command.record)'));
  assert.ok(ui.includes('Выбрать из каталога'));
  assert.ok(ui.includes('catalog.removeIf(CatalogRecord::getArchived)'));
  assert.ok(ui.includes('units.get(unit.getSelectedItemPosition())'));
  assert.ok(ui.includes('new ClientCommand.ItemizedDebt(purchaseId,clientId,purchase.encode()'));
  assert.ok(ui.includes('showPurchaseDetails('));
});

test('client history follows two primary actions and secondary actions live in one menu',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
  assert.ok(ui.includes('return ClientDetailCompose.create(this,data,id,filters.getDetail().getFilter()'));
  assert.ok(detail.includes('"+ Долг"')&&detail.includes('"Оплата"')&&detail.includes('"Ещё действия"'));
  assert.ok(!detail.includes('"Сохранить выписку PDF"')&&!detail.includes('"Долг по товарам"'));
  assert.ok(detail.includes('История клиента'));
  assert.ok(ui.includes('showClientActions('));
  assert.ok(ui.includes('Остаток после записи: '));
});

test('compact book keeps essential controls and hides redundant empty-state filters',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('private int controlHeight(){return largeText?54:48;}'));
  assert.ok(ui.includes('private int navHeight(){return largeText?76:64;}'));
  const clients=ui.slice(ui.indexOf('private View clientsView(BookScreenData data)'),ui.indexOf('private void showClientFilters()'));
  const compose=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
  assert.ok(clients.includes('ClientsCompose.create(this,data,filters.getClients().getScope(),filters.getClients().getFilter()'));
  assert.match(compose, /if \(model\.allCount > 0\) TextButton\(onClick = actions\.add,/);
  assert.ok(compose.includes('LazyColumn('));
  assert.ok(compose.includes('CollectionsInsights.visible(model.scoped, filter, amountFirst, recentFirst, query)'));
  assert.ok(clients.includes('this::showClientFilters'));
  assert.ok(!clients.includes('filterGrid'));
  const settings=read('android/app/src/main/kotlin/dev/ventry/raschet/SettingsCompose.kt');
  assert.ok(settings.includes('if (state.expanded == "privacy")'));
  assert.ok(settings.includes('if (state.expanded == "backup")'));
  assert.ok(ui.includes('filters.toggleSettings(section);render();'));
  const filters=read('android/app/src/main/kotlin/dev/ventry/raschet/presentation/BookFiltersViewModel.kt');
  assert.ok(filters.includes('out.putString("settings_expanded", s.settingsExpanded)'));
});

test('dense lists, selected filters and content-sized dialogs are wired',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const compose=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
  assert.ok(compose.includes('HorizontalDivider(color = border)'));
  assert.ok(ui.includes('filterMenuAction(menu,scope,scope.equals(filters.getClients().getScope())'));
  assert.ok(ui.includes('filterMenuAction(menu,choice,choice.equals(filters.getClients().getFilter())'));
  assert.ok(ui.includes('if(selected)item.addView(label("Выбрано"'));
  const history=read('android/app/src/main/kotlin/dev/ventry/raschet/HistoryCompose.kt');
  assert.ok(history.includes('shown[index - 1].date != row.date'));
  assert.ok(history.includes('DetailOperationRow(row, largeText'));
  assert.ok(ui.includes('textView.setFontFeatureSettings("kern, tnum")'));
  const dialog=ui.slice(ui.indexOf('private void showDialog('),ui.indexOf('private void pickImport('));
  assert.ok(dialog.includes('WindowManager.LayoutParams.WRAP_CONTENT'));
  assert.ok(dialog.includes('View.MeasureSpec.AT_MOST'));
  assert.ok(dialog.includes('WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE'));
  assert.ok(!dialog.includes('dp(550)'));
});

test('counter-first UI keeps both financial actions, compact search and viewport-aware dialogs',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  assert.ok(book.includes('OutlinedButton(onClick = actions.payment'));
  assert.ok(book.includes('Text("Принять оплату")'));
  const compose=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
  assert.ok(compose.includes('contentDescription = "+ Добавить клиента"'));
  const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
  assert.ok(detail.includes('actions.more(model.archived)'));
  assert.ok(ui.includes('root.getWindowVisibleDisplayFrame(visible)'));
  assert.ok(!ui.includes('int limit=dp(380)'));
  assert.ok(ui.includes('items.size()>1?View.VISIBLE:View.GONE'));
  assert.ok(compose.includes('selected = isSelected'));
  assert.ok(compose.includes('selectedId == status.client.id'));
});


test('Ventry ledger uses shared semantic palette and real metric rows',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const theme=read('android/app/src/main/java/dev/ventry/raschet/LedgerTheme.java');
  assert.ok(theme.includes('BG=0xff101113'));
   assert.ok(theme.includes('PANEL=0xff191a1d'));
   assert.ok(theme.includes('TEXT=0xfff2f1ee'));
   assert.ok(theme.includes('ACCENT=0xffe3d5bd'));
  for(const screen of ['MainActivity','IntroActivity','SplashActivity']) assert.ok(read('android/app/src/main/java/dev/ventry/raschet/'+screen+'.java').includes('LedgerTheme.BG'));
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  const collections=read('android/app/src/main/kotlin/dev/ventry/raschet/CollectionsCompose.kt');
  assert.ok(book.includes('LedgerTotal("Общая задолженность", model.total)'));
  assert.ok(book.includes('"Должников: " + model.debtors'));
  assert.ok(collections.includes('LedgerMetric("Просрочено", ledgerMoney(model.overdue)'));
  assert.ok(book.includes('contentDescription = "Возврат долгов"'));
  assert.ok(book.includes('clickable(onClick = actions.collections)'));
  assert.ok(ui.includes('android.R.attr.state_focused'));
  const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
  assert.ok(detail.includes('TextDecoration.LineThrough'));
  assert.ok(book.includes('TextDecoration.LineThrough'));
});


test('book starts directly with a dated debt form and expandable daily journal',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  assert.ok(ui.includes('this::showQuickDebt'));
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  assert.ok(book.includes('BookDays.group(entries, zone)'));
  assert.ok(book.includes('expanded = if (open) expanded - key else expanded + key'));
  assert.ok(ui.includes('return BookCompose.create(this,data,filters.getBook().getPayments()'));
  assert.ok(ui.includes('filters::toggleBookDay'));
  assert.ok(ui.includes('formGroup("Дата записи",date)'));
  assert.ok(ui.includes('hasExactCustomer('));
  assert.ok(store.includes('insertEntry(db,id,"debt",amount,due,note,null,timestamp,entryId)'));
});


test('client creation routes to the shared optional debt form and existing clients can select a recording date',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('if(client==null){showQuickDebt(true);return;}'));
  assert.ok(ui.includes('fromClients&&amount.getText().toString().trim().isEmpty()'));
  assert.ok(ui.includes('new ClientCommand.Debt(draftId,selected[0]'));
  assert.ok(ui.includes('formGroup(type.equals("debt")?"Дата записи":"Дата оплаты",entryDate)'));
  assert.ok(ui.includes('new ClientCommand.Debt(operationId[0],clientId,"","",cents,entryDay[0]'));
});


test('journal filters preserve the full-book allocation and support date-scoped entry',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const days=read('android/app/src/main/java/dev/ventry/raschet/BookDays.java');
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  assert.ok(book.includes('BookDays.select(allDays, people, from, to, query, onlyUnpaid && !payments)'));
  assert.ok(book.includes('actions.debtDate(day.date)'));
  assert.ok(ui.includes('day->showQuickDebt(false,day)'));
  assert.ok(book.includes('day.unpaid.getOrDefault(e.id, 0L)'));
  assert.ok(days.includes('Math.min(available,e.amount)'));
  assert.ok(days.includes('if(onlyUnpaid&&rest==0)continue;'));
});


test('launcher has adaptive and themed icon resources without changing the Ventry brand',()=>{
  const res='android/app/src/main/res/';
  const manifest=read('android/app/src/main/AndroidManifest.xml');
  assert.ok(manifest.includes('android:icon="@mipmap/ic_launcher"'));
  assert.ok(manifest.includes('android:roundIcon="@mipmap/ic_launcher"'));
  assert.ok(read(res+'mipmap-anydpi-v26/ic_launcher.xml').includes('@drawable/ic_book_foreground'));
  assert.ok(read(res+'mipmap-anydpi-v33/ic_launcher.xml').includes('<monochrome android:drawable="@drawable/ic_book_monochrome"'));
  const foreground=read(res+'drawable/ic_book_foreground.xml');
  const mono=read(res+'drawable/ic_book_monochrome.xml');
  assert.ok(foreground.includes('android:viewportWidth="108"'));
  assert.ok(foreground.includes('android:fillType="evenOdd"'));
  assert.equal(foreground.replace('#48CF99','#FFFFFF'),mono);
  assert.ok(read(res+'drawable/ic_ventry.xml').includes('Faithful Android vector'));
});


test('payment journal is separate from unpaid debt and shortcuts only prefill the amount',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const days=read('android/app/src/main/java/dev/ventry/raschet/BookDays.java');
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  assert.ok(book.includes('BookDays.groupPayments(entries, zone)'));
  assert.ok(book.includes('"Оплаты по датам" to true'));
  assert.ok(book.includes('actions.mode(value)'));
  assert.ok(ui.includes("if(historicalMaximum[0]>0)amount.setText(amountInput(historicalMaximum[0]));"));
  assert.ok(ui.includes("historicalMaximum[0]=DatedPayments.maximum(clientEntries,clientId,entryDay[0],today,ZoneId.systemDefault());"));
  assert.ok(ui.includes("else if(payment&&cents>historicalMaximum[0])"));
  assert.ok(ui.includes('"payment".equals(receipt.getKind())&&receipt.getDestination()==WriteDestination.BOOK'));
  assert.ok(days.includes('if(!"payment".equals(e.type))continue;'));
});


test('repeat debt shortcuts prefill without posting and quick debt has optional detail fields',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const recent=read('android/app/src/main/java/dev/ventry/raschet/RecentDebts.java');
  const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
  assert.ok(book.includes('actions.debtClient(person.id)'));
  assert.ok(ui.includes('id->showEntryForm(id,"debt")'));
  assert.ok(ui.includes('()->amount.setText(amountInput(cents))'));
  assert.ok(ui.includes('addButton(matches,"Недавние клиенты"'));
  assert.match(ui,/new ClientCommand\.Debt\(draftId,selected\[0\],n,ph,cents,day\[0\],\s*due\.getText\(\)\.toString\(\),note\.getText\(\)\.toString\(\)\.trim\(\)/);
  assert.ok(recent.includes('!canceled.contains(e.id)'));
});


test('operation inspector uses exact selected IDs and history search',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const history=read('android/app/src/main/kotlin/dev/ventry/raschet/HistoryCompose.kt');
  assert.ok(history.includes('HistoryRows.select(entries, people, clientId, query, from, to, filter, zone)'));
  assert.ok(ui.includes('return HistoryCompose.create(this,data,filters.getHistory().getClientId(),filters.getHistory().getQuery()'));
  assert.ok(history.includes('actions.open(row.entry.id)'));
  assert.ok(ui.includes('this::showOperation,this::showPurchaseDetails'));
  const repository=read('android/app/src/main/kotlin/dev/ventry/raschet/data/LedgerRepository.kt');
  const service=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientWriteService.kt');
  assert.ok(ui.includes('new ClientCommand.Correction(clientId,original.id'));
  assert.ok(ui.includes('new ClientCommand.Reversal(clientId,last.id'));
  assert.ok(repository.includes('store.correctSelected(command.clientId, command.expectedId,'));
  assert.ok(repository.includes('store.reverseSelected(command.clientId, command.expectedId,'));
  assert.ok(service.includes('is ClientCommand.Correction -> repository.correct(command)'));
  assert.ok(service.includes('is ClientCommand.Reversal -> repository.reverse(command)'));
});


test('all new debt forms check the local client credit limit before storing debt',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('separate[0],notice.confirmation()'));
  assert.ok(ui.includes('false,notice.confirmation(),false,destination'));
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const purchase=store.slice(store.indexOf('WriteReceipt recordCheckedPurchase('),store.indexOf('/** Existing-client draft submission'));
  assert.ok(ui.includes('new ClientCommand.ItemizedDebt(purchaseId,clientId,purchase.encode()'));
  assert.ok(ui.includes('note.getText().toString().trim(),notice.confirmation()'));
  assert.ok(purchase.includes('DebtApproval.matches('));
  assert.ok(purchase.includes('DebtLimits.requireConsent(limit,balance,purchase.total,confirmed)'));
  assert.ok(purchase.includes('db.beginTransaction()'));
  assert.ok(purchase.includes('db.setTransactionSuccessful()'));
  assert.ok(ui.includes('consent.setChecked(false)'));
});


test('quick form drafts resume explicitly and use stable operation IDs',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  assert.ok(ui.includes('Продолжить черновик'));
  assert.ok(ui.includes('QuickDebtDrafts.Draft pending'));
  assert.ok(ui.includes('separate[0],notice.confirmation()'));
  assert.ok(store.includes('if(hasEntryId(entryId))'));
});


test('collection workspace is separate from chronological book and uses a read-only worklist',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const queue=read('android/app/src/main/java/dev/ventry/raschet/CollectionWorklist.java');
  const navigation=read('android/app/src/main/kotlin/dev/ventry/raschet/presentation/BookNavigationViewModel.kt');
  assert.ok(ui.includes('case COLLECTIONS -> collectionView(data)'));
  assert.ok(ui.includes('id->openClient(id,Screen.COLLECTIONS)'));
  assert.ok(ui.includes('new ClientCommand.FollowUp(person.id,current==null?null:current.id,day[0]'));
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  assert.ok(store.includes('FollowUpJournal.save(db,command.getClientId()'));
  assert.ok(!ui.includes('directory.setFollowUp(person.id'));
  assert.ok(ui.includes('fromCollections?WriteDestination.COLLECTIONS:WriteDestination.DETAIL'));
  assert.ok(ui.includes('bookNavigation.onWrite(receipt.getDestination(),receipt.getClientId())'));
  assert.ok(navigation.includes('WriteDestination.COLLECTIONS -> showRoot(Screen.COLLECTIONS)'));
  assert.ok(navigation.includes('showRoot(state.clientOrigin)'));
  assert.ok(queue.includes('if(s.balance>0)'));
});


test('payment promises calculate progress from active receipts after the promise and only prefill payment',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const model=read('android/app/src/main/java/dev/ventry/raschet/PaymentPromises.java');
 const collections=read('android/app/src/main/kotlin/dev/ventry/raschet/CollectionsCompose.kt');
 assert.ok(collections.includes('heightIn(min = 48.dp).clickable { actions.open(person.id) }'));
 assert.ok(collections.includes('promiseNotices(plan, item.promise, today)'));
 assert.ok(ui.includes('Обещанная сумма, ₽ (необязательно)'));
 assert.ok(ui.includes('()->amount.setText(amountInput(outstanding))'));
 assert.ok(model.includes('canceled.contains(e.id)'));
 assert.ok(model.includes('e.id.equals(plan.anchor)'));
});


test('client drafts keep operation identity and original date across reopening',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const draft=read('android/app/src/main/java/dev/ventry/raschet/LedgerDrafts.java');
 assert.ok(ui.includes('previous.day:initialDay'));
 assert.ok(ui.includes('new ClientCommand.Payment(operationId[0],clientId,cents'));
 const files=read('android/app/src/main/kotlin/dev/ventry/raschet/BookFileService.kt');
 const restore=files.slice(files.indexOf('private fun restore('));
 assert.ok(restore.indexOf('val count = ledger.importJson(backup)')>=0);
 assert.ok(restore.indexOf('LedgerDrafts(app).clearAll()')>restore.indexOf('val count = ledger.importJson(backup)'));
 assert.ok(draft.includes('json.put("day",day.toString())'));
});


test('client statement allocates before filtering and reuses the existing operation inspector',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const model=read('android/app/src/main/java/dev/ventry/raschet/ClientStatement.java');
 const detail=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientDetailCompose.kt');
 assert.ok(detail.includes('ClientStatement.build(all, id, zone)'));
 assert.ok(detail.includes('statement.remaining[entry.id]'));
 assert.ok(ui.includes('this::showDetailFilters'));
 assert.ok(detail.includes('drafts.get(id, kind)'));
 assert.ok(detail.includes('actions.open(row.entry.id)'));
 assert.ok(ui.includes('this::showOperation,this::showPurchaseDetails'));
 assert.ok(model.includes('BookDays.group(own,zone)'));
 assert.ok(model.includes('remaining.getOrDefault(e.id,0L)<=0'));
});


test('backdated receipts validate historical balances and reopen the actual receipt day',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
 const model=read('android/app/src/main/java/dev/ventry/raschet/DatedPayments.java');
 const book=read('android/app/src/main/kotlin/dev/ventry/raschet/BookCompose.kt');
 assert.ok(book.includes('"+ Оплата за этот день"'));
 assert.ok(book.includes('actions.paymentDate(day.date)'));
 assert.ok(ui.includes('day->chooseClient("payment",day)'));
 assert.ok(ui.includes('operationId[0],entryDay[0]'));
 assert.ok(store.includes('amount>DatedPayments.maximum('));
 assert.ok(model.includes('DatedLedger.maximum(entries,id,day,today,zone)'));
 const kotlin=read('android/app/src/main/kotlin/dev/ventry/raschet/domain/DatedLedger.kt');
 assert.ok(kotlin.includes('minimum = minOf(minimum, running)'));
});


test('client directory supports cleared balances and recent insertion order without changing financial data',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const model=read('android/app/src/main/java/dev/ventry/raschet/CollectionsInsights.java');
 const search=read('android/app/src/main/java/dev/ventry/raschet/ClientSearch.java');
 assert.ok(ui.includes('Сортировка: последние операции'));
 const compose=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientsCompose.kt');
 assert.ok(compose.includes('"Клиентов: " + result.size'));
 assert.ok(model.includes('CLEAR.equals(filter) && status.balance != 0'));
 assert.ok(model.includes('item.lastOperationIndex'));
 assert.ok(search.includes('wanted.substring(1).equals(actual.substring(1))'));
});


test('correction and reversal previews use the same balance guard and exact selected operation',()=>{
 const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
 const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
 assert.ok(ui.includes('showCorrection(entry.clientId,entry.id)'));
 assert.ok(ui.includes('showReversal(entry.clientId,entry.id)'));
 assert.ok(ui.includes('LedgerStore.correctedBalance(currentBalance,original,'));
 assert.ok(ui.includes('Остаток после отмены: '));
 assert.ok(store.includes('correctedBalance(balance(all,clientId),last,amount)'));
});

test('financial commands revalidate consent and preserve existing transactional write guards',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const repository=read('android/app/src/main/kotlin/dev/ventry/raschet/data/LedgerRepository.kt');
  const service=read('android/app/src/main/kotlin/dev/ventry/raschet/ClientWriteService.kt');
  const debt=store.slice(store.indexOf('WriteReceipt recordCheckedDebt('),store.indexOf('WriteReceipt recordCheckedPayment('));
  assert.ok(debt.includes('db.beginTransaction()'));
  assert.ok(debt.includes('DebtApproval.matches('));
  assert.ok(debt.includes('DebtLimits.requireConsent('));
  assert.ok(debt.includes('recordDatedDebt(client,'));
  assert.ok(debt.includes('db.setTransactionSuccessful()'));
  assert.ok(repository.includes('store.recordCheckedDebt(command, context)'));
  assert.ok(repository.includes('store.recordCheckedPayment(command)'));
  assert.ok(service.includes('drafts.get(clientId, kind)?.id == id'));
});

test('licensed staging adds network permission and an isolated package only when explicitly selected',()=>{
  const gradle=read('android/app/build.gradle.kts');
  const manifest=read('android/app/src/licensedStaging/AndroidManifest.xml');
  assert.ok(gradle.includes('if (licensePin != null) {'));
  assert.ok(gradle.includes('applicationIdSuffix = ".licensedstaging"'));
  assert.ok(gradle.includes('src/licensedStaging/AndroidManifest.xml'));
  assert.ok(manifest.includes('android.permission.INTERNET'));
  assert.match(manifest,/<activity android:name="\.license\.LicenseActivity" android:exported="false"\s*\/>/);
  assert.ok(!read('android/app/src/main/AndroidManifest.xml').includes('android.permission.INTERNET'));
});
