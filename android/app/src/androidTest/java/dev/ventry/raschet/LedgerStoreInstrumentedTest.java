package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Uses a dedicated QA-only filename in the target sandbox; never opens,
 * replaces or deletes raschet-ledger.db. Records are fictitious.
 */
@RunWith(AndroidJUnit4.class)
public class LedgerStoreInstrumentedTest {
    private static final String ISOLATED_DB="raschet-qa-only.db";
    private Context testContext;
    private LedgerStore book;

    @Before public void prepare() {
        testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Tests use the target app's valid sandbox but NEVER the production DB name.
        testContext.deleteDatabase(ISOLATED_DB);
        book = new LedgerStore(testContext,ISOLATED_DB);
    }

    @After public void cleanup() {
        if (book != null) book.close();
        if (testContext != null) testContext.deleteDatabase(ISOLATED_DB);
    }

    private String addCustomer() {
        book.addClient("Тестовый покупатель", "+7 000 000 00 00");
        return book.clients().get(0).id;
    }

    @Test public void partialPaymentsAndOverpaymentRejectionSurviveReopening() {
        String id = addCustomer();
        book.addEntry(id,"debt",120000L,"","Тест");
        book.addEntry(id,"payment",40000L,"","Оплата");
        assertEquals(80000L,LedgerStore.balance(book.entries(),id));
        int count = book.entries().size();
        try {
            book.addEntry(id,"payment",80001L,"","Переплата");
            fail("Overpayment must not be recorded");
        } catch (IllegalArgumentException expected) {
            assertEquals(count,book.entries().size());
        }
        book.close();
        book = new LedgerStore(testContext,ISOLATED_DB);
        assertEquals(80000L,LedgerStore.balance(book.entries(),id));
        book.addEntry(id,"payment",80000L,"","Закрытие");
        assertEquals(0L,LedgerStore.balance(book.entries(),id));
    }

    @Test public void reversingLastPaymentRestoresBalanceAndOverdue() {
        String id = addCustomer();
        String overdueDate = LocalDate.now().minusDays(2).toString();
        book.addEntry(id,"debt",10000L,overdueDate,"Тестовый долг");
        book.addEntry(id,"payment",4000L,"","Частичная оплата");
        assertEquals(6000L,LedgerStore.overdue(book.entries(),id,LocalDate.now()));
        book.reverseLast(id,"Ошибка записи");
        List<LedgerStore.Entry> entries=book.entries();
        assertEquals(3,entries.size());
        assertEquals(10000L,LedgerStore.balance(entries,id));
        assertEquals(10000L,LedgerStore.overdue(entries,id,LocalDate.now()));
        assertEquals("reversal",entries.get(2).type);
        assertEquals(entries.get(1).id,entries.get(2).reverses);
    }

    @Test public void backupRoundtripAndInvalidRestorePreserveExistingBook() throws Exception {
        String id = addCustomer();
        book.addEntry(id,"debt",55555L,"","Проверка копии");
        String snapshot=book.exportJson();
        String before=book.exportJson();
        JSONObject invalid=new JSONObject(snapshot);
        invalid.getJSONArray("entries").getJSONObject(0).put("amount",1.25);
        try {
            book.importJson(invalid.toString());
            fail("Fractional imported amount must be rejected");
        } catch (IllegalArgumentException expected) { /* Verified. */ }
        assertEquals(before,book.exportJson());
        book.addEntry(id,"payment",500L,"","Другая операция");
        assertEquals(55055L,LedgerStore.balance(book.entries(),id));
        int[] restored=book.importJson(snapshot);
        assertArrayEquals(new int[]{1,1},restored);
        assertEquals(55555L,LedgerStore.balance(book.entries(),id));
        assertEquals(snapshot,book.exportJson());
    }

    @Test public void inputValidationAndClientEditingKeepLedgerIntact() {
        assertEquals(123456L,LedgerStore.parseAmount("1 234,56"));
        for (String bad : new String[]{"0", "-1", "1.234", "1e5", "100,0,2"}) {
            try { LedgerStore.parseAmount(bad); fail("Accepted: "+bad); }
            catch (IllegalArgumentException expected) { /* Verified. */ }
        }
        String id=addCustomer();
        book.addEntry(id,"debt",1010L,"","");
        book.editClient(id,"Исправленное имя","+7 111");
        assertEquals("Исправленное имя",book.clients().get(0).name);
        assertEquals(1010L,LedgerStore.balance(book.entries(),id));
    }

    @Test public void correctingLastEntryIsAtomicAndExportsValidV2History() throws Exception {
        String id=addCustomer();
        book.addEntry(id,"debt",12000L,"","Старая сумма");
        String oldId=book.lastActive(id).id;
        book.correctLast(id,14000L,"","Новая сумма","Ошибочно указана сумма");
        assertEquals(14000L,LedgerStore.balance(book.entries(),id));
        assertEquals(3,book.entries().size());
        assertEquals(oldId,book.entries().get(1).reverses);
        assertEquals("reversal",book.entries().get(1).type);
        String exported=book.exportJson();
        int[] loaded=book.importJson(exported);
        assertArrayEquals(new int[]{1,3},loaded);
        assertEquals(14000L,LedgerStore.balance(book.entries(),id));
        book.addEntry(id,"payment",6000L,"","");
        String before=book.exportJson();
        try{
            book.correctLast(id,25000L,"","","Неверная оплата");
            fail("Cannot correct to an overpayment");
        }catch(IllegalArgumentException expected){
            assertEquals(before,book.exportJson());
        }
        book.correctLast(id,5000L,"","","Исправлена оплата");
        assertEquals(9000L,LedgerStore.balance(book.entries(),id));
    }

    @Test public void alreadyPartiallyPaidDebtCannotBeCorrectedOutOfOrder() {
        String id=addCustomer();
        book.addEntry(id,"debt",12000L,"","Долг");
        book.addEntry(id,"payment",5000L,"","Погашение");
        try{
            book.correctLast(id,1000L,"","","Нельзя менять первый долг через последнюю оплату");
            // The last payment is corrected above, so an amount of 1000 is valid.
            assertEquals(11000L,LedgerStore.balance(book.entries(),id));
        }catch(IllegalArgumentException e){fail("Last payment correction must be allowed: "+e);}
    }

}
