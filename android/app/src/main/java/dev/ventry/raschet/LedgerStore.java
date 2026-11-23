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
    public static final long MAX_AMOUNT = 100_000_000_000L;
    private static final int MAX_CLIENTS = 10_000, MAX_ENTRIES = 100_000;
    public static final class Client {
        public final String id, name, phone;
        Client(String id, String name, String phone) { this.id=id; this.name=name; this.phone=phone; }
    }
    public static final class Entry {
        public final String id, clientId, type, date, due, note, reverses;
        public final long amount;
        Entry(String id, String clientId, String type, long amount, String date, String due, String note, String reverses) {
            this.id=id; this.clientId=clientId; this.type=type; this.amount=amount;
            this.date=date; this.due=due; this.note=note; this.reverses=reverses;
        }
    }
    public static final class Balance {
        public long amount, overdue;
    }
    public LedgerStore(Context context) { super(context,"raschet-ledger.db",null,1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clients (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL)");
        db.execSQL("CREATE TABLE entries (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, client_id TEXT NOT NULL REFERENCES clients(id), type TEXT NOT NULL, amount INTEGER NOT NULL, date TEXT NOT NULL, due TEXT NOT NULL, note TEXT NOT NULL, reverses TEXT NOT NULL)");
        db.execSQL("CREATE INDEX entries_client_seq ON entries(client_id, seq)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        throw new IllegalStateException("Database migration missing; keep existing customer data");
    }
    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    public synchronized List<Client> clients() {
        List<Client> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,phone FROM clients ORDER BY name COLLATE NOCASE,id",null)) {
            while(c.moveToNext()) result.add(new Client(c.getString(0),c.getString(1),c.getString(2)));
        }
        return result;
    }
    public synchronized List<Entry> entries() {
        List<Entry> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,client_id,type,amount,date,due,note,reverses FROM entries ORDER BY seq",null)) {
            while(c.moveToNext()) result.add(new Entry(c.getString(0),c.getString(1),c.getString(2),
                    c.getLong(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7)));
        }
        return result;
    }
    public synchronized void addClient(String name,String phone) {
        checkClient(name,phone);
        if(clients().size()>=MAX_CLIENTS)throw new IllegalArgumentException("Достигнут лимит клиентов.");
        ContentValues row=new ContentValues();
        row.put("id",UUID.randomUUID().toString()); row.put("name",name.trim()); row.put("phone",phone.trim());
        getWritableDatabase().insertOrThrow("clients",null,row);
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
        if(!"debt".equals(type)&&!"payment".equals(type))throw new IllegalArgumentException("Неверная операция.");
        if(amount<=0||amount>MAX_AMOUNT)throw new IllegalArgumentException("Неверная сумма.");
        if(note==null||note.length()>200)throw new IllegalArgumentException("Комментарий слишком длинный.");
        if("debt".equals(type)) {
            if(due!=null&&!due.isEmpty()) { try { LocalDate.parse(due); } catch(Exception ex) {throw new IllegalArgumentException("Неверный срок.");} }
        } else due="";
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            boolean exists=false;
            try(Cursor c=db.rawQuery("SELECT 1 FROM clients WHERE id=?",new String[]{clientId})) {exists=c.moveToFirst();}
            if(!exists)throw new IllegalArgumentException("Клиент не найден.");
            List<Entry> all=entries();
            if(all.size()>=MAX_ENTRIES)throw new IllegalArgumentException("Достигнут лимит операций.");
            long available=balance(all,clientId);
            if("payment".equals(type)&&amount>available)
                throw new IllegalArgumentException("Оплата превышает остаток долга.");
            if("debt".equals(type)&&amount>MAX_AMOUNT-available)
                throw new IllegalArgumentException("Превышен максимальный остаток.");
            Entry entry=new Entry(UUID.randomUUID().toString(),clientId,type,amount,Instant.now().toString(),
                    due==null?"":due,note,"");
            db.insertOrThrow("entries",null,values(entry));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized Entry lastActive(String clientId) {
        Set<String> undone=new HashSet<>();
        List<Entry> all=entries();
        for(Entry e:all)if("reversal".equals(e.type))undone.add(e.reverses);
        for(int i=all.size()-1;i>=0;i--){
            Entry e=all.get(i);
            if(e.clientId.equals(clientId)&&!"reversal".equals(e.type)&&!undone.contains(e.id))return e;
        }
        return null;
    }
    public synchronized void reverseLast(String clientId,String reason) {
        if(reason==null||reason.trim().isEmpty()||reason.trim().length()>200)
            throw new IllegalArgumentException("Укажите причину отмены.");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            Entry last=lastActive(clientId);
            if(last==null)throw new IllegalArgumentException("Нет действующей операции для отмены.");
            List<Entry> all=entries();
            long b=balance(all,clientId);
            if("debt".equals(last.type)&&b<last.amount)
                throw new IllegalArgumentException("Этот долг уже частично погашен. Сначала отмените последнюю оплату.");
            Entry undo=new Entry(UUID.randomUUID().toString(),clientId,"reversal",last.amount,Instant.now().toString(),"",reason.trim(),last.id);
            db.insertOrThrow("entries",null,values(undo));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static ContentValues values(Entry entry) {
        ContentValues row=new ContentValues();
        row.put("id",entry.id);row.put("client_id",entry.clientId);row.put("type",entry.type);
        row.put("amount",entry.amount);row.put("date",entry.date);row.put("due",entry.due);
        row.put("note",entry.note);row.put("reverses",entry.reverses);
        return row;
    }
    public static Set<String> canceled(List<Entry> list) {
        Set<String> removed=new HashSet<>();
        for(Entry e:list)if("reversal".equals(e.type))removed.add(e.reverses);
        return removed;
    }
    public static long balance(List<Entry> list,String clientId) {
        Set<String> reversed=canceled(list);
        long sum=0;
        for(Entry e:list) if(e.clientId.equals(clientId)&&!"reversal".equals(e.type)&&!reversed.contains(e.id))
            sum+= "debt".equals(e.type) ? e.amount : -e.amount;
        return sum;
    }
    public static long overdue(List<Entry> list,String clientId,LocalDate today) {
        Set<String> reversed=canceled(list);
        long totalPaid=0;
        for(Entry e:list)if(e.clientId.equals(clientId)&&"payment".equals(e.type)&&!reversed.contains(e.id))totalPaid+=e.amount;
        long overdue=0;
        for(Entry e:list){
            if(!e.clientId.equals(clientId)||!"debt".equals(e.type)||reversed.contains(e.id))continue;
            long paid=Math.min(totalPaid,e.amount);
            totalPaid-=paid;
            if(!e.due.isEmpty() && e.due.compareTo(today.toString())<0)overdue+=e.amount-paid;
        }
        return overdue;
    }

    /** Compatible with the original web book JSON format, including v1 and v2 reversals. */
    public synchronized String exportJson() throws JSONException {
        JSONObject root=new JSONObject();root.put("version",2);
        JSONArray cs=new JSONArray(),es=new JSONArray();
        for(Client c:clients()) cs.put(new JSONObject().put("id",c.id).put("name",c.name).put("phone",c.phone));
        for(Entry e:entries()){
            JSONObject object=new JSONObject().put("id",e.id).put("clientId",e.clientId).put("type",e.type)
                .put("amount",e.amount).put("date",e.date).put("due",e.due).put("note",e.note);
            if("reversal".equals(e.type))object.put("reverses",e.reverses);
            es.put(object);
        }
        return root.put("clients",cs).put("entries",es).toString(2);
    }

    /** Parse and verify all rows before replacing anything; apply the replacement in one SQLite transaction. */
    public synchronized int[] importJson(String source) throws JSONException {
        JSONObject book=new JSONObject(source);
        int version=book.getInt("version");
        if(version!=1 && version!=2)throw new IllegalArgumentException("Поддерживаются копии v1 и v2.");
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
        Map<String,Long> sums=new HashMap<>();
        Map<String,List<Entry>> active=new HashMap<>();
        for(Client c:newClients){sums.put(c.id,0L);active.put(c.id,new ArrayList<>());}
        for(int i=0;i<es.length();i++){
            JSONObject j=es.getJSONObject(i);
            String id=j.getString("id"),cid=j.getString("clientId"),type=j.getString("type"),
                    date=j.getString("date"),due=j.getString("due"),note=j.getString("note"),
                    reverses=j.optString("reverses","");
            Object rawAmount=j.get("amount");
            if(!(rawAmount instanceof Number) || !rawAmount.toString().matches("\\d{1,12}"))
                throw new IllegalArgumentException("Сумма операции должна быть целым числом копеек.");
            long amount=Long.parseLong(rawAmount.toString());
            if(id.isEmpty()||id.length()>100||!entryIds.add(id)||!sums.containsKey(cid)
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
            Entry e=new Entry(id,cid,type,amount,date,due,note,reverses);
            long delta;
            List<Entry> stack=active.get(cid);
            if(type.equals("reversal")){
                if(version==1||stack.isEmpty()||note.trim().isEmpty())
                    throw new IllegalArgumentException("Некорректная отмена.");
                Entry previous=stack.get(stack.size()-1);
                if(!previous.id.equals(reverses)||previous.amount!=amount)
                    throw new IllegalArgumentException("Неверная связь отмены.");
                stack.remove(stack.size()-1);
                delta=previous.type.equals("debt")?-amount:amount;
            }else{
                if(!reverses.isEmpty())throw new IllegalArgumentException("Неверная ссылка.");
                stack.add(e);
                delta=type.equals("debt")?amount:-amount;
            }
            long current=sums.get(cid),updated=current+delta;
            if(updated<0||updated>MAX_AMOUNT)throw new IllegalArgumentException("Книга содержит переплату или слишком большой остаток.");
            sums.put(cid,updated);
            newEntries.add(e);
        }
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
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
