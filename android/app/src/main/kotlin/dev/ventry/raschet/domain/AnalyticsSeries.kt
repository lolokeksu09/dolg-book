package dev.ventry.raschet.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Turns the sparse financial projection into a bounded, gap-free calendar chart. */
object AnalyticsSeries {
    data class Bucket(
        val from: LocalDate, val to: LocalDate, val opening: Long,
        val closing: Long, val movement: AnalyticsProjection.Movement
    )

    private val zero = AnalyticsProjection.Movement(0, 0, 0, 0, 0, 0, 0, 0)

    private fun add(a: AnalyticsProjection.Movement, b: AnalyticsProjection.Movement) =
        AnalyticsProjection.Movement(
            Math.addExact(a.debts, b.debts), Math.addExact(a.payments, b.payments),
            Math.addExact(a.debtReversals, b.debtReversals), Math.addExact(a.paymentReversals, b.paymentReversals),
            Math.addExact(a.debtCount, b.debtCount), Math.addExact(a.paymentCount, b.paymentCount),
            Math.addExact(a.debtReversalCount, b.debtReversalCount), Math.addExact(a.paymentReversalCount, b.paymentReversalCount)
        )

    @JvmStatic
    fun build(range: AnalyticsProjection.Range, opening: Long, daily: List<AnalyticsProjection.Day>): List<Bucket> {
        val length = ChronoUnit.DAYS.between(range.from, range.to) + 1
        val months = ChronoUnit.MONTHS.between(range.from.withDayOfMonth(1), range.to.withDayOfMonth(1)) + 1
        val monthStride = maxOf(1L, (months + 119L) / 120L)
        val result = ArrayList<Bucket>()
        var from = range.from
        var balance = opening
        var index = 0
        while (true) {
            val to = when {
                length <= 31 -> from
                length <= 182 -> minOf(range.to, from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
                else -> minOf(range.to, from.withDayOfMonth(1).plusMonths(monthStride).minusDays(1))
            }
            val startBalance = balance
            var flow = zero
            while (index < daily.size && !daily[index].date.isAfter(to)) {
                val day = daily[index++]
                require(!day.date.isBefore(from)) { "Дневные данные вне интервала или не упорядочены." }
                flow = add(flow, day.movement)
                balance = Math.addExact(balance, day.movement.net)
                check(balance == day.closing) { "Несогласованный остаток дня." }
            }
            result += Bucket(from, to, startBalance, balance, flow)
            if (to == range.to) break
            from = to.plusDays(1)
        }
        require(index == daily.size) { "Дневные данные выходят за пределы периода." }
        return result
    }
}
