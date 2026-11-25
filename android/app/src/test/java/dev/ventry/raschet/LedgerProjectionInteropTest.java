package dev.ventry.raschet;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the actual production Java facade and stored Entry representation. */
public class LedgerProjectionInteropTest {
    @Test public void fifoAndReversalsDelegateThroughStoredEntries() {
        List<LedgerStore.Entry> rows = Arrays.asList(
                entry("1", "a", "debt", 7000L, "2026-12-01", ""),
                entry("2", "a", "debt", 8000L, "2026-11-20", ""),
                entry("3", "b", "debt", 9000L, "2026-11-20", ""),
                entry("4", "a", "payment", 4000L, "", ""),
                entry("5", "a", "reversal", 4000L, "", "4"),
                entry("6", "a", "payment", 9000L, "", ""));
        LocalDate today = LocalDate.parse("2026-11-24");
        assertEquals(6000L, LedgerStore.balance(rows, "a"));
        assertEquals(6000L, LedgerStore.overdue(rows, "a", today));
        assertEquals(9000L, LedgerStore.balance(rows, "b"));
        assertEquals(9000L, LedgerStore.overdue(rows, "b", today));
        assertEquals(java.util.Collections.singleton("4"), LedgerStore.canceled(rows));
    }

    private static LedgerStore.Entry entry(String id, String client, String type, long amount,
                                           String due, String reverses) {
        return new LedgerStore.Entry(id, client, type, amount,
                "2026-11-24T08:00:00Z", due, "", reverses);
    }
}
