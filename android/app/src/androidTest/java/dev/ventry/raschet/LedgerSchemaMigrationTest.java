package dev.ventry.raschet;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.junit.Assert.*;

/** Independent legacy fixtures; only isolated database/preference names are touched. */
@RunWith(AndroidJUnit4.class)
public class LedgerSchemaMigrationTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String DB="raschet-schema-migration-qa.db";
    private static final String PREFS="raschet-schema-migration-qa";
    private static final String CUSTOMER="customer";
    private static final LocalDate DAY=LocalDate.of(2026,11,21);

    private void cleanup(){
        context.deleteDatabase(DB);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit();
    }
    private void seed(int version){
        try(SQLiteDatabase db=context.openOrCreateDatabase(DB,Context.MODE_PRIVATE,null)){
            db.execSQL("CREATE TABLE clients (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL)");
            db.execSQL("CREATE TABLE entries (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, client_id TEXT NOT NULL REFERENCES clients(id), type TEXT NOT NULL, amount INTEGER NOT NULL, date TEXT NOT NULL, due TEXT NOT NULL, note TEXT NOT NULL, reverses TEXT NOT NULL"+(version>=2?", purchase TEXT NOT NULL DEFAULT ''":"")+")");
            db.execSQL("CREATE INDEX entries_client_seq ON entries(client_id, seq)");
            if(version>=3){
                db.execSQL("CREATE TABLE products (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,unit TEXT NOT NULL,price INTEGER NOT NULL,favorite INTEGER NOT NULL,archived INTEGER NOT NULL)");
                db.execSQL("INSERT INTO products VALUES('product','Яблоко','Фрукты','кг',15000,1,0)");
            }
            db.execSQL("INSERT INTO clients VALUES('customer','Тестовый клиент','+70000000000')");
            db.execSQL("INSERT INTO entries(id,client_id,type,amount,date,due,note,reverses) VALUES('debt','customer','debt',35000,'2026-11-21T09:00:00Z','','Тест','')");
            db.execSQL("INSERT INTO entries(id,client_id,type,amount,date,due,note,reverses) VALUES('payment','customer','payment',10000,'2026-11-21T10:00:00Z','','Оплата','')");
            db.setVersion(version);
        }
    }
    private boolean table(SQLiteDatabase db,String name){
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{name})){
            return c.moveToFirst();
        }
    }
    @Test public void allLegacyVersionsPreserveIdsBalancesPlansAndCatalog() throws Exception {
        for(int version=1;version<=3;version++){
            cleanup();
            try{
                seed(version);
                ClientDirectory legacy=new ClientDirectory(context,PREFS);
                legacy.setFollowUp(CUSTOMER,DAY,"Тестовый план",5000L,"debt");
                try(LedgerStore store=new LedgerStore(context,DB,legacy)){
                    assertEquals(4,store.getReadableDatabase().getVersion());
                    assertEquals(CUSTOMER,store.readSnapshot().getClients().get(0).id);
                    List<LedgerStore.Entry> entries=store.entriesForClient(CUSTOMER);
                    assertEquals(2,entries.size());
                    assertEquals("debt",entries.get(0).id);
                    assertEquals("payment",entries.get(1).id);
                    assertEquals(25000L,LedgerStore.balance(entries,CUSTOMER));
                    assertEquals(1,store.readSnapshot().getPlans().size());
                    assertEquals("debt",store.currentPlan(CUSTOMER).anchor);
                    assertEquals(5000L,store.currentPlan(CUSTOMER).amount);
                    assertEquals(version==3?1:0,store.products().size());
                    if(version==3)assertEquals(15000L,store.products().get(0).price);
                    assertEquals(1,LedgerStore.journalRank(store.getReadableDatabase(),"payment",DAY,ZoneId.of("UTC")));
                    String backup=store.exportJson();
                    assertArrayEquals(new int[]{1,2},store.importJson(backup));
                    assertEquals(backup,store.exportJson());
                }
                try(LedgerStore reopened=new LedgerStore(context,DB,legacy)){
                    assertEquals(25000L,LedgerStore.balance(reopened.entries(),CUSTOMER));
                    assertEquals(1,reopened.followUpHistory().size());
                }
            }finally{cleanup();}
        }
    }

    @Test public void failedLegacyUpgradeRollsBackSchemaAndRows(){
        cleanup();
        try{
            seed(1);
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString("follow_up_date:"+CUSTOMER,DAY.toString())
                .putLong("promise_amount:"+CUSTOMER,-1L).commit();
            try(LedgerStore store=new LedgerStore(context,DB,new ClientDirectory(context,PREFS))){
                try{store.getWritableDatabase();fail("Invalid legacy plan must fail upgrade");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("старый план"));}
            }
            try(SQLiteDatabase raw=context.openOrCreateDatabase(DB,Context.MODE_PRIVATE,null)){
                assertEquals(1,raw.getVersion());
                assertFalse(table(raw,"products"));
                assertFalse(table(raw,"collection_plans"));
                try(Cursor c=raw.rawQuery("SELECT sum(CASE type WHEN 'debt' THEN amount ELSE -amount END) FROM entries",null)){
                    assertTrue(c.moveToFirst());assertEquals(25000L,c.getLong(0));
                }
                try(Cursor c=raw.rawQuery("PRAGMA table_info(entries)",null)){
                    while(c.moveToNext())assertNotEquals("purchase",c.getString(1));
                }
            }
        }finally{cleanup();}
    }

    @Test public void catalogRollbackAndForeignKeysRemainEnforced(){
        cleanup();
        try(LedgerStore store=new LedgerStore(context,DB)){
            Product first=new Product("a","Яблоко","Фрукты","кг",15000,true,false);
            store.saveProduct(first);
            try{store.saveProduct(new Product("b"," яблоко ","Фрукты","кг",9000,false,false));fail("Duplicate active product");}
            catch(IllegalArgumentException expected){assertEquals(1,store.catalogRecords().size());}
            store.saveProduct(new Product("a","Яблоко","Фрукты","кг",17000,false,false));
            assertEquals(17000L,store.catalogRecords().get(0).getPrice());
            assertFalse(store.getWritableDatabase().inTransaction());
            try{
                store.getWritableDatabase().execSQL("INSERT INTO entries(id,client_id,type,amount,date,due,note,reverses) VALUES('bad','missing','debt',1,'2026-11-21T09:00:00Z','','','')");
                fail("Foreign key must remain enforced");
            }catch(SQLiteConstraintException expected){assertTrue(store.entries().isEmpty());}
        }finally{cleanup();}
    }
}
