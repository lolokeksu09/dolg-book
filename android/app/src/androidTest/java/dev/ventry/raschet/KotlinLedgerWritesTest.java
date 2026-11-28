package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.ClientCommand;
import dev.ventry.raschet.data.WriteDestination;
import dev.ventry.raschet.data.WriteReceipt;
import dev.ventry.raschet.domain.DebtApproval;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KotlinLedgerWritesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String DB="raschet-kotlin-writes-qa.db";
    private static String uuid(){return UUID.randomUUID().toString();}

    @Test public void atomicNewCustomerAndDuplicateOperationIds() throws Exception {
        context.deleteDatabase(DB);
        try(LedgerStore book=new LedgerStore(context,DB)){
            String empty=book.exportJson();
            try{book.recordDatedDebt(null,"Synthetic","",0,LocalDate.now(),"","",uuid());fail("Zero debt");}
            catch(IllegalArgumentException expected){}
            assertEquals(empty,book.exportJson());
            String op=uuid();
            String id=book.recordDatedDebt(null,"Synthetic","",35000,LocalDate.now(),"","",op);
            String before=book.exportJson();
            try{book.recordDatedDebt(null,"Duplicate","",100,LocalDate.now(),"","",op);fail("Duplicate debt");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            String pay=uuid();
            WriteReceipt receipt=book.recordCheckedPayment(new ClientCommand.Payment(pay,id,10000,LocalDate.now(),"",WriteDestination.BOOK));
            assertEquals(id,receipt.getClientId());
            assertFalse(receipt.getSettled());
            assertEquals(25000L,LedgerStore.balance(book.entries(),id));
            before=book.exportJson();
            try{book.recordCheckedPayment(new ClientCommand.Payment(pay,id,10000,LocalDate.now(),"",WriteDestination.BOOK));fail("Duplicate payment");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            assertFalse(book.getWritableDatabase().inTransaction());
        }finally{context.deleteDatabase(DB);}
    }

    @Test public void exactApprovalMustBeRevalidatedAtCommit() throws Exception {
        context.deleteDatabase(DB);
        String id=null;
        ClientDirectory directory=new ClientDirectory(context);
        try(LedgerStore book=new LedgerStore(context,DB)){
            book.addClient("Synthetic","");
            id=book.clients().get(0).id;
            book.addEntry(id,"debt",35000L,"","");
            directory.setDebtLimit(id,40000L);
            String approval=DebtApproval.key(id,35000L,40000L,10000L);
            book.addEntry(id,"payment",1000L,"","");
            String before=book.exportJson();
            String op=uuid();
            ClientCommand.Debt stale=new ClientCommand.Debt(op,id,"","",10000L,LocalDate.now(),"","",false,approval,false,WriteDestination.DETAIL);
            try{book.recordCheckedDebt(stale,context);fail("Stale approval");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            ClientCommand.Debt approved=new ClientCommand.Debt(op,id,"","",10000L,LocalDate.now(),"","",false,
                DebtApproval.key(id,34000L,40000L,10000L),false,WriteDestination.DETAIL);
            book.recordCheckedDebt(approved,context);
            assertEquals(44000L,LedgerStore.balance(book.entries(),id));
            assertFalse(book.getWritableDatabase().inTransaction());
        }finally{
            if(id!=null)directory.setDebtLimit(id,0);
            context.deleteDatabase(DB);
        }
    }
}
