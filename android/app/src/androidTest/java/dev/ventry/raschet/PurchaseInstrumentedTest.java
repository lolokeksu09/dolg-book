package dev.ventry.raschet;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PurchaseInstrumentedTest {
    @Test public void fractionalQuantityDiscountAndRestoreAreExact() throws Exception{
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="purchase-qa-only.db";
        ctx.deleteDatabase(db);
        try(LedgerStore book=new LedgerStore(ctx,db)){
            book.addClient("Тест","");
            String id=book.clients().get(0).id;
            Purchase purchase=new Purchase(Arrays.asList(
                    new Purchase.Item("Яблоки",1500,9999),
                    new Purchase.Item("Хлеб",2000,5000)),1000);
            assertEquals(23999,purchase.total);
            book.addPurchase(id,purchase,"","Тест");
            String before=book.exportJson();
            assertEquals(3,new JSONObject(before).getInt("version"));
            book.importJson(before);
            assertEquals(before,book.exportJson());
            assertEquals(23999,LedgerStore.balance(book.entries(),id));
            JSONObject invalid=new JSONObject(before);
            invalid.getJSONArray("entries").getJSONObject(0).put("amount",1);
            try{book.importJson(invalid.toString());fail("wrong total");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            book.reverseLast(id,"Ошибка");
            assertEquals(0,LedgerStore.balance(book.entries(),id));
            assertNotNull(book.entries().get(0).purchase);
        }finally{ctx.deleteDatabase(db);}
    }
    @Test public void existingVersionOneDatabaseMigratesWithoutLosingRecords(){
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="purchase-migrate-qa.db";
        ctx.deleteDatabase(db);
        try{
            try(SQLiteDatabase old=ctx.openOrCreateDatabase(db,0,null)){
                old.execSQL("CREATE TABLE clients (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL)");
                old.execSQL("CREATE TABLE entries (seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,client_id TEXT NOT NULL REFERENCES clients(id),type TEXT NOT NULL,amount INTEGER NOT NULL,date TEXT NOT NULL,due TEXT NOT NULL,note TEXT NOT NULL,reverses TEXT NOT NULL)");
                old.execSQL("CREATE INDEX entries_client_seq ON entries(client_id,seq)");
                old.execSQL("INSERT INTO clients VALUES('a','QA','')");
                old.execSQL("INSERT INTO entries(id,client_id,type,amount,date,due,note,reverses) VALUES('b','a','debt',80000,'2026-11-01T12:00:00Z','','old','')");
                old.setVersion(1);
            }
            try(LedgerStore book=new LedgerStore(ctx,db)){
                assertEquals(80000,LedgerStore.balance(book.entries(),"a"));
                assertNull(book.entries().get(0).purchase);
                assertEquals("old",book.entries().get(0).note);
                assertEquals(4,book.getReadableDatabase().getVersion());
                assertTrue(book.followUpHistory().isEmpty());
                assertTrue(book.products().isEmpty());
            }
        }finally{ctx.deleteDatabase(db);}
    }
}
