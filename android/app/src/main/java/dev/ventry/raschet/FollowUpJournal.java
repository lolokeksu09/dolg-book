package dev.ventry.raschet;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.json.JSONException;
import java.time.LocalDate;
import java.util.*;

/** One append-only sequence of agreement intervals per client, in the same DB as the ledger.
 * A payment can belong to at most one interval; closing a plan never edits a financial entry.
 * Legacy rows preserve their current agreement, not a fictional creation date or prior history.
 */
public final class FollowUpJournal {
    static final int MAX_PLANS=100_000;
    private static final String COLUMNS="id,client_id,due,original_due,note,amount,anchor,end_anchor,opened,closed,reason";
    private FollowUpJournal(){}
    public static final class Record {
        public final String id,clientId,note,anchor,endAnchor,reason;
        public final LocalDate due,originalDue,opened,closed;
        public final long amount;
        Record(String id,String clientId,LocalDate due,LocalDate originalDue,String note,long amount,
               String anchor,String endAnchor,LocalDate opened,LocalDate closed,String reason){
            this.id=id;this.clientId=clientId;this.due=due;this.originalDue=originalDue;
            this.note=note;this.amount=amount;this.anchor=anchor;this.endAnchor=endAnchor;
            this.opened=opened;this.closed=closed;this.reason=reason;
        }
        public boolean active(){return closed==null;}
        ClientDirectory.FollowUp plan(){return new ClientDirectory.FollowUp(due,note,amount,anchor);}
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("clientId",clientId).put("due",due.toString())
                .put("originalDue",originalDue.toString()).put("note",note).put("amount",amount)
                .put("anchor",anchor).put("endAnchor",endAnchor).put("opened",date(opened))
                .put("closed",date(closed)).put("reason",reason);
        }
        static Record parse(JSONObject j) throws JSONException {
            if(j.length()!=11)throw new IllegalArgumentException("Некорректный план возврата.");
            Object value=j.get("amount");
            if(!(value instanceof Number)||!value.toString().matches("[0-9]{1,12}"))
                throw new IllegalArgumentException("Неверная сумма обещания.");
            String id=str(j,"id"),client=str(j,"clientId"),note=str(j,"note"),anchor=str(j,"anchor"),
                end=str(j,"endAnchor"),reason=str(j,"reason");
            LocalDate due=requiredDate(str(j,"due")),original=requiredDate(str(j,"originalDue")),
                opened=optionalDate(str(j,"opened")),closed=optionalDate(str(j,"closed"));
            long amount=Long.parseLong(value.toString());
            if(!UUID.fromString(id).toString().equals(id)||client.isEmpty()||client.length()>100
                ||note.length()>200||amount<0||amount>LedgerStore.MAX_AMOUNT||anchor.length()>100||end.length()>100
                ||(closed==null?(!reason.isEmpty()||!end.isEmpty()):!Arrays.asList("replaced","removed","settled").contains(reason))
                ||(amount>0&&anchor.isEmpty()&&opened!=null))
                throw new IllegalArgumentException("Некорректная запись истории обещаний.");
            return new Record(id,client,due,original,note,amount,anchor,end,opened,closed,reason);
        }
        private static String str(JSONObject j,String key)throws JSONException{
            Object v=j.get(key);if(!(v instanceof String))throw new IllegalArgumentException("Некорректное поле плана.");
            return (String)v;
        }
    }
    private static String date(LocalDate d){return d==null?"":d.toString();}
    private static LocalDate requiredDate(String v){
        if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("Неверная дата плана.");
        return LocalDate.parse(v);
    }
    private static LocalDate optionalDate(String v){return v.isEmpty()?null:requiredDate(v);}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE collection_plans (seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,client_id TEXT NOT NULL REFERENCES clients(id),due TEXT NOT NULL,original_due TEXT NOT NULL,note TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>=0),anchor TEXT NOT NULL,end_anchor TEXT NOT NULL,opened TEXT NOT NULL,closed TEXT NOT NULL,reason TEXT NOT NULL)");
        db.execSQL("CREATE INDEX collection_plan_client_seq ON collection_plans(client_id,seq)");
        db.execSQL("CREATE UNIQUE INDEX collection_plan_active ON collection_plans(client_id) WHERE closed=''");
    }
    private static Record row(Cursor c){
        return new Record(c.getString(0),c.getString(1),requiredDate(c.getString(2)),requiredDate(c.getString(3)),
            c.getString(4),c.getLong(5),c.getString(6),c.getString(7),optionalDate(c.getString(8)),
            optionalDate(c.getString(9)),c.getString(10));
    }
    static List<Record> read(SQLiteDatabase db){
        List<Record> result=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM collection_plans ORDER BY seq",null)){
            while(c.moveToNext())result.add(row(c));
        }
        return result;
    }
    static Record current(SQLiteDatabase db,String client){
        try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM collection_plans WHERE client_id=? AND closed=''",new String[]{client})){
            return c.moveToFirst()?row(c):null;
        }
    }
    private static ContentValues values(Record r){
        ContentValues v=new ContentValues();
        v.put("id",r.id);v.put("client_id",r.clientId);v.put("due",date(r.due));
        v.put("original_due",date(r.originalDue));v.put("note",r.note);v.put("amount",r.amount);
        v.put("anchor",r.anchor);v.put("end_anchor",r.endAnchor);v.put("opened",date(r.opened));
        v.put("closed",date(r.closed));v.put("reason",r.reason);return v;
    }
    static void insert(SQLiteDatabase db,Record r){db.insertOrThrow("collection_plans",null,values(r));}
    static void close(SQLiteDatabase db,Record r,String end,LocalDate today,String reason){
        ContentValues v=new ContentValues();v.put("closed",date(today));v.put("end_anchor",end);v.put("reason",reason);
        if(db.update("collection_plans",v,"id=? AND closed=''",new String[]{r.id})!=1)
            throw new IllegalStateException("План уже изменён. Откройте книгу.");
    }
    private static Record fresh(String client,LocalDate day,String note,long amount,String anchor,LocalDate opened){
        return new Record(UUID.randomUUID().toString(),client,day,day,note,amount,anchor,"",opened,null,"");
    }
    private static boolean ownsEntry(SQLiteDatabase db,String client,String anchor){
        if(anchor.isEmpty())return false;
        try(Cursor c=db.rawQuery("SELECT 1 FROM entries WHERE id=? AND client_id=?",new String[]{anchor,client})){
            return c.moveToFirst();
        }
    }
    static void migrate(SQLiteDatabase db,ClientDirectory legacy){
        try(Cursor c=db.rawQuery("SELECT id FROM clients ORDER BY id",null)){
            while(c.moveToNext()){
                String id=c.getString(0);
                ClientDirectory.FollowUp f=legacy.followUp(id);
                if(f==null)continue;
                // Preserve unknown anchors as an explicit review state. Never invent payments.
                String anchor=ownsEntry(db,id,f.anchor)?f.anchor:"";
                if(f.amount<0||f.amount>LedgerStore.MAX_AMOUNT||f.note.length()>200)
                    throw new IllegalArgumentException("Некорректный старый план возврата.");
                insert(db,fresh(id,f.date,f.note,f.amount,anchor,null));
            }
        }
    }
    /** Called under LedgerStore's write transaction; optimistic identity prevents stale-form overwrite. */
    static void save(SQLiteDatabase db,String client,String expected,LocalDate day,String note,long amount,
                     boolean restart,List<LedgerStore.Entry> entries,LocalDate today){
        Record old=current(db,client);
        if(!Objects.equals(expected,old==null?null:old.id))
            throw new IllegalArgumentException("План изменился. Откройте его заново.");
        if(note==null||note.length()>200||amount<0||amount>LedgerStore.MAX_AMOUNT)
            throw new IllegalArgumentException("Неверная сумма или комментарий плана.");
        String last=PaymentPromises.lastEntryId(entries,client);
        if(day==null){
            if(old!=null)close(db,old,last,today,"removed");
            return;
        }
        long balance=LedgerStore.balance(entries,client);
        boolean keep=old!=null&&!restart&&old.amount==amount
            &&(amount==0||ownsEntry(db,client,old.anchor));
        if(keep){
            if(amount==0&&balance<=0)throw new IllegalArgumentException("Долг уже погашен.");
            ContentValues v=new ContentValues();v.put("due",day.toString());v.put("note",note.trim());
            if(db.update("collection_plans",v,"id=? AND closed=''",new String[]{old.id})!=1)
                throw new IllegalStateException("План уже изменён.");
            return;
        }
        if(balance<=0||amount>balance||amount>0&&(day.isBefore(today)||last.isEmpty()))
            throw new IllegalArgumentException("Новое обещание: сумма не выше остатка, дата не раньше сегодняшней.");
        if(android.database.DatabaseUtils.queryNumEntries(db,"collection_plans")>=MAX_PLANS)
            throw new IllegalArgumentException("Достигнут лимит истории планов.");
        if(old!=null)close(db,old,last,today,"replaced");
        insert(db,fresh(client,day,note.trim(),amount,last,today));
    }
    /** Import validation: ordered intervals cannot overlap or reference another client's entries.
     * Blank anchors are permitted only for migrated unknown records / contact-only plans.
     */
    static final class ImportCheck {
        private final Map<String,String> owners=new HashMap<>();
        private final Map<String,Long> ranks=new HashMap<>(),previousEnd=new HashMap<>();
        private final Set<String> clients,ids=new HashSet<>(),open=new HashSet<>(),seen=new HashSet<>();
        ImportCheck(SQLiteDatabase db,Set<String> clients){
            this.clients=clients;
            try(Cursor c=db.rawQuery("SELECT id,client_id,seq FROM entries ORDER BY seq",null)){
                while(c.moveToNext()){owners.put(c.getString(0),c.getString(1));ranks.put(c.getString(0),c.getLong(2));}
            }
        }
        private long rank(String id,String client){
            if(id.isEmpty())return -1L;
            if(!client.equals(owners.get(id)))throw new IllegalArgumentException("План ссылается на чужую или отсутствующую запись.");
            return ranks.get(id);
        }
        void accept(Record r){
            if(!clients.contains(r.clientId)||!ids.add(r.id)||open.contains(r.clientId)
                ||r.opened==null&&seen.contains(r.clientId))
                throw new IllegalArgumentException("Неверная последовательность планов.");
            long start=rank(r.anchor,r.clientId),end=rank(r.endAnchor,r.clientId);
            long previous=previousEnd.getOrDefault(r.clientId,-1L);
            if(start<previous||(!r.active()&&end<start))
                throw new IllegalArgumentException("Пересекающиеся интервалы обещаний.");
            if(r.active())open.add(r.clientId);else previousEnd.put(r.clientId,end);
            seen.add(r.clientId);
        }
    }
}
