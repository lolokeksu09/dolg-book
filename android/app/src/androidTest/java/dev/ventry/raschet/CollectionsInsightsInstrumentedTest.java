package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;

/** Uses a dedicated, disposable QA database and only fictional records. */
@RunWith(AndroidJUnit4.class)
public class CollectionsInsightsInstrumentedTest {
    private static final String QA_DB = "collections-insights-qa-only.db";
    private Context context;
    private LedgerStore ledger;
    private LocalDate today;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(QA_DB);
        ledger = new LedgerStore(context, QA_DB);
        today = LocalDate.now();
    }

    @After public void tearDown() {
        if (ledger != null) ledger.close();
        if (context != null) context.deleteDatabase(QA_DB);
    }

    @Test public void overdueAndDueSoonRespectOldestFirstPayments() {
        ledger.addClient("Алёна", "111");
        String id = ledger.clients().get(0).id;
        ledger.addEntry(id, "debt", 10000L, today.minusDays(1).toString(), "");
        ledger.addEntry(id, "debt", 5000L, today.plusDays(3).toString(), "");
        ledger.addEntry(id, "payment", 3000L, "", "");
        CollectionsInsights.CustomerStatus s = CollectionsInsights
                .analyze(ledger.clients(), ledger.entries(), today).get(0);
        assertEquals(12000L, s.balance);
        assertEquals(7000L, s.overdue);
        assertEquals(5000L, s.upcoming);
        assertEquals(today.plusDays(3), s.nextDue);
        assertEquals(LedgerStore.balance(ledger.entries(), id), s.balance);
        assertEquals(LedgerStore.overdue(ledger.entries(), id, today), s.overdue);
        ledger.reverseLast(id, "Ошибка оплаты");
        s = CollectionsInsights.analyze(ledger.clients(), ledger.entries(), today).get(0);
        assertEquals(15000L, s.balance);
        assertEquals(10000L, s.overdue);
    }

    @Test public void boundariesAndFiltersDoNotCountClosedOrFutureDebts() {
        ledger.addClient("А тест", "123");
        String a = ledger.clients().get(0).id;
        ledger.addEntry(a, "debt", 100L, today.toString(), "");
        ledger.addEntry(a, "debt", 200L, today.plusDays(7).toString(), "");
        ledger.addEntry(a, "debt", 400L, today.plusDays(8).toString(), "");
        ledger.addClient("Б тест", "999");
        String b = ledger.clients().stream().filter(c -> c.name.equals("Б тест")).findFirst().get().id;
        ledger.addEntry(b, "debt", 150L, today.minusDays(4).toString(), "");
        ledger.addEntry(b, "payment", 150L, "", "");
        List<CollectionsInsights.CustomerStatus> all =
                CollectionsInsights.analyze(ledger.clients(), ledger.entries(), today);
        assertEquals(2, CollectionsInsights.visible(all, CollectionsInsights.ALL,false,"").size());
        assertEquals(1, CollectionsInsights.visible(all, CollectionsInsights.OWING,false,"").size());
        assertEquals(1, CollectionsInsights.visible(all, CollectionsInsights.SOON,false,"").size());
        assertEquals(0, CollectionsInsights.visible(all, CollectionsInsights.LATE,false,"").size());
        assertEquals(1, CollectionsInsights.visible(all, CollectionsInsights.ALL,false,"123").size());
        CollectionsInsights.CustomerStatus status=CollectionsInsights
                .visible(all,CollectionsInsights.SOON,false,"").get(0);
        assertEquals(300L,status.upcoming);
        assertEquals(today,status.nextDue);
    }

    @Test public void amountSortingAndReversedDebtsAreConsistent() {
        ledger.addClient("А меньший", "");
        ledger.addClient("Б больший", "");
        List<LedgerStore.Client> customers=ledger.clients();
        String small=customers.stream().filter(c -> c.name.startsWith("А")).findFirst().get().id;
        String big=customers.stream().filter(c -> c.name.startsWith("Б")).findFirst().get().id;
        ledger.addEntry(small,"debt",500L,today.minusDays(2).toString(),"");
        ledger.reverseLast(small,"Ошибочная сумма");
        ledger.addEntry(big,"debt",3000L,today.plusDays(2).toString(),"");
        List<CollectionsInsights.CustomerStatus> sorted=CollectionsInsights.visible(
                CollectionsInsights.analyze(ledger.clients(),ledger.entries(),today),
                CollectionsInsights.ALL,true,"");
        assertEquals(big, sorted.get(0).client.id);
        assertEquals(0L,sorted.get(1).overdue);
        assertEquals(0L,sorted.get(1).balance);
    }
}
