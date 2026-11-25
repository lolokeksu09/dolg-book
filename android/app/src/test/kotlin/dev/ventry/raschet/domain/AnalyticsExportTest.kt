package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AnalyticsExportTest {
    private data class Record(
        override val entryId: String, override val ownerId: String,
        override val operation: String, override val amountKopecks: Long,
        override val recordedAt: String, override val dueDate: String = "",
        override val reversedId: String = ""
    ) : DatedLedgerRecord

    @Test fun aggregateContainsNoPersonalNamesAndPersonalReportIsScoped() {
        val today = LocalDate.parse("2026-11-25")
        val range = AnalyticsProjection.Range(today, today)
        val rows = listOf(Record("a", "alpha", "debt", 1500, "2026-11-25T08:00:00Z"),
            Record("b", "beta", "debt", 9999, "2026-11-25T09:00:00Z"))
        val snapshot = AnalyticsProjection.build(rows, listOf("alpha", "beta"), range, today, ZoneId.of("UTC"), "alpha")
        val general = AnalyticsExport.lines(snapshot, null, null, null, AnalyticsSeries.build(range, snapshot.opening, snapshot.daily))
        val personal = AnalyticsExport.lines(snapshot, null, "alpha", " =SUM(1;2)", AnalyticsSeries.build(range, 0L, snapshot.focusedDaily))
        assertEquals("114,99", general.single { it.label == "Текущий остаток (руб.)" }.value)
        assertEquals("15,00", personal.single { it.label == "Текущий остаток (руб.)" }.value)
        assertFalse(general.any { it.label == "Клиент" })
        val csv = AnalyticsExport.csv(personal)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"' =SUM(1;2)\""))
        assertFalse(csv.contains("99,99"))
        assertTrue(AnalyticsExport.csv(listOf(AnalyticsExport.Line("Изменение", "-10,00", true))).contains(";-10,00\r\n"))
    }

    @Test fun rejectsFormulaInNumericCell() {
        try {
            AnalyticsExport.csv(listOf(AnalyticsExport.Line("Число", "=1+1", true)))
            fail("Formula accepted as numeric value")
        } catch (_: IllegalArgumentException) { }
    }
}
