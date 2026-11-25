package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AnalyticsProjectionTest {
    private data class Record(
        override val entryId: String, override val ownerId: String,
        override val operation: String, override val amountKopecks: Long,
        override val recordedAt: String, override val dueDate: String = "",
        override val reversedId: String = ""
    ) : DatedLedgerRecord

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-11-25")
    private fun date(day: Int) = today.withDayOfMonth(day)
    private fun row(id: String, owner: String, kind: String, amount: Long, day: Int,
                    due: String = "", reversed: String = "") =
        Record(id, owner, kind, amount, date(day).toString() + "T12:00:00Z", due, reversed)

    @Test fun historicalAndCurrentBalancesAreIndependent() {
        val rows = listOf(
            row("a0", "a", "debt", 10000, 1, "2026-11-05"),
            row("b0", "b", "debt", 5000, 2),
            row("a1", "a", "debt", 3000, 10),
            row("b1", "b", "payment", 5000, 11),
            row("a2", "a", "payment", 2000, 12),
            row("a3", "a", "reversal", 2000, 13, reversed = "a2"),
            row("c1", "c", "debt", 7000, 14),
            row("c2", "c", "reversal", 7000, 15, reversed = "c1"),
            row("a4", "a", "reversal", 3000, 20, reversed = "a1"),
            row("a5", "a", "payment", 4000, 21)
        )
        val range = AnalyticsProjection.Range(date(10), date(15))
        val report = AnalyticsProjection.build(rows, listOf("a", "b", "c", "empty"), range, today, zone)
        assertEquals(15000L, report.opening)
        assertEquals(13000L, report.closing)
        assertEquals(6000L, report.current.balance)
        assertEquals(6000L, report.current.overdue)
        assertEquals(1, report.currentDebtors)
        assertEquals(2, report.openingDebtors)
        assertEquals(1, report.closingDebtors)
        assertEquals(10000L, report.movement.debts)
        assertEquals(7000L, report.movement.payments)
        assertEquals(7000L, report.movement.debtReversals)
        assertEquals(2000L, report.movement.paymentReversals)
        assertEquals(6, report.movement.count)
        assertEquals(3000L, report.effective.debts)
        assertEquals(1, report.effective.debtCount)
        assertEquals(5000L, report.effective.payments)
        assertEquals(1, report.effective.paymentCount)
        assertEquals(3000L, report.effective.maximumDebt)
        assertEquals(3, report.activeCustomers)
        assertEquals(1, report.borrowingCustomers)
        assertEquals(0, report.firstTimeBorrowers)
        assertEquals(1, report.settledCustomers)
        val a = report.customers.single { it.id == "a" }
        assertEquals(10000L, a.opening)
        assertEquals(13000L, a.closing)
        assertEquals(6000L, a.current.balance)
        assertEquals(date(1), a.firstDebtAsOfEnd)
        assertEquals(date(10), a.lastDebtAsOfEnd)
        assertNull(a.lastPaymentAsOfEnd)
        assertNull(report.customers.single { it.id == "empty" }.effective.maximumDebt)
        assertEquals(report.closing, report.daily.last().closing)
        val existing = ReportProjection.build(rows, range.from, range.to, zone)
        assertEquals(existing.opening, report.opening)
        assertEquals(existing.closing, report.closing)
        assertEquals(existing.totals.net, report.movement.net)
        assertEquals(existing.activeCustomers, report.activeCustomers)
        val next = AnalyticsProjection.build(rows, emptyList(), AnalyticsProjection.Range(date(16), today), today, zone)
        assertEquals(report.closing, next.opening)
        assertEquals(report.current, next.current)
        val focused = AnalyticsProjection.build(rows, listOf("a", "b", "c"), range, today, zone, "a")
        assertEquals(listOf(date(10), date(12), date(13)), focused.focusedDaily.map { it.date })
        assertEquals(listOf(13000L, 11000L, 13000L), focused.focusedDaily.map { it.closing })
        val focusedB = AnalyticsProjection.build(rows, listOf("a", "b", "c"), range, today, zone, "b")
        assertEquals(listOf(date(11)), focusedB.focusedDaily.map { it.date })
        assertEquals(0L, focusedB.focusedDaily.single().closing)
        assertEquals(focused.current, focusedB.current)
        assertEquals(focused.daily, focusedB.daily)
    }

    @Test fun longAndEmptyPeriodsRemainSparse() {
        val rows = listOf(row("a", "a", "debt", 999L, 10))
        val all = AnalyticsProjection.build(rows, listOf("a", "b"),
            AnalyticsProjection.Range(LocalDate.parse("2000-01-01"), today), today, zone)
        assertEquals(999L, all.closing)
        assertEquals(1, all.firstTimeBorrowers)
        assertEquals(1, all.daily.size)
        val empty = AnalyticsProjection.build(rows, listOf("a", "b"),
            AnalyticsProjection.Range(date(20), today), today, zone)
        assertEquals(999L, empty.opening)
        assertEquals(999L, empty.closing)
        assertEquals(0, empty.activeCustomers)
        assertEquals(0, empty.effective.debtCount)
        assertNull(empty.effective.maximumDebt)
        assertTrue(empty.daily.isEmpty())
    }

    @Test fun localDatesAndBackdatedEntriesMatchExistingReport() {
        val moscow = ZoneId.of("Europe/Moscow")
        val rows = listOf(
            Record("late", "a", "debt", 700, "2026-11-12T22:30:00Z"),
            Record("earlier", "a", "debt", 300, "2026-11-11T12:00:00Z")
        )
        val range = AnalyticsProjection.Range(date(12), date(13))
        val snapshot = AnalyticsProjection.build(rows, emptyList(), range, today, moscow)
        assertEquals(300L, snapshot.opening)
        assertEquals(1000L, snapshot.closing)
        assertEquals(date(13), snapshot.daily.single().date)
        assertEquals(ReportProjection.build(rows, range.from, range.to, moscow).closing, snapshot.closing)
    }

    @Test fun rejectsDuplicateAndInvalidReversals() {
        val debt = row("a", "a", "debt", 100, 1)
        val range = AnalyticsProjection.Range(date(1), today)
        val invalid = listOf(
            listOf(debt, debt),
            listOf(debt, row("bad", "b", "reversal", 100, 2, reversed = "a")),
            listOf(debt, row("bad", "a", "reversal", 99, 2, reversed = "a")),
            listOf(debt, row("r1", "a", "reversal", 100, 2, reversed = "a"),
                row("r2", "a", "reversal", 100, 3, reversed = "a"))
        )
        for (rows in invalid) {
            try {
                AnalyticsProjection.build(rows, emptyList(), range, today, zone)
                fail("Invalid ledger accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
