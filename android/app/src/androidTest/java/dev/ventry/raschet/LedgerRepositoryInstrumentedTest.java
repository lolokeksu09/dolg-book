package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.LedgerSnapshot;
import dev.ventry.raschet.data.SqliteLedgerRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LedgerRepositoryInstrumentedTest {
    @Test public void snapshotTracksCommittedChangesAndPreservesOldReads() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="raschet-repository-qa-only.db";
        context.deleteDatabase(name);
        try (LedgerStore book=new LedgerStore(context,name)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            assertTrue(repository.snapshot().getClients().isEmpty());
            assertTrue(repository.snapshot().getEntries().isEmpty());
            book.addClient("Synthetic customer","");
            String id=book.clients().get(0).id;
            book.addEntry(id,"debt",10000L,"","");
            LedgerSnapshot before=repository.snapshot();
            assertEquals(1,before.getClients().size());
            assertEquals(1,before.getEntries().size());
            assertEquals(id,before.getEntries().get(0).clientId);
            String backup=book.exportJson();
            try {
                before.getEntries().clear();
                fail("Snapshot exposed mutable list");
            } catch (UnsupportedOperationException expected) {}
            try {
                before.getClients().clear();
                fail("Snapshot exposed mutable client list");
            } catch (UnsupportedOperationException expected) {}
            // A second helper commits a payment to the same isolated database.
            try (LedgerStore writer=new LedgerStore(context,name)) {
                writer.addEntry(id,"payment",4000L,"","");
            }
            LedgerSnapshot after=repository.snapshot();
            assertEquals(2,after.getEntries().size());
            assertEquals(6000L,LedgerStore.balance(after.getEntries(),id));
            assertEquals(10000L,LedgerStore.balance(before.getEntries(),id));
            try {
                book.addEntry(id,"payment",6001L,"","");
                fail("Overpayment accepted");
            } catch (IllegalArgumentException expected) {}
            assertEquals(2,repository.snapshot().getEntries().size());
            book.importJson(backup);
            LedgerSnapshot restored=repository.snapshot();
            assertEquals(1,restored.getEntries().size());
            assertEquals(10000L,LedgerStore.balance(restored.getEntries(),id));
            assertEquals(2,after.getEntries().size());
        } finally {
            context.deleteDatabase(name);
        }
    }
}
