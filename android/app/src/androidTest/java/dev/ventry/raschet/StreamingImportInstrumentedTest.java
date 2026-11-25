package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StreamingImportInstrumentedTest {
    @Test public void versionLastAndLateFailureKeepBookAtomic() throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="raschet-stream-import-qa-only.db";
        ctx.deleteDatabase(db);
        try(LedgerStore book=new LedgerStore(ctx,db)){
            book.addClient("Тестовый","");
            String client=book.clients().get(0).id;
            book.addEntry(client,"debt",10000L,"","Долг");
            book.addEntry(client,"payment",3500L,"","Оплата");
            String before=book.exportJson();
            JSONObject json=new JSONObject(before);
            JSONArray rows=json.getJSONArray("entries");
            // Root arrays are intentionally reordered; version may occur last.
            String reordered="{\"entries\":"+rows+",\"clients\":"+json.getJSONArray("clients")+",\"version\":2}";
            assertArrayEquals(new int[]{1,2},book.importJson(new StringReader(reordered)));
            assertEquals(before,book.exportJson());
            rows.getJSONObject(1).put("amount",10001);
            try{
                book.importJson(new StringReader(json.toString()));
                fail("Late overpayment must fail");
            }catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            for(String broken:new String[]{before+" true", before.substring(0,before.length()-1),
                    before.substring(0,before.length()-1)+",\"version\":2}"}){
                try{
                    book.importJson(new StringReader(broken));
                    fail("Malformed or duplicate root key accepted");
                }catch(java.io.IOException|org.json.JSONException|IllegalArgumentException expected){}
                assertEquals(before,book.exportJson());
            }
            // A failed transaction must not strand the staging table or prevent a later restore.
            assertArrayEquals(new int[]{1,2},book.importJson(new StringReader(before)));
            assertEquals(6500L,LedgerStore.balance(book.entriesForClient(client),client));
        }finally{ctx.deleteDatabase(db);}
    }
}
