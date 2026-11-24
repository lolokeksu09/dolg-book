package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoricalInteropTest {
    @Test public void historicalGuardAndCollectionFacadeUseStoredEntries() {
        LocalDate today = LocalDate.parse("2026-11-24");
        ZoneId zone = ZoneId.of("Europe/Moscow");
        List<LedgerStore.Entry> rows = Arrays.asList(
                new LedgerStore.Entry("1","a","debt",10000L,"2026-11-20T22:30:00Z","2026-11-23","",""),
                new LedgerStore.Entry("2","a","payment",8000L,"2026-11-22T09:00:00Z","","",""),
                new LedgerStore.Entry("3","a","reversal",8000L,"2026-11-23T09:00:00Z","","","2"),
                new LedgerStore.Entry("4","b","debt",5000L,"2026-11-23T09:00:00Z","2026-12-01","",""));
        assertEquals(0L,DatedPayments.maximum(rows,"a",LocalDate.parse("2026-11-20"),today,zone));
        assertEquals(2000L,DatedPayments.maximum(rows,"a",LocalDate.parse("2026-11-21"),today,zone));
        assertEquals(10000L,DatedPayments.maximum(rows,"a",today,today,zone));
        List<CollectionsInsights.CustomerStatus> statuses = CollectionsInsights.analyze(Arrays.asList(
                new LedgerStore.Client("a","A",""), new LedgerStore.Client("b","B",""),
                new LedgerStore.Client("c","C","")),rows,today);
        assertEquals(3,statuses.size());
        assertEquals(10000L,statuses.get(0).balance);
        assertEquals(10000L,statuses.get(0).overdue);
        assertEquals(2,statuses.get(0).lastOperationIndex);
        assertEquals(5000L,statuses.get(1).upcoming);
        assertEquals(LocalDate.parse("2026-12-01"),statuses.get(1).nextDue);
        assertEquals(0L,statuses.get(2).balance);
        assertEquals(-1,statuses.get(2).lastOperationIndex);
    }
}
