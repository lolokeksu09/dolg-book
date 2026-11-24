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
  assert.ok(gradle.includes('versionName = "0.19.0-beta.1"'));
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

test('itemized debts use an additive migration and versioned validated purchase export',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const purchase=read('android/app/src/main/java/dev/ventry/raschet/Purchase.java');
  assert.ok(store.includes("ALTER TABLE entries ADD COLUMN purchase"));
  assert.ok(store.includes('Math.max(root.getInt("version"),3)'));
  assert.ok(store.includes('purchase.total!=amount'));
  assert.ok(store.includes('last.purchase!=null&&amount!=last.amount'));
  assert.ok(purchase.includes('RoundingMode.HALF_UP'));
  assert.ok(ui.includes('ledger.addPurchase(clientId,purchase'));
  assert.ok(ui.includes('e.purchase.summary()'));
});

test('legacy catalog data stays portable but the standalone catalog and picker are removed',()=>{
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(store.includes('CREATE TABLE products'));
  assert.ok(store.includes('root.put("products",ps)'));
  assert.ok(store.includes('root.put("version",4)'));
  assert.ok(store.includes('for(Product p:newProducts)'));
  assert.doesNotMatch(ui,/\b(?:openCatalog|catalogView|showProductForm|addCatalogPicker|applyProduct)\s*\(/);
  assert.doesNotMatch(ui,/ledger\.(?:products|saveProduct)\s*\(/);
  assert.ok(!ui.includes('Каталог товаров'));
  assert.ok(!ui.includes('Выбрать из каталога'));
  assert.ok(ui.includes('ledger.addPurchase(clientId,purchase'));
  assert.ok(ui.includes('showPurchaseDetails('));
});

test('client history follows two primary actions and secondary actions live in one menu',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const detail=ui.slice(ui.indexOf('private View detailView('),ui.indexOf('private void changeClientArchive('));
  assert.ok(detail.includes('"+ Долг"')&&detail.includes('"Оплата"')&&detail.includes('"Ещё действия"'));
  assert.ok(!detail.includes('"Сохранить выписку PDF"')&&!detail.includes('"Долг по товарам"'));
  assert.ok(detail.includes('История клиента'));
  assert.ok(ui.includes('showClientActions('));
  assert.ok(ui.includes('Остаток после записи: '));
});

test('compact book keeps essential controls and hides redundant empty-state filters',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('private int controlHeight(){return largeText?54:48;}'));
  assert.ok(ui.includes('private int navHeight(){return largeText?68:56;}'));
  const clients=ui.slice(ui.indexOf('private View clientsView()'),ui.indexOf('private void showClientFilters()'));
  assert.ok(clients.indexOf('if(ledger.clients().isEmpty())')<clients.indexOf('"+ Добавить клиента"'));
  assert.ok(clients.includes('this::showClientFilters'));
  assert.ok(!clients.includes('filterGrid'));
  assert.ok(ui.includes('if(settingsExpanded.equals("privacy"))'));
  assert.ok(ui.includes('if(settingsExpanded.equals("backup"))'));
  assert.ok(ui.includes('out.putString("settings_expanded",settingsExpanded)'));
});

test('dense lists, selected filters and content-sized dialogs are wired',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('addListDivider(rows)'));
  assert.ok(ui.includes('filterMenuAction(menu,scope,scope.equals(clientScope)'));
  assert.ok(ui.includes('filterMenuAction(menu,choice,choice.equals(clientFilter)'));
  assert.ok(ui.includes('if(selected)item.addView(label("Выбрано"'));
  assert.ok(ui.includes('LocalDate lastHistoryDay=null;'));
  assert.ok(ui.includes('date.equals(lastHistoryDay)'));
  assert.ok(ui.includes('textView.setFontFeatureSettings("kern, tnum")'));
  const dialog=ui.slice(ui.indexOf('private void showDialog('),ui.indexOf('private void pickImport('));
  assert.ok(dialog.includes('WindowManager.LayoutParams.WRAP_CONTENT'));
  assert.ok(dialog.includes('View.MeasureSpec.AT_MOST'));
  assert.ok(dialog.includes('WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE'));
  assert.ok(!dialog.includes('dp(550)'));
});

