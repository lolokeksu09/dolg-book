package dev.ventry.raschet;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Device-only ledger. Never conflates the offline database with the server shop API. */
public final class LedgerStore extends SQLiteOpenHelper {
    public static final long MAX_AMOUNT = dev.ventry.raschet.domain.LedgerArithmetic.MAX_AMOUNT;
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
        LedgerWrites.saveFollowUp(getWritableDatabase(),command);
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
        LedgerWrites.insertClient(getWritableDatabase(),name,phone,UUID.randomUUID().toString());
    }
    synchronized void addDraftClient(String id,String name,String phone){
        LedgerWrites.insertClient(getWritableDatabase(),name,phone,id);
    }
    public synchronized void addCheckedDraftClient(String id,String name,String phone,boolean allowDuplicate){
        LedgerWrites.checkedClient(getWritableDatabase(),id,name,phone,allowDuplicate);
    }
    public synchronized String recordDatedDebt(String existingId,String name,String phone,long amount,LocalDate day,String due,String note){
        return recordDatedDebt(existingId,name,phone,amount,day,due,note,UUID.randomUUID().toString());
    }
    synchronized boolean hasEntryId(String id){return LedgerSql.hasEntry(getReadableDatabase(),id);}
    synchronized boolean hasClientId(String id){return LedgerSql.hasClient(getReadableDatabase(),id);}
    public synchronized String recordDatedDebt(String existingId,String name,String phone,long amount,LocalDate day,String due,String note,String entryId){
        return LedgerWrites.recordDatedDebt(getWritableDatabase(),appContext,existingId,name,phone,amount,day,due,note,entryId);
    }
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedDebt(dev.ventry.raschet.data.ClientCommand.Debt command,Context context){
        return LedgerWrites.recordCheckedDebt(getWritableDatabase(),appContext,context,command);
    }
    static int journalRank(SQLiteDatabase db,String type,LocalDate day,ZoneId zone){
        return LedgerSql.journalRank(db,type,day,zone);
    }
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedPayment(dev.ventry.raschet.data.ClientCommand.Payment command){
        return LedgerWrites.recordCheckedPayment(getWritableDatabase(),appContext,command);
    }
    public synchronized void editClient(String id,String name,String phone) {
        LedgerWrites.editClient(getWritableDatabase(),id,name,phone);
    }
    public static long parseAmount(String value) { return LedgerInput.parseAmount(value); }
    public synchronized void addEntry(String clientId,String type,long amount,String due,String note) {
        LedgerWrites.addEntry(getWritableDatabase(),appContext,clientId,type,amount,due,note,null);
    }
    public synchronized void addPurchase(String clientId,Purchase purchase,String due,String note){
        if(purchase==null)throw new IllegalArgumentException("Добавьте товары.");
        LedgerWrites.addEntry(getWritableDatabase(),appContext,clientId,"debt",purchase.total,due,note,purchase);
    }
    public synchronized dev.ventry.raschet.data.WriteReceipt recordCheckedPurchase(dev.ventry.raschet.data.ClientCommand.ItemizedDebt command,Context context){
        return LedgerWrites.recordCheckedPurchase(getWritableDatabase(),appContext,context,command);
    }
    synchronized void addDraftPayment(String clientId,long amount,String note,String entryId){
        addDraftPayment(clientId,amount,note,entryId,LocalDate.now());
    }
    synchronized void addDraftPayment(String clientId,long amount,String note,String entryId,LocalDate day){
        LedgerWrites.addDraftPayment(getWritableDatabase(),appContext,clientId,amount,note,entryId,day);
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
