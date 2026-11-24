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


    @Test public void datedQuickDebtIsAtomicAndGroupsByDayEvenAfterPayment() throws Exception {
        LocalDate yesterday=LocalDate.now().minusDays(1);
        LocalDate earlier=yesterday.minusDays(3);
        try{
            book.recordDatedDebt(null,"Не сохранять","",0L,yesterday,"","");
            fail("Invalid first debt must roll back the client");
        }catch(IllegalArgumentException expected){
            assertTrue(book.clients().isEmpty());assertTrue(book.entries().isEmpty());
        }
        String id=book.recordDatedDebt(null,"Покупатель","",10000L,yesterday,"","");
        assertEquals(id,book.recordDatedDebt(id,"ignored","",5000L,earlier,"",""));
        assertEquals(1,book.clients().size());
        book.addEntry(id,"payment",15000L,"","");
        assertEquals(0L,LedgerStore.balance(book.entries(),id));
        List<BookDays.Day> days=BookDays.group(book.entries(),java.time.ZoneId.systemDefault());
        assertEquals(2,days.size());assertEquals(yesterday,days.get(0).date);
        assertEquals(10000L,days.get(0).recorded);assertEquals(5000L,days.get(1).recorded);
        String backup=book.exportJson();book.importJson(backup);
        assertEquals(backup,book.exportJson());
        try{
            book.recordDatedDebt(null,"Будущее","",100L,LocalDate.now().plusDays(1),"","");
            fail("Future debt date must be rejected");
        }catch(IllegalArgumentException expected){assertEquals(1,book.clients().size());}
    }
    @Test public void canceledDebtStaysVisibleButIsExcludedFromDayTotal(){
        String id=book.recordDatedDebt(null,"Клиент","",1200L,LocalDate.now().minusDays(2),"","");
        book.reverseLast(id,"Ошибка");
        BookDays.Day day=BookDays.group(book.entries(),java.time.ZoneId.systemDefault()).get(0);
        assertEquals(1,day.entries.size());assertEquals(0L,day.recorded);
        assertTrue(day.canceled.contains(day.entries.get(0).id));
    }

    @Test public void bookFiltersNeverReallocatePaymentsAndPreserveOriginalDebtDates() {
        LocalDate today=LocalDate.now(),older=today.minusDays(2);
        String a=book.recordDatedDebt(null,"Фёдор","+7 123 456 78 90",120000L,older,"","");
        book.recordDatedDebt(a,"","",50000L,today,"","");
        String b=book.recordDatedDebt(null,"Анна","",30000L,today,"","");
        book.addEntry(a,"payment",140000L,"","");
        List<BookDays.Day> days=BookDays.group(book.entries(),java.time.ZoneId.systemDefault());
        assertEquals(2,days.size());assertEquals(80000L,days.get(0).recorded);
        assertEquals(60000L,days.get(0).remaining);assertEquals(0L,days.get(1).remaining);
        java.util.Map<String,LedgerStore.Client> people=new java.util.HashMap<>();
        for(LedgerStore.Client c:book.clients())people.put(c.id,c);
        List<BookDays.Day> filtered=BookDays.select(days,people,today,today,"федор",true);
        assertEquals(1,filtered.size());assertEquals(1,filtered.get(0).entries.size());
        assertEquals(50000L,filtered.get(0).recorded);assertEquals(30000L,filtered.get(0).remaining);
        assertEquals(30000L,LedgerStore.balance(book.entries(),a));
        assertEquals(30000L,LedgerStore.balance(book.entries(),b));
        assertEquals(2,BookDays.select(days,people,null,null,"1234567890",false).size());
        assertTrue(BookDays.select(days,people,older,older,"",true).isEmpty());
        book.reverseLast(a,"Отмена оплаты");
        days=BookDays.group(book.entries(),java.time.ZoneId.systemDefault());
        assertEquals(120000L,days.get(1).remaining);assertEquals(80000L,days.get(0).remaining);
    }

    @Test public void paymentJournalKeepsReversedReceiptsAndFiltersByClient(){
        String a=book.recordDatedDebt(null,"Али","",100000L,LocalDate.now().minusDays(2),"","");
        String b=book.recordDatedDebt(null,"Лейла","",20000L,LocalDate.now(),"","");
        book.addEntry(a,"payment",40000L,"","");
        book.addEntry(a,"payment",10000L,"","");
        book.reverseLast(a,"Ошибочная оплата");
        book.addEntry(b,"payment",20000L,"","");
        List<BookDays.Day> days=BookDays.groupPayments(book.entries(),java.time.ZoneId.systemDefault());
        assertEquals(1,days.size());assertEquals(3,days.get(0).entries.size());
        assertEquals(60000L,days.get(0).recorded);assertEquals(1,days.get(0).canceled.size());
        java.util.Map<String,LedgerStore.Client> people=new java.util.HashMap<>();
        for(LedgerStore.Client c:book.clients())people.put(c.id,c);
        List<BookDays.Day> filtered=BookDays.select(days,people,LocalDate.now(),LocalDate.now(),"али",false);
        assertEquals(1,filtered.size());assertEquals(40000L,filtered.get(0).recorded);
        assertEquals(2,filtered.get(0).entries.size());assertEquals(60000L,LedgerStore.balance(book.entries(),a));
        assertEquals(0L,LedgerStore.balance(book.entries(),b));
    }

    @Test public void recentDebtSuggestionsIgnorePaymentsAndCanceledDebt(){
        String a=book.recordDatedDebt(null,"Али","",15000L,LocalDate.now(),"","");
        String b=book.recordDatedDebt(null,"Лейла","",20000L,LocalDate.now(),"","");
        book.recordDatedDebt(a,"","",9000L,LocalDate.now(),"","");
        book.reverseLast(a,"Ошибочная запись");
        book.addEntry(a,"payment",5000L,"","");
        java.util.Map<String,Long> recent=RecentDebts.lastAmounts(book.entries());
        assertEquals(java.util.Arrays.asList(b,a),new java.util.ArrayList<>(recent.keySet()));
        assertEquals(Long.valueOf(15000L),recent.get(a));
        assertEquals(Long.valueOf(20000L),recent.get(b));
        assertEquals(10000L,LedgerStore.balance(book.entries(),a));
        assertEquals(5,book.entries().size());
    }

    @Test public void historySearchSortsActualDatesAndRejectsStaleCorrection() throws Exception {
        LocalDate now=LocalDate.now();
        String a=book.recordDatedDebt(null,"Фёдор","+7 123 456 78 90",10000L,now,"","Молоко");
        String first=book.lastActive(a).id;
        book.recordDatedDebt(a,"","",20000L,now.minusDays(2),"","Хлеб");
        String second=book.lastActive(a).id;
        book.recordDatedDebt(null,"Анна","",30000L,now.minusDays(1),"","");
        java.util.Map<String,LedgerStore.Client> people=new java.util.HashMap<>();
        for(LedgerStore.Client c:book.clients())people.put(c.id,c);
        List<LedgerStore.Entry> all=book.entries();
        List<LedgerStore.Entry> visible=HistoryRows.select(all,people,null,"федор",null,null,"Все",java.time.ZoneId.systemDefault());
        assertEquals(2,visible.size());assertEquals(first,visible.get(0).id);assertEquals(second,visible.get(1).id);
        assertEquals(1,HistoryRows.select(all,people,a,"хлеб",null,null,"Долги",java.time.ZoneId.systemDefault()).size());
        assertEquals(2,HistoryRows.select(all,people,null,"1234567890",null,null,"Все",java.time.ZoneId.systemDefault()).size());
        String before=book.exportJson();
        try{book.correctSelected(a,first,5000L,"","","Ошибка");fail("Stale correction accepted");}catch(IllegalArgumentException expected){}
        try{book.reverseSelected(a,first,"Ошибка");fail("Stale cancellation accepted");}catch(IllegalArgumentException expected){}
        assertEquals(before,book.exportJson());
        book.correctSelected(a,second,15000L,"","","Исправление");
        assertEquals(25000L,LedgerStore.balance(book.entries(),a));
        String corrected=book.exportJson();book.importJson(corrected);assertEquals(corrected,book.exportJson());
    }

    @Test public void debtLimitSettingsPersistAndGuardOnlyNewDebt(){
        String id=addCustomer();
        String storage="raschet-qa-limits-"+java.util.UUID.randomUUID();
        ClientDirectory directory=new ClientDirectory(testContext,storage);
        try{
            assertEquals(0L,directory.debtLimit(id));
            directory.setDebtLimit(id,15000L);
            assertEquals(15000L,new ClientDirectory(testContext,storage).debtLimit(id));
            book.addEntry(id,"debt",10000L,"","");
            long balance=LedgerStore.balance(book.entries(),id);
            assertEquals(5000L,DebtLimits.remaining(directory.debtLimit(id),balance));
            DebtLimits.requireConsent(15000L,balance,5000L,false);
            String before=book.exportJson();
            try{DebtLimits.requireConsent(15000L,balance,5001L,false);fail("Missing override accepted");}
            catch(IllegalArgumentException expected){}
            assertEquals(before,book.exportJson());
            DebtLimits.requireConsent(15000L,balance,10000L,true);
            book.addEntry(id,"debt",10000L,"","");
            assertTrue(DebtLimits.requiresConfirmation(15000L,20000L,1L));
            assertFalse(DebtLimits.requiresConfirmation(15000L,20000L,0L));
            book.addEntry(id,"payment",10000L,"","");
            assertEquals(5000L,DebtLimits.remaining(15000L,LedgerStore.balance(book.entries(),id)));
            directory.setDebtLimit(id,0L);
            assertFalse(DebtLimits.requiresConfirmation(directory.debtLimit(id),10000L,100000L));
            directory.setDebtLimit(id,15000L);directory.clearAfterRestore();
            assertEquals(0L,directory.debtLimit(id));
        }finally{directory.clearAfterRestore();}
    }
}
