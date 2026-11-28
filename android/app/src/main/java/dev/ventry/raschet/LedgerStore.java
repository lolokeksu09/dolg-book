package dev.ventry.raschet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Device-only ledger. Never conflates the offline database with the server shop API. */
public final class LedgerStore extends SQLiteOpenHelper {
    public static final long MAX_AMOUNT = dev.ventry.raschet.domain.LedgerArithmetic.MAX_AMOUNT;
    private static final int MAX_CLIENTS = 10_000, MAX_ENTRIES = 100_000;
    public static final class Client {
        public final String id, name, phone;
        Client(String id, String name, String phone) { this.id=id; this.name=name; this.phone=phone; }
    }
    public static final class Entry implements dev.ventry.raschet.domain.DatedLedgerRecord {
        public final String id, clientId, type, date, due, note, reverses;
        public final long amount;
        public final Purchase purchase;
        Entry(String id, String clientId, String type, long amount, String date, String due, String note, String reverses) {
            this(id,clientId,type,amount,date,due,note,reverses,null);
        }
        Entry(String id,String clientId,String type,long amount,String date,String due,String note,String reverses,Purchase purchase){
            this.id=id; this.clientId=clientId; this.type=type; this.amount=amount;
            this.date=date; this.due=due; this.note=note; this.reverses=reverses;this.purchase=purchase;
        }
        @Override public String getEntryId() { return id; }
        @Override public String getOwnerId() { return clientId; }
        @Override public String getOperation() { return type; }
        @Override public long getAmountKopecks() { return amount; }
        @Override public String getDueDate() { return due; }
        @Override public String getReversedId() { return reverses; }
        @Override public String getRecordedAt() { return date; }
    }
    public static final class Balance {
        public long amount, overdue;
    }
    private final Context appContext;
    private final ClientDirectory legacyDirectory;
    public LedgerStore(Context context) { this(context,"raschet-ledger.db",new ClientDirectory(context)); }
    // Package-private for instrumentation: a distinct database name prevents any
    // test from overwriting or deleting real customer records.
    LedgerStore(Context context,String databaseName) { this(context,databaseName,null); }
    LedgerStore(Context context,String databaseName,ClientDirectory legacyDirectory) {
        super(context,databaseName,null,LedgerSchema.VERSION);
        appContext=context.getApplicationContext();this.legacyDirectory=legacyDirectory;
    }
    @Override public void onCreate(SQLiteDatabase db) { LedgerSchema.create(db); }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        LedgerSchema.upgrade(db,oldVersion,newVersion,legacyDirectory);
    }
    public synchronized List<FollowUpJournal.Record> followUpHistory(){
        return FollowUpJournal.read(getReadableDatabase());
    }
    public synchronized FollowUpJournal.Record currentPlan(String client){
        return FollowUpJournal.current(getReadableDatabase(),client);
    }
    public synchronized void saveFollowUp(dev.ventry.raschet.data.ClientCommand.FollowUp command){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!hasClientId(command.getClientId()))throw new IllegalArgumentException("Клиент не найден.");
            FollowUpJournal.save(db,command.getClientId(),command.getExpectedId(),command.getDay(),
                command.getNote(),command.getAmount(),command.getRestart(),entriesForClient(command.getClientId()),LocalDate.now());
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized List<Product> products(){
        return LedgerCatalogSql.products(getReadableDatabase());
    }
    public synchronized void saveProduct(Product product){
        LedgerCatalogSql.save(getWritableDatabase(),product);
    }
    public synchronized List<dev.ventry.raschet.data.CatalogRecord> catalogRecords(){
        return LedgerCatalogSql.records(getReadableDatabase());
    }
    public synchronized void saveCatalogRecord(dev.ventry.raschet.data.CatalogRecord record){
        LedgerCatalogSql.saveRecord(getWritableDatabase(),record);
    }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    public synchronized List<Client> clients() {
        return LedgerSql.clients(getReadableDatabase());
    }
    public synchronized List<Entry> entries() { return LedgerSql.entries(getReadableDatabase(),null); }
    /** Indexed client history, including every reversal, in insertion order. */
    public synchronized List<Entry> entriesForClient(String clientId) {
        if(clientId==null)throw new IllegalArgumentException("Клиент не выбран.");
        return LedgerSql.entries(getReadableDatabase(),clientId);
    }
    /** Both ordered queries observe the same committed SQLite transaction. */
    public synchronized dev.ventry.raschet.data.LedgerSnapshot readSnapshot() {
        return LedgerSql.snapshot(getWritableDatabase());
    }
    public synchronized void addClient(String name,String phone) {
        insertClient(getWritableDatabase(),name,phone);
    }
    private String insertClient(SQLiteDatabase db,String name,String phone){
        return insertClient(db,name,phone,UUID.randomUUID().toString());
    }
    synchronized void addDraftClient(String id,String name,String phone){
        insertClient(getWritableDatabase(),name,phone,id);
    }
    /** Duplicate consent and stable draft ID checked within the write transaction. */
    public synchronized void addCheckedDraftClient(String id,String name,String phone,boolean allowDuplicate){
        UUID.fromString(id);
        checkClient(name,phone);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            if(hasClientId(id)||hasEntryId(id))
                throw new IllegalArgumentException("Этот черновик уже сохранён. Откройте книгу.");
            if(!allowDuplicate){
                String n=name.trim(),d=phone.replaceAll("[^0-9]","");
                for(Client other:clients()){
                    if(other.name.trim().equalsIgnoreCase(n)||ClientSearch.sameNumber(other.phone,d))
                        throw new IllegalArgumentException("Выберите существующего клиента или подтвердите создание отдельного.");
                }
            }
            insertClient(db,name,phone,id);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private String insertClient(SQLiteDatabase db,String name,String phone,String id){
        checkClient(name,phone);
        if(android.database.DatabaseUtils.queryNumEntries(db,"clients")>=MAX_CLIENTS)throw new IllegalArgumentException("Достигнут лимит клиентов.");
        ContentValues row=new ContentValues();
        row.put("id",id);row.put("name",name.trim());row.put("phone",phone.trim());
        db.insertOrThrow("clients",null,row);
        return id;
    }
    /** New customer and first debt commit atomically; selecting an existing ID never edits contact details. */
    public synchronized String recordDatedDebt(String existingId,String name,String phone,long amount,LocalDate day,String due,String note){
        return recordDatedDebt(existingId,name,phone,amount,day,due,note,UUID.randomUUID().toString());
    }
    synchronized boolean hasEntryId(String id){return LedgerSql.hasEntry(getReadableDatabase(),id);}
    synchronized boolean hasClientId(String id){return LedgerSql.hasClient(getReadableDatabase(),id);}
    public synchronized String recordDatedDebt(String existingId,String name,String phone,long amount,LocalDate day,String due,String note,String entryId){
        if(day==null||day.isAfter(LocalDate.now()))throw new IllegalArgumentException("Дата записи не может быть в будущем.");
        String timestamp=day.equals(LocalDate.now())?Instant.now().toString():
                day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString();
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            if(hasEntryId(entryId))throw new IllegalArgumentException("Этот черновик уже сохранён. Откройте книгу.");
            String id=existingId==null?insertClient(db,name,phone):existingId;
            insertEntry(db,id,"debt",amount,due,note,null,timestamp,entryId);
            db.setTransactionSuccessful();
            return id;
        }finally{db.endTransaction();}
    }
    /** The consent check and existing debt transaction run under one outer SQLite transaction. */
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedDebt(
            dev.ventry.raschet.data.ClientCommand.Debt command, Context context){
        UUID.fromString(command.getId());
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            String client=command.getExistingId();
            if(hasEntryId(command.getId())||hasClientId(command.getId()))
                throw new IllegalArgumentException("Этот черновик уже сохранён. Откройте книгу.");
            if(client==null){
                checkClient(command.getName(),command.getPhone());
                if(!command.getAllowDuplicate()){
                    String n=command.getName().trim(),d=command.getPhone().replaceAll("[^0-9]","");
                    for(Client other:clients())
                        if(other.name.trim().equalsIgnoreCase(n)||ClientSearch.sameNumber(other.phone,d))
                            throw new IllegalArgumentException("Выберите существующего клиента или подтвердите создание отдельного.");
                }
            }else{
                ClientDirectory directory=new ClientDirectory(context);
                long balance=balance(entriesForClient(client),client),limit=directory.debtLimit(client);
                if(directory.archived(client,balance))
                    throw new IllegalArgumentException("Сначала верните клиента из архива.");
                boolean confirmed=dev.ventry.raschet.domain.DebtApproval.matches(
                        command.getApproval(),client,balance,limit,command.getAmount());
                if(!confirmed&&!command.getApproval().isEmpty()&&DebtLimits.requiresConfirmation(limit,balance,command.getAmount()))
                    throw new IllegalArgumentException("Остаток или лимит изменился. Откройте форму заново и подтвердите превышение.");
                DebtLimits.requireConsent(limit,balance,command.getAmount(),confirmed);
            }
            int rank=journalRank(db,"debt",command.getDay(),ZoneId.systemDefault());
            String id=recordDatedDebt(client,command.getName(),command.getPhone(),command.getAmount(),
                    command.getDay(),command.getDue(),command.getNote(),command.getId());
            dev.ventry.raschet.data.WriteReceipt receipt=new dev.ventry.raschet.data.WriteReceipt(
                    command.getDestination(),"debt",id,command.getDay(),rank,false);
            db.setTransactionSuccessful();
            return receipt;
        }finally{db.endTransaction();}
    }

    /** Count journal days without decoding purchases or projecting monetary balances.
     * Reversed entries remain visible in both journals; count original operation dates.
     */
    static int journalRank(SQLiteDatabase db,String type,LocalDate day,ZoneId zone){
        return LedgerSql.journalRank(db,type,day,zone);
    }

    /** Retain the existing historical-payment guard and compute the journal position before commit. */
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedPayment(
            dev.ventry.raschet.data.ClientCommand.Payment command){
        UUID.fromString(command.getId());
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            int rank=journalRank(db,"payment",command.getDay(),ZoneId.systemDefault());
            boolean settled=balance(entriesForClient(command.getClientId()),command.getClientId())==command.getAmount();
            addDraftPayment(command.getClientId(),command.getAmount(),command.getNote(),command.getId(),command.getDay());
            if(settled){
                FollowUpJournal.Record plan=FollowUpJournal.current(db,command.getClientId());
                if(plan!=null&&plan.amount==0L)FollowUpJournal.close(db,plan,command.getId(),LocalDate.now(),"settled");
            }
            dev.ventry.raschet.data.WriteReceipt receipt=new dev.ventry.raschet.data.WriteReceipt(
                    command.getDestination(),"payment",command.getClientId(),command.getDay(),rank,settled);
            db.setTransactionSuccessful();
            return receipt;
        }finally{db.endTransaction();}
    }

    public synchronized void editClient(String id,String name,String phone) {
        checkClient(name,phone);
        ContentValues row=new ContentValues(); row.put("name",name.trim()); row.put("phone",phone.trim());
        if(getWritableDatabase().update("clients",row,"id=?",new String[]{id})!=1)
            throw new IllegalArgumentException("Клиент больше не найден.");
    }
    private static void checkClient(String name,String phone) { LedgerInput.checkClient(name,phone); }
    public static long parseAmount(String value) {
        if(value==null||!value.trim().replace(" ","").matches("\\d{1,10}([.,]\\d{1,2})?"))
            throw new IllegalArgumentException("Введите корректную сумму с точностью до копеек.");
        try {
            long result=new BigDecimal(value.replace(" ","").replace(',','.'))
                    .movePointRight(2).longValueExact();
            if(result<=0||result>MAX_AMOUNT)throw new ArithmeticException("out of range");
            return result;
        }catch(ArithmeticException ex){
            throw new IllegalArgumentException("Сумма должна быть от 0,01 до 1 000 000 000 ₽.");
        }
    }
    public synchronized void addEntry(String clientId,String type,long amount,String due,String note) {
        addEntry(clientId,type,amount,due,note,null);
    }
    public synchronized void addPurchase(String clientId,Purchase purchase,String due,String note){
        if(purchase==null)throw new IllegalArgumentException("Добавьте товары.");
        addEntry(clientId,"debt",purchase.total,due,note,purchase);
    }
    /** Revalidate the immutable purchase and exact credit approval inside one transaction. */
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedPurchase(
            dev.ventry.raschet.data.ClientCommand.ItemizedDebt command, Context context){
        UUID.fromString(command.getId());
        Purchase purchase=Purchase.decode(command.getPurchaseJson());
        if(purchase==null)throw new IllegalArgumentException("Добавьте товары.");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            if(hasEntryId(command.getId())||hasClientId(command.getId()))
                throw new IllegalArgumentException("Эта покупка уже сохранена. Откройте историю.");
            String client=command.getClientId();
            ClientDirectory directory=new ClientDirectory(context);
            long balance=balance(entriesForClient(client),client),limit=directory.debtLimit(client);
            if(directory.archived(client,balance))
                throw new IllegalArgumentException("Сначала верните клиента из архива.");
            boolean confirmed=dev.ventry.raschet.domain.DebtApproval.matches(
                    command.getApproval(),client,balance,limit,purchase.total);
            if(!confirmed&&!command.getApproval().isEmpty()&&DebtLimits.requiresConfirmation(limit,balance,purchase.total))
                throw new IllegalArgumentException("Остаток или лимит изменился. Откройте форму заново и подтвердите превышение.");
            DebtLimits.requireConsent(limit,balance,purchase.total,confirmed);
            insertEntry(db,client,"debt",purchase.total,command.getDue(),command.getNote(),purchase,
                    Instant.now().toString(),command.getId());
            dev.ventry.raschet.data.WriteReceipt receipt=new dev.ventry.raschet.data.WriteReceipt(
                    dev.ventry.raschet.data.WriteDestination.DETAIL,"purchase",client,null,0,false);
            db.setTransactionSuccessful();
            return receipt;
        }finally{db.endTransaction();}
    }
    /** Existing-client draft submission uses an immutable operation ID, just like quick debt. */
    synchronized void addDraftPayment(String clientId,long amount,String note,String entryId){
        addDraftPayment(clientId,amount,note,entryId,LocalDate.now());
    }
    synchronized void addDraftPayment(String clientId,long amount,String note,String entryId,LocalDate day){
        LocalDate today=LocalDate.now();
        if(day==null||day.isAfter(today))throw new IllegalArgumentException("Дата оплаты не может быть в будущем.");
        ZoneId zone=ZoneId.systemDefault();
        String timestamp=day.equals(today)?Instant.now().toString():day.atStartOfDay(zone).toInstant().toString();
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            if(hasEntryId(entryId))throw new IllegalArgumentException("Этот черновик уже сохранён. Откройте историю.");
            if(day.isBefore(today)&&amount>DatedPayments.maximum(entriesForClient(clientId),clientId,day,today,zone))
                throw new IllegalArgumentException("Оплата на эту дату создаст переплату в истории. Уменьшите сумму или выберите более позднюю дату.");
            insertEntry(db,clientId,"payment",amount,"",note,null,timestamp,entryId);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private synchronized void addEntry(String clientId,String type,long amount,String due,String note,Purchase purchase) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            insertEntry(db,clientId,type,amount,due,note,purchase,Instant.now().toString());
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void insertEntry(SQLiteDatabase db,String clientId,String type,long amount,String due,String note,Purchase purchase,String timestamp){
        insertEntry(db,clientId,type,amount,due,note,purchase,timestamp,UUID.randomUUID().toString());
    }
    private void insertEntry(SQLiteDatabase db,String clientId,String type,long amount,String due,String note,Purchase purchase,String timestamp,String entryId){
        if(!"debt".equals(type)&&!"payment".equals(type))throw new IllegalArgumentException("Неверная операция.");
        if(amount<=0||amount>MAX_AMOUNT)throw new IllegalArgumentException("Неверная сумма.");
        if(note==null||note.length()>200)throw new IllegalArgumentException("Комментарий слишком длинный.");
        if("debt".equals(type)) {
            dev.ventry.raschet.license.LicenseManager.requireNewDebt(appContext);
            if(due!=null&&!due.isEmpty()) { try { LocalDate.parse(due); } catch(Exception ex) {throw new IllegalArgumentException("Неверный срок.");} }
        } else due="";
        if(!LedgerSql.hasClient(db,clientId))throw new IllegalArgumentException("Клиент не найден.");
        if(android.database.DatabaseUtils.queryNumEntries(db,"entries")>=MAX_ENTRIES)throw new IllegalArgumentException("Достигнут лимит операций.");
        long available=balance(entriesForClient(clientId),clientId);
        if("payment".equals(type)&&amount>available)
            throw new IllegalArgumentException("Оплата превышает остаток долга.");
        if("debt".equals(type)&&amount>MAX_AMOUNT-available)
            throw new IllegalArgumentException("Превышен максимальный остаток.");
        Entry entry=new Entry(entryId,clientId,type,amount,timestamp,
                due==null?"":due,note,"",purchase);
        db.insertOrThrow("entries",null,LedgerSql.values(entry));
    }
    public synchronized Entry lastActive(String clientId) {
        if(clientId==null)throw new IllegalArgumentException("Клиент не выбран.");
        return LedgerCorrections.lastActive(getReadableDatabase(),clientId);
    }
    public synchronized void reverseLast(String clientId,String reason) { reverseSelected(clientId,null,reason); }
    public synchronized void reverseSelected(String clientId,String expectedId,String reason) {
        LedgerCorrections.reverse(getWritableDatabase(),clientId,expectedId,reason);
    }
    public synchronized void correctLast(String clientId,long amount,String due,String note,String reason) {
        correctSelected(clientId,null,amount,due,note,reason);
    }
    public synchronized void correctSelected(String clientId,String expectedId,long amount,String due,String note,String reason) {
        LedgerCorrections.correct(getWritableDatabase(),clientId,expectedId,amount,due,note,reason);
    }
    static long correctedBalance(long available,Entry last,long amount){
        return LedgerCorrections.correctedBalance(available,last,amount);
    }

    public static Set<String> canceled(List<Entry> list) {
        return dev.ventry.raschet.domain.LedgerProjection.canceled(list);
    }
    public static long balance(List<Entry> list,String clientId) {
        return dev.ventry.raschet.domain.LedgerProjection.balance(list,clientId);
    }
    public static long overdue(List<Entry> list,String clientId,LocalDate today) {
        return dev.ventry.raschet.domain.LedgerProjection.overdue(list,clientId,today.toString());
    }

    /** Compatibility API for callers that need a string; exports share one serializer. */
    public synchronized String exportJson() throws JSONException {
        java.io.StringWriter output=new java.io.StringWriter();
        try{writeJson(output);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        return output.toString();
    }

    /** Stream a consistent book in insertion order. Version is written last because purchases
     * determine the format; JSON object key order is not part of the v2-v4 contract.
     * The caller owns the writer and is responsible for flushing/closing it.
     */
    public synchronized void writeJson(java.io.Writer output) throws JSONException,java.io.IOException {
        LedgerJson.write(getReadableDatabase(),output);
    }

    /** Compatibility entry point; one parser owns all import validation. */
    public synchronized int[] importJson(String source) throws JSONException {
        try{return importJson(new java.io.StringReader(source));}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("Некорректный JSON.",invalid);}
    }

    /**
     * Stage one JSON object at a time on the transaction's SQLite connection.
     * Root field order is arbitrary. The current ledger remains committed until
     * the entire input and every financial invariant have been checked.
     */
    public synchronized int[] importJson(java.io.Reader source) throws JSONException,java.io.IOException {
        return LedgerJson.restore(getWritableDatabase(),source);
    }
}
