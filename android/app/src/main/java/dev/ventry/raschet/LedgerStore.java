package dev.ventry.raschet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    public LedgerStore(Context context) { this(context,"raschet-ledger.db"); }
    // Package-private for instrumentation: a distinct database name prevents any
    // test from overwriting or deleting real customer records.
    LedgerStore(Context context,String databaseName) { super(context,databaseName,null,3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clients (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL)");
        db.execSQL("CREATE TABLE entries (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, client_id TEXT NOT NULL REFERENCES clients(id), type TEXT NOT NULL, amount INTEGER NOT NULL, date TEXT NOT NULL, due TEXT NOT NULL, note TEXT NOT NULL, reverses TEXT NOT NULL, purchase TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX entries_client_seq ON entries(client_id, seq)");
        createCatalog(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1||newVersion!=3)throw new IllegalStateException("Unsupported migration");
        if(oldVersion==1)db.execSQL("ALTER TABLE entries ADD COLUMN purchase TEXT NOT NULL DEFAULT ''");
        if(oldVersion<=2){createCatalog(db);return;}
        throw new IllegalStateException("Database migration missing; keep existing customer data");
    }
    private static void createCatalog(SQLiteDatabase db){
        db.execSQL("CREATE TABLE products (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,unit TEXT NOT NULL,price INTEGER NOT NULL,favorite INTEGER NOT NULL,archived INTEGER NOT NULL)");
    }
    public synchronized List<Product> products(){
        List<Product> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,category,unit,price,favorite,archived FROM products ORDER BY name COLLATE NOCASE,id",null)){
            while(c.moveToNext())result.add(new Product(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getInt(5)!=0,c.getInt(6)!=0));
        }
        return result;
    }
    public synchronized void saveProduct(Product product){
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
            List<Product> all=products();
            boolean exists=false;
            for(Product p:all){
                if(p.id.equals(product.id)){exists=true;continue;}
                if(!product.archived&&!p.archived&&p.sameName(product))
                    throw new IllegalArgumentException("Такой товар уже есть в этой категории.");
            }
            if(!exists&&all.size()>=Product.MAX_PRODUCTS)throw new IllegalArgumentException("Достигнут лимит каталога.");
            if(exists){
                if(db.update("products",productValues(product),"id=?",new String[]{product.id})!=1)
                    throw new IllegalStateException("Товар не найден.");
            }else db.insertOrThrow("products",null,productValues(product));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized List<dev.ventry.raschet.data.CatalogRecord> catalogRecords(){
        List<dev.ventry.raschet.data.CatalogRecord> result=new ArrayList<>();
        for(Product p:products())result.add(new dev.ventry.raschet.data.CatalogRecord(
                p.id,p.name,p.category,p.unit,p.price,p.favorite,p.archived));
        return java.util.Collections.unmodifiableList(result);
    }
    public synchronized void saveCatalogRecord(dev.ventry.raschet.data.CatalogRecord record){
        saveProduct(new Product(record.getId(),record.getName(),record.getCategory(),record.getUnit(),
                record.getPrice(),record.getFavorite(),record.getArchived()));
    }
    private static ContentValues productValues(Product p){
        ContentValues row=new ContentValues();
        row.put("id",p.id);row.put("name",p.name);row.put("category",p.category);
        row.put("unit",p.unit);row.put("price",p.price);row.put("favorite",p.favorite?1:0);row.put("archived",p.archived?1:0);
        return row;
    }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    public synchronized List<Client> clients() {
        List<Client> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,phone FROM clients ORDER BY name COLLATE NOCASE,id",null)) {
            while(c.moveToNext()) result.add(new Client(c.getString(0),c.getString(1),c.getString(2)));
        }
        return result;
    }
    public synchronized List<Entry> entries() { return readEntries(null); }
    /** Indexed client history, including every reversal, in insertion order. */
    public synchronized List<Entry> entriesForClient(String clientId) {
        if(clientId==null)throw new IllegalArgumentException("Клиент не выбран.");
        return readEntries(clientId);
    }
    private List<Entry> readEntries(String clientId) {
        List<Entry> result=new ArrayList<>();
        String query="SELECT id,client_id,type,amount,date,due,note,reverses,purchase FROM entries"+
                (clientId==null?"":" WHERE client_id=?")+" ORDER BY seq";
        try(Cursor c=getReadableDatabase().rawQuery(query,clientId==null?null:new String[]{clientId})) {
            while(c.moveToNext()) result.add(new Entry(c.getString(0),c.getString(1),c.getString(2),
                    c.getLong(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),Purchase.decode(c.getString(8))));
        }
        return result;
    }
    /** Both ordered queries observe the same committed SQLite transaction. */
    public synchronized dev.ventry.raschet.data.LedgerSnapshot readSnapshot() {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            dev.ventry.raschet.data.LedgerSnapshot snapshot=
                    new dev.ventry.raschet.data.LedgerSnapshot(clients(),entries());
            db.setTransactionSuccessful();
            return snapshot;
        }finally{db.endTransaction();}
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
    synchronized boolean hasEntryId(String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM entries WHERE id=?",new String[]{id})){return c.moveToFirst();}
    }
    synchronized boolean hasClientId(String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM clients WHERE id=?",new String[]{id})){return c.moveToFirst();}
    }
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
        if(day==null)throw new IllegalArgumentException("Дата не выбрана.");
        Set<LocalDate> later=new HashSet<>();
        try(Cursor cursor=db.rawQuery("SELECT DISTINCT date FROM entries WHERE type=?",new String[]{type})){
            while(cursor.moveToNext()){
                LocalDate date=dev.ventry.raschet.domain.DatedLedger.localDate(cursor.getString(0),zone);
                if(date.isAfter(day))later.add(date);
            }
        }
        return 1+later.size();
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
    private static void checkClient(String name,String phone) {
        if(name==null||name.trim().isEmpty()||name.trim().length()>80||phone==null||phone.trim().length()>30)
            throw new IllegalArgumentException("Имя: до 80 знаков. Телефон: до 30.");
    }
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
            if(due!=null&&!due.isEmpty()) { try { LocalDate.parse(due); } catch(Exception ex) {throw new IllegalArgumentException("Неверный срок.");} }
        } else due="";
            boolean exists=false;
            try(Cursor c=db.rawQuery("SELECT 1 FROM clients WHERE id=?",new String[]{clientId})) {exists=c.moveToFirst();}
            if(!exists)throw new IllegalArgumentException("Клиент не найден.");
            if(android.database.DatabaseUtils.queryNumEntries(db,"entries")>=MAX_ENTRIES)throw new IllegalArgumentException("Достигнут лимит операций.");
            long available=balance(entriesForClient(clientId),clientId);
            if("payment".equals(type)&&amount>available)
                throw new IllegalArgumentException("Оплата превышает остаток долга.");
            if("debt".equals(type)&&amount>MAX_AMOUNT-available)
                throw new IllegalArgumentException("Превышен максимальный остаток.");
            Entry entry=new Entry(entryId,clientId,type,amount,timestamp,
                    due==null?"":due,note,"",purchase);
            db.insertOrThrow("entries",null,values(entry));
    }
    public synchronized Entry lastActive(String clientId) {
        Set<String> undone=new HashSet<>();
        List<Entry> all=entriesForClient(clientId);
        for(Entry e:all)if("reversal".equals(e.type))undone.add(e.reverses);
        for(int i=all.size()-1;i>=0;i--){
            Entry e=all.get(i);
            if(e.clientId.equals(clientId)&&!"reversal".equals(e.type)&&!undone.contains(e.id))return e;
        }
        return null;
    }
    public synchronized void reverseLast(String clientId,String reason) {
        reverseSelected(clientId,null,reason);
    }
    /** A dialog may be stale: never reverse a different operation than the one displayed. */
    public synchronized void reverseSelected(String clientId,String expectedId,String reason) {
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>200)
            throw new IllegalArgumentException("Укажите причину отмены.");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            Entry last=lastActive(clientId);
            if(last==null)throw new IllegalArgumentException("Нет действующей операции для отмены.");
            if(expectedId!=null&&!expectedId.equals(last.id))throw new IllegalArgumentException("Запись уже не последняя. Откройте актуальную историю.");
            if(android.database.DatabaseUtils.queryNumEntries(db,"entries")>=MAX_ENTRIES)throw new IllegalArgumentException("Достигнут лимит операций.");
            long b=balance(entriesForClient(clientId),clientId);
            if("debt".equals(last.type)&&b<last.amount)
                throw new IllegalArgumentException("Этот долг уже частично погашен. Сначала отмените последнюю оплату.");
            Entry undo=new Entry(UUID.randomUUID().toString(),clientId,"reversal",last.amount,Instant.now().toString(),"",reason.trim(),last.id);
            db.insertOrThrow("entries",null,values(undo));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /**
     * Correct only the last active operation of a customer. A reversal and
     * replacement are committed together; failures leave the book unchanged.
     * This deliberately preserves the v2 JSON sequential reversal contract.
     */
    public synchronized void correctLast(String clientId,long amount,String due,
            String note,String reason) {
        correctSelected(clientId,null,amount,due,note,reason);
    }
    public synchronized void correctSelected(String clientId,String expectedId,long amount,String due,String note,String reason) {
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>200)
            throw new IllegalArgumentException("Укажите причину исправления.");
        if(note==null||note.length()>200)
            throw new IllegalArgumentException("Комментарий слишком длинный.");
        if(amount<=0||amount>MAX_AMOUNT)
            throw new IllegalArgumentException("Неверная сумма.");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            Entry last=lastActive(clientId);
            if(last==null)throw new IllegalArgumentException("Нет операции для исправления.");
            if(expectedId!=null&&!expectedId.equals(last.id))throw new IllegalArgumentException("Запись уже не последняя. Откройте актуальную историю.");
            if("debt".equals(last.type)){
                if(due==null)due="";
                if(!due.isEmpty())try{LocalDate.parse(due);}
                    catch(Exception ex){throw new IllegalArgumentException("Неверный срок.");}
            }else due="";
            List<Entry> all=entriesForClient(clientId);
            if(android.database.DatabaseUtils.queryNumEntries(db,"entries")+2>MAX_ENTRIES)
                throw new IllegalArgumentException("Недостаточно места в истории операций.");
            correctedBalance(balance(all,clientId),last,amount);
            if(last.purchase!=null&&amount!=last.amount)
                throw new IllegalArgumentException("Для изменения суммы покупки отмените её и создайте новую с правильными товарами.");
            if(last.amount==amount&&last.due.equals(due)&&last.note.equals(note))
                throw new IllegalArgumentException("Новые данные совпадают со старыми.");
            String timestamp=Instant.now().toString();
            Entry undo=new Entry(UUID.randomUUID().toString(),clientId,"reversal",
                    last.amount,timestamp,"",reason.trim(),last.id);
            Entry corrected=new Entry(UUID.randomUUID().toString(),clientId,last.type,
                    amount,timestamp,due,note,"",last.purchase);
            db.insertOrThrow("entries",null,values(undo));
            db.insertOrThrow("entries",null,values(corrected));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    /** Same balance guard for correction preview and actual transaction. Zero is reversal only. */
    static long correctedBalance(long available,Entry last,long amount){
        if(last==null)throw new IllegalArgumentException("Неверная операция.");
        return dev.ventry.raschet.bridge.CorrectionEngine.correctedBalance(available,last.type,last.amount,amount);
    }

    private static ContentValues values(Entry entry) {
        ContentValues row=new ContentValues();
        row.put("id",entry.id);row.put("client_id",entry.clientId);row.put("type",entry.type);
        row.put("amount",entry.amount);row.put("date",entry.date);row.put("due",entry.due);
        row.put("note",entry.note);row.put("reverses",entry.reverses);
        row.put("purchase",entry.purchase==null?"":entry.purchase.encode());
        return row;
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

    /** Compatible with the original web book JSON format, including v1 and v2 reversals. */
    public synchronized String exportJson() throws JSONException {
        SQLiteDatabase db=getReadableDatabase();
        db.beginTransaction();
        try {
        JSONObject root=new JSONObject();root.put("version",2);
        JSONArray cs=new JSONArray(),es=new JSONArray();
        for(Client c:clients()) cs.put(new JSONObject().put("id",c.id).put("name",c.name).put("phone",c.phone));
        for(Entry e:entries()){
            JSONObject object=new JSONObject().put("id",e.id).put("clientId",e.clientId).put("type",e.type)
                .put("amount",e.amount).put("date",e.date).put("due",e.due).put("note",e.note);
            if("reversal".equals(e.type))object.put("reverses",e.reverses);
            if(e.purchase!=null){root.put("version",e.purchase.hasUnits()?4:Math.max(root.getInt("version"),3));object.put("purchase",new JSONObject(e.purchase.encode()));}
            es.put(object);
        }
        List<Product> catalog=products();
        if(!catalog.isEmpty()||root.getInt("version")==4){
            root.put("version",4);
            JSONArray ps=new JSONArray();
            for(Product p:catalog)ps.put(p.json());
            root.put("products",ps);
        }
        String json=root.put("clients",cs).put("entries",es).toString();
        db.setTransactionSuccessful();
        return json;
        }finally{db.endTransaction();}
    }

    /** Parse and verify all rows before replacing anything; apply the replacement in one SQLite transaction. */
    public synchronized int[] importJson(String source) throws JSONException {
        JSONObject book=new JSONObject(source);
        int version=book.getInt("version");
        if(version<1||version>4)throw new IllegalArgumentException("Поддерживаются копии v1–v4.");
        List<Product> newProducts=new ArrayList<>();
        if(version==4){
            JSONArray ps=book.getJSONArray("products");
            if(ps.length()>Product.MAX_PRODUCTS)throw new IllegalArgumentException("Каталог слишком большой.");
            Set<String> productIds=new HashSet<>(),productKeys=new HashSet<>();
            for(int i=0;i<ps.length();i++){
                Product p=Product.fromJson(ps.getJSONObject(i));
                if(!productIds.add(p.id)||(!p.archived&&!productKeys.add(p.normalizedKey())))
                    throw new IllegalArgumentException("Дубли в каталоге.");
                newProducts.add(p);
            }
        }else if(book.has("products"))throw new IllegalArgumentException("Каталог требует JSON v4.");
        JSONArray cs=book.getJSONArray("clients"),es=book.getJSONArray("entries");
        if(cs.length()>MAX_CLIENTS||es.length()>MAX_ENTRIES)throw new IllegalArgumentException("Книга слишком большая.");
        List<Client> newClients=new ArrayList<>();
        List<Entry> newEntries=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(int i=0;i<cs.length();i++){
            JSONObject c=cs.getJSONObject(i);
            String id=c.getString("id"),name=c.getString("name"),phone=c.getString("phone");
            checkClient(name,phone);
            if(id.isEmpty()||id.length()>100||!ids.add(id))throw new IllegalArgumentException("Повтор или неверный ID клиента.");
            newClients.add(new Client(id,name.trim(),phone));
        }
        Set<String> entryIds=new HashSet<>();
        dev.ventry.raschet.domain.ImportLedgerValidation history=
                new dev.ventry.raschet.domain.ImportLedgerValidation(version,ids);
        for(int i=0;i<es.length();i++){
            JSONObject j=es.getJSONObject(i);
            String id=j.getString("id"),cid=j.getString("clientId"),type=j.getString("type"),
                    date=j.getString("date"),due=j.getString("due"),note=j.getString("note"),
                    reverses=j.optString("reverses","");
            Object rawAmount=j.get("amount");
            if(!(rawAmount instanceof Number) || !rawAmount.toString().matches("\\d{1,12}"))
                throw new IllegalArgumentException("Сумма операции должна быть целым числом копеек.");
            long amount=Long.parseLong(rawAmount.toString());
            if(id.isEmpty()||id.length()>100||!entryIds.add(id)||!ids.contains(cid)
                    ||!type.equals("debt")&&!type.equals("payment")&&!type.equals("reversal")
                    ||amount<=0||amount>MAX_AMOUNT||note.length()>200||date.length()>40
                    ||due.length()>10)throw new IllegalArgumentException("Некорректная запись.");
            try { Instant.parse(date); } catch(Exception ex) {
                try { java.time.OffsetDateTime.parse(date); } catch(Exception e) {throw new IllegalArgumentException("Неверная дата операции.");}
            }
            if(!due.isEmpty()) {
                try {LocalDate.parse(due);}catch(Exception ex){throw new IllegalArgumentException("Неверный срок возврата.");}
                if(!type.equals("debt"))throw new IllegalArgumentException("Срок задан не для долга.");
            }
            Purchase purchase=null;
            if(j.has("purchase")){
                if(version<3||!type.equals("debt"))throw new IllegalArgumentException("Состав покупки разрешён только для долга в v3/v4.");
                purchase=Purchase.decode(j.getJSONObject("purchase").toString());
                if(purchase.total!=amount||(version<4&&purchase.hasUnits()))throw new IllegalArgumentException("Неверный состав или сумма покупки.");
            }
            Entry e=new Entry(id,cid,type,amount,date,due,note,reverses,purchase);
            history.accept(cid,id,type,amount,reverses,note);
            newEntries.add(e);
        }
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("products",null,null);
            for(Product p:newProducts)db.insertOrThrow("products",null,productValues(p));
            db.delete("entries",null,null);
            db.delete("clients",null,null);
            for(Client c:newClients){
                ContentValues row=new ContentValues();
                row.put("id",c.id);row.put("name",c.name);row.put("phone",c.phone);
                db.insertOrThrow("clients",null,row);
            }
            for(Entry e:newEntries)db.insertOrThrow("entries",null,values(e));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return new int[]{newClients.size(),newEntries.size()};
    }
}
