package dev.ventry.raschet;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LargeBookInstrumentedTest {
    private static final int CLIENTS=1000, ROWS_PER_CLIENT=100, ROWS=CLIENTS*ROWS_PER_CLIENT;

    @Test public void fullCapacitySnapshotIndexedReadAndStreamingExport() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="raschet-large-book-qa-only.db";
        File file=new File(context.getCacheDir(),"raschet-large-book-qa-only.json");
        File points=new File(context.getCacheDir(),"raschet-large-book-points-"+System.nanoTime());
        context.deleteDatabase(name);
        try(LedgerStore book=new LedgerStore(context,name)){
            RecoveryVault vault=new RecoveryVault(points);
            String original=book.exportJson();
            vault.create(book);
            long start=SystemClock.elapsedRealtime();
            seed(book.getWritableDatabase());
            long seedMs=SystemClock.elapsedRealtime()-start;
            start=SystemClock.elapsedRealtime();
            dev.ventry.raschet.data.LedgerSnapshot snapshot=book.readSnapshot();
            List<CollectionsInsights.CustomerStatus> statuses=CollectionsInsights.analyze(
                    snapshot.getClients(),snapshot.getEntries(),LocalDate.now());
            long projectionMs=SystemClock.elapsedRealtime()-start;
            assertEquals(CLIENTS,statuses.size());
            assertEquals(ROWS,snapshot.getEntries().size());
            for(CollectionsInsights.CustomerStatus status:statuses)assertEquals(3500L,status.balance);
            // Drop the full read before measuring the independent indexed query/export.
            snapshot=null;statuses=null;
            start=SystemClock.elapsedRealtime();
            List<LedgerStore.Entry> own=book.entriesForClient("qa-0");
            long indexedMs=SystemClock.elapsedRealtime()-start;
            assertEquals(ROWS_PER_CLIENT,own.size());
            assertEquals(3500L,LedgerStore.balance(own,"qa-0"));
            start=SystemClock.elapsedRealtime();
            try(java.io.Writer writer=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)){
                book.writeJson(writer);
            }
            long exportMs=SystemClock.elapsedRealtime()-start;
            int clients=0,entries=0,version=0;
            try(JsonReader reader=new JsonReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
                reader.beginObject();
                while(reader.hasNext()){
                    String key=reader.nextName();
                    if("clients".equals(key)||"entries".equals(key)){
                        int count=0;
                        reader.beginArray();
                        while(reader.hasNext()){reader.skipValue();count++;}
                        reader.endArray();
                        if("clients".equals(key))clients=count;else entries=count;
                    }else if("version".equals(key))version=reader.nextInt();
                    else reader.skipValue();
                }
                reader.endObject();
                assertEquals(android.util.JsonToken.END_DOCUMENT,reader.peek());
            }
            assertEquals(CLIENTS,clients);assertEquals(ROWS,entries);assertEquals(2,version);
            assertTrue("Fixture must exceed the legacy 10 MiB limit",file.length()>BackupSize.LEGACY_MAX_BYTES);
            assertTrue(BackupSize.isSupported(file.length()));
            start=SystemClock.elapsedRealtime();
            File largePoint=vault.create(book);
            long recoveryMs=SystemClock.elapsedRealtime()-start;
            assertEquals(2,vault.count());
            assertTrue(largePoint.length()>BackupSize.LEGACY_MAX_BYTES);
            String restoredName="raschet-large-restore-qa-only.db";
            context.deleteDatabase(restoredName);
            long importMs;
            try(LedgerStore restored=new LedgerStore(context,restoredName);
                java.io.Reader input=BackupSize.openUtf8(new FileInputStream(file))){
                start=SystemClock.elapsedRealtime();
                assertArrayEquals(new int[]{CLIENTS,ROWS},restored.importJson(input));
                importMs=SystemClock.elapsedRealtime()-start;
                assertEquals(ROWS_PER_CLIENT,restored.entriesForClient("qa-0").size());
                assertEquals(3500L,LedgerStore.balance(restored.entriesForClient("qa-999"),"qa-999"));
                assertEquals(ROWS,android.database.DatabaseUtils.queryNumEntries(restored.getReadableDatabase(),"entries"));
                // The encrypted reader must restore the identical full-size book as well.
                try(java.io.Reader encrypted=vault.openLatest()){
                    assertArrayEquals(new int[]{CLIENTS,ROWS},restored.importJson(encrypted));
                }
                assertEquals(3500L,LedgerStore.balance(restored.entriesForClient("qa-999"),"qa-999"));
            }finally{context.deleteDatabase(restoredName);}
            Log.i("RaschetBenchmark","LARGE_BOOK_PASS rows="+ROWS+" clients="+CLIENTS+
                    " seed_ms="+seedMs+" snapshot_projection_ms="+projectionMs+
                    " indexed_read_ms="+indexedMs+" export_ms="+exportMs+" import_ms="+importMs+" recovery_ms="+recoveryMs+" json_bytes="+file.length());
        }finally{
            file.delete();
            File[] remaining=points.listFiles();
            if(remaining!=null)for(File point:remaining)point.delete();
            points.delete();
            context.deleteDatabase(name);
        }
    }

    /** Bulk fixture setup is not a production-write latency measurement. */
    private static void seed(SQLiteDatabase db){
        db.beginTransaction();
        try(SQLiteStatement client=db.compileStatement("INSERT INTO clients(id,name,phone) VALUES(?,?,?)");
            SQLiteStatement entry=db.compileStatement("INSERT INTO entries(id,client_id,type,amount,date,due,note,reverses,purchase) VALUES(?,?,?,?,?,?,?,?,?)")){
            for(int c=0;c<CLIENTS;c++){
                String id="qa-"+c;
                client.bindString(1,id);client.bindString(2,"Synthetic "+c);client.bindString(3,"");client.executeInsert();
                for(int r=0;r<ROWS_PER_CLIENT;r++){
                    boolean debt=r%2==0;
                    entry.bindString(1,id+"-"+r);entry.bindString(2,id);entry.bindString(3,debt?"debt":"payment");
                    entry.bindLong(4,debt?100:30);entry.bindString(5,"2026-01-01T00:00:00Z");
                    entry.bindString(6,"");entry.bindString(7,"Synthetic load fixture");entry.bindString(8,"");
                    entry.bindString(9,"");entry.executeInsert();
                }
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}
