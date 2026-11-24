package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.ClientCommand;
import dev.ventry.raschet.data.SqliteLedgerRepository;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClientCommandInstrumentedTest {
    @Test public void clientCreationEditDuplicateConsentAndRollback() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database="raschet-client-command-qa-only.db";
        context.deleteDatabase(database);
        try (LedgerStore book=new LedgerStore(context,database)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            String first=UUID.randomUUID().toString();
            String second=UUID.randomUUID().toString();
            repository.saveClient(new ClientCommand.Create(first,"  Test client  ","+7 900 123 45 67",false));
            assertEquals(1,repository.snapshot().getClients().size());
            assertEquals("Test client",repository.snapshot().getClients().get(0).name);
            assertTrue(repository.snapshot().getEntries().isEmpty());
            String before=book.exportJson();
            rejected(()->repository.saveClient(new ClientCommand.Create(first,"Another","",true)));
            rejected(()->repository.saveClient(new ClientCommand.Create(second,"test CLIENT","",false)));
            rejected(()->repository.saveClient(new ClientCommand.Create(second,"Different","8 900 123 45 67",false)));
            rejected(()->repository.saveClient(new ClientCommand.Create(second,"  ","",true)));
            rejected(()->repository.saveClient(new ClientCommand.Edit(first,"","")));
            rejected(()->repository.saveClient(new ClientCommand.Edit(second,"Missing","")));
            assertEquals(before,book.exportJson());
            repository.saveClient(new ClientCommand.Create(second,"Different","8 900 123 45 67",true));
            book.addEntry(first,"debt",10000L,"","");
            repository.saveClient(new ClientCommand.Edit(first,"Edited",""));
            assertEquals(2,repository.snapshot().getClients().size());
            assertEquals("Edited",repository.snapshot().getClients().stream().filter(c->c.id.equals(first)).findFirst().get().name);
            assertEquals(10000L,LedgerStore.balance(repository.snapshot().getEntries(),first));
            assertEquals(1,repository.snapshot().getEntries().size());
        } finally {
            context.deleteDatabase(database);
        }
    }

    private static void rejected(Runnable action){
        try { action.run(); fail("Invalid client command accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
