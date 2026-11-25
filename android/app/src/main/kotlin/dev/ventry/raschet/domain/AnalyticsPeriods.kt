package dev.ventry.raschet.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Calendar policy independent of Android, device time is supplied by the book snapshot. */
object AnalyticsPeriods {
    enum class Preset { DAY, WEEK, MONTH, QUARTER, YEAR, ALL, CUSTOM }

    data class Selection(val preset: Preset = Preset.MONTH, val custom: AnalyticsProjection.Range? = null) {
        init { require((preset == Preset.CUSTOM) == (custom != null)) }
    }

    data class Resolved(val range: AnalyticsProjection.Range, val previous: AnalyticsProjection.Range?)

    @JvmStatic
    fun resolve(selection: Selection, today: LocalDate, firstDate: LocalDate?): Resolved {
        val preset = selection.preset
        if (preset == Preset.ALL) {
            return Resolved(AnalyticsProjection.Range(minOf(firstDate ?: today, today), today), null)
        }
        if (preset == Preset.CUSTOM) {
            val range = requireNotNull(selection.custom)
            require(!range.to.isAfter(today)) { "Период не может заканчиваться в будущем." }
            val length = ChronoUnit.DAYS.between(range.from, range.to) + 1
            return Resolved(range, AnalyticsProjection.Range(range.from.minusDays(length), range.from.minusDays(1)))
        }
        val start = when (preset) {
            Preset.DAY -> today
            Preset.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Preset.MONTH -> today.withDayOfMonth(1)
            Preset.QUARTER -> today.withMonth((today.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            Preset.YEAR -> today.withDayOfYear(1)
            else -> error("Unsupported preset")
        }
        val previousStart = when (preset) {
            Preset.DAY -> start.minusDays(1)
            Preset.WEEK -> start.minusWeeks(1)
            Preset.MONTH -> start.minusMonths(1)
            Preset.QUARTER -> start.minusMonths(3)
            Preset.YEAR -> start.minusYears(1)
            else -> error("Unsupported preset")
        }
        val elapsed = ChronoUnit.DAYS.between(start, today)
        // Match elapsed days in the previous calendar period, capped at its actual end.
        val previousEnd = minOf(previousStart.plusDays(elapsed), start.minusDays(1))
        return Resolved(AnalyticsProjection.Range(start, today), AnalyticsProjection.Range(previousStart, previousEnd))
    }

    /** Null percentage is an explicit zero-base state; money remains integral. */
    data class Change(val absolute: Long, val percent: java.math.BigDecimal?) {
        companion object {
            fun between(current: Long, previous: Long): Change {
                val absolute = Math.subtractExact(current, previous)
                val percent = if (previous == 0L) null else java.math.BigDecimal.valueOf(absolute)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .divide(java.math.BigDecimal.valueOf(previous), 1, java.math.RoundingMode.HALF_UP)
                return Change(absolute, percent)
            }
        }
    }
}
