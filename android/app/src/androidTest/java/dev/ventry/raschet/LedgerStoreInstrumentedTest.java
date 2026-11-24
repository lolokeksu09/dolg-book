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

    @Test public void debtLimitSettingsPersistAndGuardOnlyNewDebt() throws Exception {
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

    @Test public void quickDraftSurvivesReopenAndCannotPostTwice() throws Exception {
        String storage="raschet-qa-draft-"+java.util.UUID.randomUUID();
        QuickDebtDrafts drafts=new QuickDebtDrafts(testContext,storage);
        String id=java.util.UUID.randomUUID().toString();
        LocalDate day=LocalDate.now().minusDays(1);
        try{
            drafts.save(new QuickDebtDrafts.Draft(id,false,"Али","123","250",day,"","Хлеб"));
            QuickDebtDrafts.Draft saved=new QuickDebtDrafts(testContext,storage).get();
            assertEquals(id,saved.id);assertEquals(day,saved.day);assertEquals("250",saved.amount);
            assertTrue(book.entries().isEmpty());assertTrue(book.clients().isEmpty());
            try{book.recordDatedDebt(null,saved.name,saved.phone,0L,day,"","",id);fail("Zero amount accepted");}
            catch(IllegalArgumentException expected){}
            assertFalse(book.hasEntryId(id));assertTrue(book.clients().isEmpty());
            String client=book.recordDatedDebt(null,saved.name,saved.phone,25000L,day,"",saved.note,id);
            assertTrue(book.hasEntryId(id));
            try{book.recordDatedDebt(null,saved.name,saved.phone,25000L,day,"",saved.note,id);fail("Duplicate draft accepted");}
            catch(IllegalArgumentException expected){}
            assertEquals(1,book.entries().size());assertEquals(1,book.clients().size());
            assertEquals(25000L,LedgerStore.balance(book.entries(),client));
            String json=book.exportJson();book.importJson(json);assertEquals(json,book.exportJson());
            drafts.clear();assertNull(drafts.get());
            String contactId=java.util.UUID.randomUUID().toString();
            book.addDraftClient(contactId,"Контакт","");
            assertTrue(book.hasClientId(contactId));
        }finally{drafts.clear();}
    }

    @Test public void collectionWorklistPrioritizesUniqueDebtorsAndDoesNotModifyBook() throws Exception {
        LocalDate today=LocalDate.now();
        String storage="raschet-qa-collections-"+java.util.UUID.randomUUID();
        ClientDirectory directory=new ClientDirectory(testContext,storage);
        try{
            String late=book.recordDatedDebt(null,"Просрочка","",10000L,today.minusDays(2),today.minusDays(1).toString(),"");
            String contact=book.recordDatedDebt(null,"Контакт","",20000L,today,"","");
            String soon=book.recordDatedDebt(null,"Срок","",30000L,today,today.plusDays(3).toString(),"");
            String limit=book.recordDatedDebt(null,"Лимит","",40000L,today,"","");
            String planned=book.recordDatedDebt(null,"План","",50000L,today,"","");
            String paid=book.recordDatedDebt(null,"Оплачено","",60000L,today,"","");
            book.addEntry(paid,"payment",60000L,"","");
            String original=book.exportJson();
            directory.setFollowUp(contact,today,"Позвонить");
            directory.setFollowUp(late,today,"Уточнить срок");
            directory.setFollowUp(planned,today.plusDays(14),"Позже");
            directory.setFollowUp(paid,today,"Не показывать");
            directory.setDebtLimit(limit,10000L);
            List<CollectionWorklist.Item> rows=CollectionWorklist.build(CollectionsInsights.analyze(book.clients(),book.entries(),today),directory,today);
            assertEquals(5,rows.size());
            assertEquals(late,rows.get(0).status.client.id);assertEquals(contact,rows.get(1).status.client.id);
            assertEquals(soon,rows.get(2).status.client.id);assertEquals(limit,rows.get(3).status.client.id);
            assertEquals(planned,rows.get(4).status.client.id);
            assertEquals(4,CollectionWorklist.select(rows,"",true).size());
            assertEquals(1,CollectionWorklist.select(rows,"контакт",false).size());
            assertEquals("Позвонить",new ClientDirectory(testContext,storage).followUp(contact).note);
            assertEquals(original,book.exportJson());
            directory.setFollowUp(contact,null,"");assertNull(directory.followUp(contact));
        }finally{directory.clearAfterRestore();}
    }

    @Test public void promisedPaymentsExcludeEarlierAndCanceledReceipts() throws Exception {
        LocalDate today=LocalDate.now();
        String id=book.recordDatedDebt(null,"Обещание","",100000L,today,"","");
        book.addEntry(id,"payment",10000L,"","");
        String anchor=PaymentPromises.lastEntryId(book.entries(),id);
        String storage="raschet-qa-promises-"+java.util.UUID.randomUUID();
        ClientDirectory directory=new ClientDirectory(testContext,storage);
        try{
            directory.setFollowUp(id,today,"Вернёт часть",40000L,anchor);
            ClientDirectory.FollowUp plan=new ClientDirectory(testContext,storage).followUp(id);
            assertEquals(40000L,plan.amount);assertEquals(anchor,plan.anchor);
            java.time.ZoneId zone=java.time.ZoneId.systemDefault();
            PaymentPromises.Progress progress=PaymentPromises.calculate(book.entries(),id,plan,today,zone);
            assertEquals(0L,progress.paid);assertFalse(progress.fulfilled());
            book.addEntry(id,"payment",15000L,"","");
            book.addEntry(id,"payment",25000L,"","");
            progress=PaymentPromises.calculate(book.entries(),id,plan,today,zone);
            assertTrue(progress.fulfilled());assertEquals(40000L,progress.onTime);
            book.reverseLast(id,"Ошибка");
            progress=PaymentPromises.calculate(book.entries(),id,plan,today.plusDays(1),zone);
            assertFalse(progress.fulfilled());assertTrue(progress.missed);assertEquals(15000L,progress.paid);
            book.addEntry(id,"payment",25000L,"","");
            assertTrue(PaymentPromises.calculate(book.entries(),id,plan,today,zone).fulfilled());
            assertFalse(PaymentPromises.calculate(book.entries(),id,new ClientDirectory.FollowUp(today,"",100L,"missing"),today,zone).valid);
            directory.setFollowUp(id,null,"");assertNull(directory.followUp(id));
        }finally{directory.clearAfterRestore();}
    }

    @Test public void clientStatementShowsUnpaidAmountsAndCanceledRowsWithoutOtherClients() throws Exception {
        LocalDate today=LocalDate.now();
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        String id=book.recordDatedDebt(null,"Выписка","",10000L,today.minusDays(3),"","");
        String first=book.entries().get(0).id;
        book.recordDatedDebt(id,"","",20000L,today.minusDays(1),"","");
        String second=book.entries().get(1).id;
        book.recordDatedDebt(null,"Другой","",99900L,today,"","");
        book.addEntry(id,"payment",15000L,"","");
        String before=book.exportJson();
        ClientStatement statement=ClientStatement.build(book.entries(),id,zone);
        assertEquals(3,statement.entries.size());
        assertEquals(Long.valueOf(0L),statement.remaining.get(first));
        assertEquals(Long.valueOf(15000L),statement.remaining.get(second));
        assertEquals(1,statement.select(ClientStatement.UNPAID).size());
        assertEquals(second,statement.select(ClientStatement.UNPAID).get(0).id);
        assertEquals(1,statement.select(ClientStatement.PAYMENTS).size());
        assertEquals(before,book.exportJson());
        book.reverseLast(id,"Ошибка");
        statement=ClientStatement.build(book.entries(),id,zone);
        assertEquals(2,statement.select(ClientStatement.UNPAID).size());
        assertEquals(2,statement.select(ClientStatement.REVERSED).size());
        assertEquals(Long.valueOf(10000L),statement.remaining.get(first));
        assertEquals(Long.valueOf(20000L),statement.remaining.get(second));
    }

    @Test public void existingClientDraftKeepsDateAndCannotRepeatPayment() throws Exception {
        String client=addCustomer();
        LocalDate day=LocalDate.now().minusDays(3);
        String storage="raschet-qa-entry-draft-"+java.util.UUID.randomUUID();
        LedgerDrafts drafts=new LedgerDrafts(testContext,storage);
        String debtId=java.util.UUID.randomUUID().toString(),paymentId=java.util.UUID.randomUUID().toString();
        try{
            drafts.save(client,"debt",debtId,day,"100","", "Хлеб");
            LedgerDrafts.Draft restored=new LedgerDrafts(testContext,storage).get(client,"debt");
            assertEquals(day,restored.day);assertEquals(debtId,restored.id);
            book.recordDatedDebt(client,"","",10000L,restored.day,"",restored.note,restored.id);
            assertEquals(day,PeriodReport.dateOf(book.entries().get(0),java.time.ZoneId.systemDefault()));
            try{book.recordDatedDebt(client,"","",10000L,day,"","",debtId);fail("Duplicate debt accepted");}
            catch(IllegalArgumentException expected){}
            drafts.save(client,"payment",paymentId,LocalDate.now(),"40","","");
            book.addDraftPayment(client,4000L,"",paymentId);
            try{book.addDraftPayment(client,4000L,"",paymentId);fail("Duplicate payment accepted");}
            catch(IllegalArgumentException expected){}
            assertEquals(2,book.entries().size());assertEquals(6000L,LedgerStore.balance(book.entries(),client));
            drafts.clearAll();assertNull(drafts.get(client,"payment"));assertNull(drafts.get(client,"debt"));
        }finally{drafts.clearAll();}
    }
}
