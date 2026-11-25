package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.ClientCommand;
import dev.ventry.raschet.data.SqliteLedgerRepository;
import dev.ventry.raschet.data.WriteDestination;
import dev.ventry.raschet.data.WriteReceipt;
import dev.ventry.raschet.domain.DebtApproval;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FinancialCommandInstrumentedTest {
    @Test public void atomicDebtPaymentConsentAndHistoricalGuards() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database="raschet-financial-command-qa-only.db";
        context.deleteDatabase(database);
        String client=null;
        ClientDirectory directory=new ClientDirectory(context);
        try (LedgerStore book=new LedgerStore(context,database)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            LocalDate today=LocalDate.now();
            String operation=UUID.randomUUID().toString();
            ClientCommand.Debt first=new ClientCommand.Debt(operation,null,"Synthetic A","",10000L,today.minusDays(2),"","",false,"",true,WriteDestination.BOOK);
            WriteReceipt receipt=repository.saveDebt(first,context);
            client=receipt.getClientId();
            final String id=client;
            assertEquals("debt",receipt.getKind());
            assertEquals(1,receipt.getRank());
            assertEquals(1,book.clients().size());
            assertEquals(operation,book.entries().get(0).id);
            String before=book.exportJson();
            rejected(()->repository.saveDebt(first,context));
            rejected(()->repository.saveDebt(new ClientCommand.Debt(UUID.randomUUID().toString(),null,"synthetic a","",1L,today,"","",false,"",true,WriteDestination.BOOK),context));
            rejected(()->repository.saveDebt(new ClientCommand.Debt(UUID.randomUUID().toString(),null,"Synthetic B","",1L,today.plusDays(1),"","",false,"",true,WriteDestination.BOOK),context));
            assertEquals(before,book.exportJson());
            directory.setDebtLimit(id,11000L);
            String approval=DebtApproval.key(id,10000L,11000L,2000L);
            String addition=UUID.randomUUID().toString();
            rejected(()->repository.saveDebt(new ClientCommand.Debt(addition,id,"","",2000L,today,"","",false,"",false,WriteDestination.DETAIL),context));
            assertEquals(before,book.exportJson());
            repository.savePayment(new ClientCommand.Payment(UUID.randomUUID().toString(),id,500L,today.minusDays(1),"",WriteDestination.DETAIL));
            String afterPayment=book.exportJson();
            rejected(()->repository.saveDebt(new ClientCommand.Debt(addition,id,"","",2000L,today,"","",false,approval,false,WriteDestination.DETAIL),context));
            assertEquals(afterPayment,book.exportJson());
            repository.saveDebt(new ClientCommand.Debt(addition,id,"","",2000L,today,"","",false,DebtApproval.key(id,9500L,11000L,2000L),false,WriteDestination.DETAIL),context);
            assertEquals(11500L,LedgerStore.balance(book.entries(),id));
            String snapshot=book.exportJson();
            String payment=UUID.randomUUID().toString();
            rejected(()->repository.savePayment(new ClientCommand.Payment(payment,id,9501L,today.minusDays(2),"",WriteDestination.BOOK)));
            rejected(()->repository.savePayment(new ClientCommand.Payment(payment,id,11501L,today,"",WriteDestination.BOOK)));
            assertEquals(snapshot,book.exportJson());
            WriteReceipt settled=repository.savePayment(new ClientCommand.Payment(payment,id,11500L,today,"",WriteDestination.BOOK));
            assertTrue(settled.getSettled());
            assertEquals(1,settled.getRank());
            assertEquals(0L,LedgerStore.balance(book.entries(),id));
            String finished=book.exportJson();
            rejected(()->repository.savePayment(new ClientCommand.Payment(payment,id,11500L,today,"",WriteDestination.BOOK)));
            assertEquals(finished,book.exportJson());
        } finally {
            if(client!=null)directory.setDebtLimit(client,0);
            context.deleteDatabase(database);
        }
    }

    private static void rejected(Runnable action) {
        try { action.run(); fail("Invalid command accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
