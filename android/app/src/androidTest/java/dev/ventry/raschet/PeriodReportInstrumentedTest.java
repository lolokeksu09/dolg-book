package dev.ventry.raschet;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PeriodReportInstrumentedTest {
    private static final ZoneId UTC=ZoneId.of("UTC");
    private LedgerStore.Entry e(String id,String type,long amount,String date,String reverses){
        return new LedgerStore.Entry(id,"customer",type,amount,date,"","private note",reverses);
    }
    @Test public void reportIncludesReversalsOnTheirOwnDate(){
        var all=Arrays.asList(e("a","debt",120000,"2026-11-01T12:00:00Z",""),
            e("b","payment",40000,"2026-11-02T12:00:00Z",""),
            e("c","reversal",40000,"2026-11-03T12:00:00Z","b"),
            e("d","payment",50000,"2026-11-03T12:01:00Z",""),
            e("e","debt",1000,"2026-11-04T00:00:00Z",""));
        LocalDate d=LocalDate.parse("2026-11-02");
        PeriodReport prior=PeriodReport.build(all,d,d,UTC);
        assertEquals(120000,prior.opening);assertEquals(80000,prior.closing);
        PeriodReport report=PeriodReport.build(all,d.plusDays(1),d.plusDays(1),UTC);
        assertEquals(80000,report.opening);assertEquals(40000,report.totals.paymentReversals);
        assertEquals(50000,report.totals.payments);assertEquals(70000,report.closing);
        assertEquals(2,report.totals.count);assertEquals(1,report.activeCustomers);
        assertEquals(-10000,report.daily.values().iterator().next().net());
        assertFalse(report.csv().contains("private note"));
        assertTrue(report.csv().contains("Остаток на конец;700,00"));
        assertEquals(LedgerStore.balance(all,"customer"),PeriodReport.build(all,d.minusDays(1),d.plusDays(2),UTC).closing);
    }
    @Test public void midnightAndDebtCancellation(){
        var all=Arrays.asList(e("a","debt",10000,"2026-11-01T21:30:00Z",""),
                e("b","reversal",10000,"2026-11-03T00:00:00+03:00","a"));
        LocalDate d=LocalDate.parse("2026-11-02");
        ZoneId zone=ZoneId.of("Europe/Moscow");
        assertEquals(10000,PeriodReport.build(all,d,d,zone).closing);
        PeriodReport next=PeriodReport.build(all,d.plusDays(1),d.plusDays(1),zone);
        assertEquals(10000,next.totals.debtReversals);assertEquals(0,next.closing);
    }
    @Test public void invalidRangeAndEmptyPeriod(){
        LocalDate d=LocalDate.parse("2026-11-01");
        assertEquals(0,PeriodReport.build(Collections.emptyList(),d,d,UTC).closing);
        try{PeriodReport.build(Collections.emptyList(),d,d.minusDays(1),UTC);fail("reversed");}
        catch(IllegalArgumentException expected){}
        try{PeriodReport.build(Collections.emptyList(),d,d.plusDays(366),UTC);fail("too long");}
        catch(IllegalArgumentException expected){}
    }
}
