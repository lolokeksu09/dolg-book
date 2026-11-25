package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import dev.ventry.raschet.domain.DatedLedger;

/** Java compatibility facade for the Kotlin calendar projection. */
final class DatedPayments {
    private DatedPayments(){}
    static long maximum(List<LedgerStore.Entry> entries,String id,LocalDate day,LocalDate today,ZoneId zone){
        return DatedLedger.maximum(entries,id,day,today,zone);
    }
}
