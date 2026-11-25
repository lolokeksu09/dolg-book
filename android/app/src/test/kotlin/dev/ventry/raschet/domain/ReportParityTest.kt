package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportParityTest {
    private data class Row(
        override val entryId: String, override val ownerId: String, override val operation: String,
        override val amountKopecks: Long, override val recordedAt: String, override val reversedId: String,
        override val dueDate: String = ""
    ) : DatedLedgerRecord

    @Test fun sharedRustReportFixture() {
        var header: List<String>? = null
        val rows = mutableListOf<Row>()
        val daily = sortedMapOf<LocalDate, ReportProjection.Totals>()
        var checked = 0
        fun day(value: String) = LocalDate.ofEpochDay(value.toLong())
        fun totals(f: List<String>) = ReportProjection.Totals(f[0].toLong(), f[1].toLong(), f[2].toLong(), f[3].toLong(), f[4].toInt())
        fun verify() {
            val h = header ?: return
            val report = ReportProjection.build(rows, day(h[2]), day(h[3]), ZoneOffset.UTC)
            assertEquals(h[4].toLong(), report.opening)
            assertEquals(h[5].toLong(), report.closing)
            assertEquals(totals(h.subList(6, 11)), report.totals)
            assertEquals(h[11].toInt(), report.activeCustomers)
            assertEquals(daily, report.daily)
            checked++
        }
        val input = checkNotNull(javaClass.getResourceAsStream("/report-epoch.tsv")).bufferedReader().use { it.readLines() }
        for (line in input) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('|')
            when (f[0]) {
                "case" -> { verify(); header = f; rows.clear(); daily.clear() }
                "row" -> rows += Row(f[1], f[2], f[3], f[4].toLong(), day(f[5]).atStartOfDay().toInstant(ZoneOffset.UTC).toString(), f[6])
                "daily" -> daily[day(f[1])] = totals(f.drop(2))
                else -> error(line)
            }
        }
        verify()
        assertEquals(5, checked)
    }
}
