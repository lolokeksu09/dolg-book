package dev.ventry.raschet.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.domain.DatedLedgerRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NativeLedgerInstrumentedTest {
    private data class Row(
        override val entryId: String, override val ownerId: String, override val operation: String,
        override val amountKopecks: Long, override val recordedAt: String, override val dueDate: String = "",
        override val reversedId: String = ""
    ): DatedLedgerRecord

    @Test fun allFourProjectionsExecuteOnAndroid() {
        assertTrue(BuildConfig.RUST_PREVIEW)
        val today = LocalDate.of(2026, 11, 24)
        val from = today.minusDays(3)
        val zone = ZoneId.of("UTC")
        val rows = listOf(
            Row("долг|1", "клиент", "debt", 35000, "2026-11-21T09:00:00Z", "2026-11-22"),
            Row("p", "клиент", "payment", 10000, "2026-11-22T09:00:00Z"),
            Row("r", "клиент", "reversal", 10000, "2026-11-23T09:00:00Z", reversedId = "p"),
            Row("p2", "клиент", "payment", 10000, "2026-11-24T09:00:00Z"),
            Row("other", "другой", "debt", 5000, "2026-11-21T11:00:00Z", "2026-11-27")
        )
        assertEquals(25000L, NativeLedgerEngine.balance(rows, "клиент"))
        assertEquals(25000L, NativeLedgerEngine.overdue(rows, "клиент", today))
        assertEquals(25000L, NativeLedgerEngine.historical(rows, "клиент", from, today, zone))
        assertEquals(0L, NativeLedgerEngine.historical(rows, "absent", from, today, zone))
        val collection = NativeLedgerEngine.collections(rows, today)
        assertEquals(25000L, collection.getValue("клиент").overdue)
        assertEquals(5000L, collection.getValue("другой").upcoming)
        assertEquals(today.plusDays(3), collection.getValue("другой").nextDue)
        val report = NativeLedgerEngine.report(rows, from, today, zone)
        assertEquals(30000L, report.closing)
        assertEquals(40000L, report.totals.debts)
        assertEquals(20000L, report.totals.payments)
        assertEquals(10000L, report.totals.paymentReversals)
        assertEquals(2, report.activeCustomers)
        assertEquals(4, report.daily.size)
        assertTrue(NativeLedgerEngine.collections(emptyList(), today).isEmpty())
    }
}
