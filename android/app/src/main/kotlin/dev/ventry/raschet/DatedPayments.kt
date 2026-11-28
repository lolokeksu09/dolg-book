package dev.ventry.raschet

import dev.ventry.raschet.domain.DatedLedger
import java.time.LocalDate
import java.time.ZoneId

/** Compatibility entry point; all historical arithmetic stays in the shared Kotlin domain. */
object DatedPayments {
    @JvmStatic
    fun maximum(entries: List<LedgerStore.Entry>, id: String, day: LocalDate, today: LocalDate, zone: ZoneId): Long =
        DatedLedger.maximum(entries, id, day, today, zone)
}
