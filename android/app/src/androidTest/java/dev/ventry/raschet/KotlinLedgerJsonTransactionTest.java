package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.StringReader;
import java.io.Writer;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KotlinLedgerJsonTransactionTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String DB="raschet-kotlin-json-qa.db";

    @Test public void streamingRestoreFailureRollsBackAndCanBeRetried() throws Exception {
        context.deleteDatabase(DB);
        try(LedgerStore book=new LedgerStore(context,DB)){
            book.addClient("Synthetic","");
            String id=book.clients().get(0).id;
            book.addEntry(id,"debt",35000L,"","");
            book.addEntry(id,"payment",10000L,"","");
            String before=book.exportJson();
            JSONObject invalid=new JSONObject(before);
            invalid.getJSONArray("entries").getJSONObject(1).put("amount",35001L);
            String[] rejected={invalid.toString(), before.replace("\"amount\":35000","\"amount\":35000.0"),
                before.replace("\"amount\":35000","\"amount\":35e3"),
                before.replace("\"amount\":35000","\"amount\":35000,\"amount\":35000"),
                before+" {}"};
            for(String raw:rejected){
                assertNotEquals(before,raw);
                try{book.importJson(new StringReader(raw));fail("Invalid backup accepted");}
                catch(IllegalArgumentException | IOException expected){}
                assertEquals(before,book.exportJson());
                assertFalse(book.getWritableDatabase().inTransaction());
            }
            assertArrayEquals(new int[]{1,2},book.importJson(before));
            assertEquals(25000L,LedgerStore.balance(book.entries(),id));
            assertEquals(before,book.exportJson());
        }finally{context.deleteDatabase(DB);}
    }

    @Test public void writerFailureAndStaleCorrectionLeaveBookUnchanged() throws Exception {
        context.deleteDatabase(DB);
        try(LedgerStore book=new LedgerStore(context,DB)){
            book.addClient("Synthetic","");
            String id=book.clients().get(0).id;
            book.addEntry(id,"debt",35000L,"","");
            String debt=book.lastActive(id).id;
            book.addEntry(id,"payment",10000L,"","");
            String before=book.exportJson();
            Writer failing=new Writer(){
                public void write(char[] c,int start,int length)throws IOException{throw new IOException("Synthetic failure");}
                public void flush(){}
                public void close(){}
            };
            try{book.writeJson(failing);fail("Writer failure swallowed");}
            catch(IOException expected){}
            assertFalse(book.getReadableDatabase().inTransaction());
            try{book.correctSelected(id,debt,100L,"","","Stale");fail("Stale correction accepted");}
            catch(IllegalArgumentException expected){}
            try{book.reverseSelected(id,debt,"Stale");fail("Stale reversal accepted");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            book.reverseLast(id,"Return payment");
            assertEquals(35000L,LedgerStore.balance(book.entries(),id));
            assertEquals(3,book.entries().size());
        }finally{context.deleteDatabase(DB);}
    }
}
