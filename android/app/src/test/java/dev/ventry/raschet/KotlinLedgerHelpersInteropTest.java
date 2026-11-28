package dev.ventry.raschet;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KotlinLedgerHelpersInteropTest {
    private final ZoneId zone = ZoneId.of("Europe/Moscow");
    private LedgerStore.Entry entry(String id, String client, String type, long cents, String date, String note, String reverses) {
        return new LedgerStore.Entry(id,client,type,cents,date,"",note,reverses);
    }
    @Test public void searchKeepsRussianPrefixesAndRejectsShortOrForeignEquivalence() {
        assertEquals("елка иван", ClientSearch.normalize("  Ёлка   Иван "));
        assertTrue(ClientSearch.phoneMatches("+7 (999) 123-45-67", "8 999 123 45 67"));
        assertTrue(ClientSearch.phoneMatches("+7 (999) 123-45-67", "123-45"));
        assertFalse(ClientSearch.phoneMatches("+7 (999) 123-45-67", "телефон 123"));
        assertFalse(ClientSearch.sameNumber("123456", "123456"));
        assertFalse(ClientSearch.sameNumber("+1 999 123 45 67", "8 999 123 45 67"));
        assertFalse(ClientSearch.phoneMatches(null, "123"));
    }
    @Test public void historyUsesLocalCalendarDateAndReverseInsertionOrder() {
        List<LedgerStore.Entry> rows=Arrays.asList(
            entry("a","c","debt",100,"2026-11-20T22:00:00Z","Ёлка",""),
            entry("b","c","payment",20,"2026-11-21T09:00:00Z","",""),
            entry("c","d","debt",300,"2026-11-22T09:00:00Z","Ёлка",""),
            entry("d","c","debt",50,"2026-11-20T23:00:00Z","",""));
        Map<String,LedgerStore.Client> people=Map.of("c",new LedgerStore.Client("c","Анна","+7 999 123-45-67"));
        LocalDate day=LocalDate.of(2026,11,21);
        List<LedgerStore.Entry> filtered=HistoryRows.select(rows,people,"c","8 999 123 45 67",day,day,"Все",zone);
        assertEquals(Arrays.asList("d","b","a"), filtered.stream().map(e->e.id).collect(Collectors.toList()));
        assertEquals(Arrays.asList("c","a"),HistoryRows.select(rows,people,null,"елка",null,null,"Долги",zone).stream().map(e->e.id).collect(Collectors.toList()));
        assertTrue(HistoryRows.select(rows,people,"c","absent",null,null,"Все",zone).isEmpty());
    }
    @Test public void recentDebtsSkipReversedEntriesAndKeepNewestActiveAmount() {
        List<LedgerStore.Entry> rows=Arrays.asList(
            entry("a","c","debt",100,"2026-11-21T09:00:00Z","",""),
            entry("b","c","debt",200,"2026-11-21T09:00:00Z","",""),
            entry("x","d","debt",300,"2026-11-21T09:00:00Z","",""),
            entry("r","c","reversal",200,"2026-11-21T10:00:00Z","","b"),
            entry("p","d","payment",25,"2026-11-21T10:00:00Z","",""));
        Map<String,Long> suggestions=RecentDebts.lastAmounts(rows);
        assertEquals(Arrays.asList("d","c"),new java.util.ArrayList<>(suggestions.keySet()));
        assertEquals(Long.valueOf(100),suggestions.get("c"));
        assertEquals(Long.valueOf(300),suggestions.get("d"));
    }
    @Test public void debtLimitBoundaryPreservesConsentRule() {
        assertEquals(200L,DebtLimits.remaining(1000,800));
        assertEquals(0L,DebtLimits.remaining(1000,1500));
        assertFalse(DebtLimits.requiresConfirmation(1000,800,200));
        assertTrue(DebtLimits.requiresConfirmation(1000,800,201));
        assertFalse(DebtLimits.requiresConfirmation(0,800,201));
        try { DebtLimits.requireConsent(1000,800,201,false); fail("consent required"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("лимит")); }
        DebtLimits.requireConsent(1000,800,201,true);
    }
    @Test public void historicalPaymentUsesSameProjectionAsDomain() {
        List<LedgerStore.Entry> rows=Arrays.asList(
            entry("a","c","debt",35000,"2026-11-21T09:00:00Z","",""),
            entry("b","c","payment",10000,"2026-11-21T10:00:00Z","",""));
        LocalDate day=LocalDate.of(2026,11,21);
        assertEquals(25000L,DatedPayments.maximum(rows,"c",day,day,zone));
        assertEquals(0L,DatedPayments.maximum(rows,"unknown",day,day,zone));
    }
}
