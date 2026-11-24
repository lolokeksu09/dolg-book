package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeCorrectionTransactionTest {
    @Test public void correctionAndRejectionPreserveTransactionContract() throws Exception {
        assertTrue(BuildConfig.RUST_PREVIEW);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="raschet-native-correction-qa-only.db";
        context.deleteDatabase(name);
        try (LedgerStore book=new LedgerStore(context,name)) {
            book.addClient("Synthetic","");
            String id=book.clients().get(0).id;
            book.addEntry(id,"debt",10000L,"","");
            book.addEntry(id,"payment",4000L,"","");
            LedgerStore.Entry payment=book.lastActive(id);
            assertEquals(7000L,LedgerStore.correctedBalance(6000L,payment,3000L));
            book.correctSelected(id,payment.id,3000L,"","","Correction");
            assertEquals(4,book.entries().size());
            assertEquals(7000L,LedgerStore.balance(book.entries(),id));
            assertEquals(payment.id,book.entries().get(2).reverses);
            String before=book.exportJson();
            LedgerStore.Entry current=book.lastActive(id);
            try {
                book.correctSelected(id,current.id,10001L,"","","Invalid correction");
                fail("Native overpayment accepted");
            } catch (IllegalArgumentException expected) {}
            assertEquals(before,book.exportJson());
        } finally {
            context.deleteDatabase(name);
        }
    }
}
