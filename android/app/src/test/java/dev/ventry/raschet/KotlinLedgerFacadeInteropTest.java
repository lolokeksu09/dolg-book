package dev.ventry.raschet;

import org.junit.Test;
import java.lang.reflect.Modifier;
import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;
import org.json.JSONException;
import static org.junit.Assert.*;

/** Java ABI and synchronization are part of the migration contract. */
public class KotlinLedgerFacadeInteropTest {
    @Test public void existingJavaFieldsAndStaticMethodsRemainUsable() {
        LedgerStore.Client client = new LedgerStore.Client("c","Synthetic","");
        LedgerStore.Entry debt = new LedgerStore.Entry("a",client.id,"debt",35000L,"2026-11-21T09:00:00Z","","","");
        LedgerStore.Entry payment = new LedgerStore.Entry("b",client.id,"payment",10000L,"2026-11-21T10:00:00Z","","","",null);
        List<LedgerStore.Entry> entries = Arrays.asList(debt,payment);
        assertEquals("a",debt.id);
        assertEquals("a",debt.getEntryId());
        assertEquals(35000L,debt.amount);
        assertEquals(25000L,LedgerStore.balance(entries,client.id));
        assertEquals(0L,LedgerStore.overdue(entries,client.id,LocalDate.of(2026,11,22)));
        assertEquals(35000L,LedgerStore.parseAmount("350"));
        assertEquals(100000000000L,LedgerStore.MAX_AMOUNT);
        LedgerStore.Balance result=new LedgerStore.Balance();
        result.amount=25000L;
        assertEquals(25000L,result.amount);
    }

    @Test public void ioBoundaryStillSynchronizedAndDeclaresCheckedExceptions() throws Exception {
        for(java.lang.reflect.Method method:LedgerStore.class.getDeclaredMethods()){
            if(Arrays.asList("addClient","addDraftClient","addCheckedDraftClient","editClient","recordDatedDebt",
                "recordCheckedDebt","recordCheckedPayment","recordCheckedPurchase","addEntry","addPurchase","addDraftPayment",
                "saveFollowUp","saveProduct","saveCatalogRecord","reverseSelected","correctSelected","readSnapshot","writeJson",
                "exportJson","importJson","entries","entriesForClient","clients","products","lastActive","followUpHistory","currentPlan",
                "reverseLast","correctLast","catalogRecords","hasClientId","hasEntryId").contains(method.getName())){
                assertTrue(method.getName(),Modifier.isSynchronized(method.getModifiers()));
            }
        }
        assertTrue(Arrays.asList(LedgerStore.class.getMethod("writeJson",Writer.class).getExceptionTypes()).contains(IOException.class));
        assertTrue(Arrays.asList(LedgerStore.class.getMethod("importJson",Reader.class).getExceptionTypes()).contains(JSONException.class));
        assertTrue(Arrays.asList(LedgerStore.class.getMethod("importJson",String.class).getExceptionTypes()).contains(JSONException.class));
        assertTrue(Modifier.isStatic(LedgerStore.class.getMethod("parseAmount",String.class).getModifiers()));
    }
}