test('counter-first UI keeps both financial actions, compact search and viewport-aware dialogs',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('addButton(quick,"Принять оплату",false'));
  assert.ok(ui.includes('add.setContentDescription("+ Добавить клиента")'));
  assert.ok(ui.includes('context.addView(moreActions'));
  assert.ok(ui.includes('root.getWindowVisibleDisplayFrame(visible)'));
  assert.ok(!ui.includes('int limit=dp(380)'));
  assert.ok(ui.includes('items.size()>1?View.VISIBLE:View.GONE'));
  assert.ok(ui.includes('card.setSelected(customer.id.equals(selectedClient))'));
});


test('AMOLED ledger uses shared semantic palette and real metric rows',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const theme=read('android/app/src/main/java/dev/ventry/raschet/LedgerTheme.java');
  assert.ok(theme.includes('BG=0xff000000'));
  for(const screen of ['MainActivity','IntroActivity','SplashActivity']) assert.ok(read('android/app/src/main/java/dev/ventry/raschet/'+screen+'.java').includes('LedgerTheme.BG'));
  assert.ok(ui.includes('balancePanel("Общая задолженность",debt)'));
  assert.ok(ui.includes('metricRow(due,"Просрочено",money(overdue)'));
  assert.ok(ui.includes('metricRow(due,"Должников",debtors'));
  assert.ok(ui.includes('android.R.attr.state_focused'));
  assert.ok(ui.includes('STRIKE_THRU_TEXT_FLAG'));
});


test('book starts directly with a dated debt form and expandable daily journal',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const store=read('android/app/src/main/java/dev/ventry/raschet/LedgerStore.java');
  assert.ok(ui.includes('this::showQuickDebt'));
  assert.ok(ui.includes('BookDays.group(entries,ZoneId.systemDefault())'));
  assert.ok(ui.includes('expandedBookDays.add(key)'));
  assert.ok(ui.includes('formGroup("Дата записи",date)'));
  assert.ok(ui.includes('hasExactCustomer('));
  assert.ok(store.includes('insertEntry(db,id,"debt",amount,due,note,null,timestamp)'));
});


test('client creation routes to the shared optional debt form and existing clients can select a recording date',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  assert.ok(ui.includes('if(client==null){showQuickDebt(true);return;}'));
  assert.ok(ui.includes('fromClients&&amount.getText().toString().trim().isEmpty()'));
  assert.ok(ui.includes('ledger.recordDatedDebt(selected[0]'));
  assert.ok(ui.includes('formGroup("Дата записи",debtDateField(entryDay))'));
  assert.ok(ui.includes('ledger.recordDatedDebt(clientId,"","",cents,entryDay[0]'));
});


test('journal filters preserve the full-book allocation and support date-scoped entry',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const days=read('android/app/src/main/java/dev/ventry/raschet/BookDays.java');
  assert.ok(ui.includes('BookDays.select(allDays,people,bookFrom,bookTo,bookQuery,bookOnlyUnpaid)'));
  assert.ok(ui.includes('showQuickDebt(false,day.date)'));
  assert.ok(ui.includes('day.unpaid.getOrDefault(e.id,0L)'));
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
  assert.ok(ui.includes('BookDays.groupPayments(entries,ZoneId.systemDefault())'));
  assert.ok(ui.includes('bookModeButton(modes,"Оплаты по датам",true)'));
  assert.ok(ui.includes('amount.setText(amountInput(available))'));
  assert.ok(ui.includes('if(type.equals("payment")&&fromBook)'));
  assert.ok(days.includes('if(!"payment".equals(e.type))continue;'));
});


test('repeat debt shortcuts prefill without posting and quick debt has optional detail fields',()=>{
  const ui=read('android/app/src/main/java/dev/ventry/raschet/MainActivity.java');
  const recent=read('android/app/src/main/java/dev/ventry/raschet/RecentDebts.java');
  assert.ok(ui.includes('repeat.setOnClickListener(v->showEntryForm(person.id,"debt"))'));
  assert.ok(ui.includes('()->amount.setText(amountInput(cents))'));
  assert.ok(ui.includes('addButton(matches,"Недавние клиенты"'));
  assert.ok(ui.includes('day[0],due.getText().toString(),note.getText().toString().trim()'));
  assert.ok(recent.includes('!canceled.contains(e.id)'));
});
