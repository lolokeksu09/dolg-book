package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.ClientCommand;
import dev.ventry.raschet.data.SqliteLedgerRepository;
import dev.ventry.raschet.data.WriteDestination;
import dev.ventry.raschet.data.WriteReceipt;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OperationCommandInstrumentedTest {
    @Test public void correctionAndReversalUseExactSelectedOperation() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database="raschet-operation-command-qa-only.db";
        context.deleteDatabase(database);
        try (LedgerStore book=new LedgerStore(context,database)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            book.addClient("Synthetic","");
            String client=book.clients().get(0).id;
            book.addEntry(client,"debt",10000L,"","");
            String debt=book.lastActive(client).id;
            book.addEntry(client,"payment",4000L,"","");
            String payment=book.lastActive(client).id;
            String before=book.exportJson();
            rejected(()->repository.correct(new ClientCommand.Correction(client,debt,9000L,"","","stale")));
            rejected(()->repository.reverse(new ClientCommand.Reversal(client,debt,"stale")));
            rejected(()->repository.correct(new ClientCommand.Correction(client,payment,10001L,"","","overpayment")));
            rejected(()->repository.reverse(new ClientCommand.Reversal(client,payment,"")));
            assertEquals(before,book.exportJson());
            WriteReceipt corrected=repository.correct(new ClientCommand.Correction(client,payment,3000L,"","edited","Correction"));
            assertEquals(WriteDestination.DETAIL,corrected.getDestination());
            assertEquals(client,corrected.getClientId());
            assertEquals("correction",corrected.getKind());
            assertEquals(4,book.entries().size());
            assertEquals(payment,book.entries().get(2).reverses);
            assertEquals(7000L,LedgerStore.balance(book.entries(),client));
            String newPayment=book.lastActive(client).id;
            String afterCorrection=book.exportJson();
            rejected(()->repository.correct(new ClientCommand.Correction(client,payment,3000L,"","edited","Correction")));
            assertEquals(afterCorrection,book.exportJson());
            WriteReceipt reversed=repository.reverse(new ClientCommand.Reversal(client,newPayment,"Reversal"));
            assertEquals(WriteDestination.KEEP,reversed.getDestination());
            assertEquals("reversal",reversed.getKind());
            assertEquals(5,book.entries().size());
            assertEquals(newPayment,book.entries().get(4).reverses);
            assertEquals(10000L,LedgerStore.balance(book.entries(),client));
            String afterReversal=book.exportJson();
            rejected(()->repository.reverse(new ClientCommand.Reversal(client,newPayment,"Repeated")));
            assertEquals(afterReversal,book.exportJson());
        } finally {
            context.deleteDatabase(database);
        }
    }

    private static void rejected(Runnable action){
        try { action.run(); fail("Invalid operation command accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
