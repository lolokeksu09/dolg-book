package dev.ventry.raschet.domain

import java.time.LocalDate
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class AnalyticsPeriodsTest {
    private fun date(value: String) = LocalDate.parse(value)
    private fun resolve(preset: AnalyticsPeriods.Preset, today: String) =
        AnalyticsPeriods.resolve(AnalyticsPeriods.Selection(preset), date(today), null)

    @Test fun weekStartsMondayAndMatchesElapsedDays() {
        val resolved = resolve(AnalyticsPeriods.Preset.WEEK, "2026-11-25")
        assertEquals(date("2026-11-23"), resolved.range.from)
        assertEquals(date("2026-11-25"), resolved.range.to)
        assertEquals(date("2026-11-16"), resolved.previous?.from)
        assertEquals(date("2026-11-18"), resolved.previous?.to)
    }

    @Test fun shortMonthsAndLeapYearsDoNotOverlap() {
        val month = resolve(AnalyticsPeriods.Preset.MONTH, "2024-03-31")
        assertEquals(date("2024-02-01"), month.previous?.from)
        assertEquals(date("2024-02-29"), month.previous?.to)
        val year = resolve(AnalyticsPeriods.Preset.YEAR, "2024-12-31")
        assertEquals(date("2023-12-31"), year.previous?.to)
        val quarter = resolve(AnalyticsPeriods.Preset.QUARTER, "2026-05-15")
        assertEquals(date("2026-04-01"), quarter.range.from)
        assertEquals(date("2026-01-01"), quarter.previous?.from)
        assertEquals(date("2026-02-14"), quarter.previous?.to)
    }

    @Test fun customAndAllTime() {
        val range = AnalyticsProjection.Range(date("2026-11-10"), date("2026-11-12"))
        val custom = AnalyticsPeriods.resolve(AnalyticsPeriods.Selection(AnalyticsPeriods.Preset.CUSTOM, range), date("2026-11-25"), null)
        assertEquals(date("2026-11-07"), custom.previous?.from)
        assertEquals(date("2026-11-09"), custom.previous?.to)
        val all = AnalyticsPeriods.resolve(AnalyticsPeriods.Selection(AnalyticsPeriods.Preset.ALL), date("2026-11-25"), date("2000-01-01"))
        assertEquals(date("2000-01-01"), all.range.from)
        assertNull(all.previous)
        assertNull(AnalyticsPeriods.Change.between(500, 0).percent)
        assertEquals(BigDecimal("25.0"), AnalyticsPeriods.Change.between(125, 100).percent)
    }

    @Test fun calendarSeriesIncludesEmptyDaysAndCarriesBalance() {
        val range = AnalyticsProjection.Range(date("2026-11-01"), date("2026-11-05"))
        val flow = AnalyticsProjection.Movement(300, 0, 0, 0, 1, 0, 0, 0)
        val buckets = AnalyticsSeries.build(range, 100, listOf(AnalyticsProjection.Day(date("2026-11-03"), flow, 400)))
        assertEquals(5, buckets.size)
        assertEquals(listOf(100L, 100L, 400L, 400L, 400L), buckets.map { it.closing })
        assertEquals(300L, buckets.sumOf { it.movement.debts })
        val all = AnalyticsSeries.build(AnalyticsProjection.Range(date("1900-01-01"), date("2026-11-25")), 200, emptyList())
        assertTrue(all.size <= 120)
        assertEquals(date("1900-01-01"), all.first().from)
        assertEquals(date("2026-11-25"), all.last().to)
        assertTrue(all.all { it.closing == 200L })
    }
}
