package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** Calendar reports count reversals on their recorded day, not the original day. */
object ReportProjection {
    data class Totals(val debts: Long, val payments: Long, val debtReversals: Long, val paymentReversals: Long, val count: Int) {
        val net: Long get() = Math.addExact(Math.subtractExact(Math.subtractExact(debts, payments), debtReversals), paymentReversals)
    }

    data class Report(val opening: Long, val totals: Totals, val daily: Map<LocalDate, Totals>, val activeCustomers: Int) {
        val closing: Long get() = Math.addExact(opening, totals.net)
    }

    private enum class Kind { DEBT, PAYMENT, DEBT_REVERSAL, PAYMENT_REVERSAL }

    private class Accumulator {
        var debts = 0L
        var payments = 0L
        var debtReversals = 0L
        var paymentReversals = 0L
        var count = 0
        fun add(kind: Kind, amount: Long) {
            when (kind) {
                Kind.DEBT -> debts = Math.addExact(debts, amount)
                Kind.PAYMENT -> payments = Math.addExact(payments, amount)
                Kind.DEBT_REVERSAL -> debtReversals = Math.addExact(debtReversals, amount)
                Kind.PAYMENT_REVERSAL -> paymentReversals = Math.addExact(paymentReversals, amount)
            }
            count = Math.incrementExact(count)
        }
        fun freeze() = Totals(debts, payments, debtReversals, paymentReversals, count)
    }

    private fun kind(entry: DatedLedgerRecord, earlier: Map<String, DatedLedgerRecord>): Kind = when (entry.operation) {
        "debt" -> Kind.DEBT
        "payment" -> Kind.PAYMENT
        "reversal" -> {
            val original = earlier[entry.reversedId]
            require(original != null && original.ownerId == entry.ownerId && original.amountKopecks == entry.amountKopecks) { "Нарушена связь отмены." }
            when (original.operation) {
                "debt" -> Kind.DEBT_REVERSAL
                "payment" -> Kind.PAYMENT_REVERSAL
                else -> throw IllegalArgumentException("Недопустимый тип отмены.")
            }
        }
        else -> throw IllegalArgumentException("Неизвестная операция.")
    }

    @JvmStatic
    fun build(entries: List<DatedLedgerRecord>, from: LocalDate, to: LocalDate, zone: ZoneId): Report {
        require(!from.isAfter(to) && !to.isAfter(from.plusDays(365))) { "Выберите период от 1 до 366 дней." }
        val earlier = HashMap<String, DatedLedgerRecord>()
        val before = Accumulator()
        val during = Accumulator()
        val daily = TreeMap<LocalDate, Accumulator>()
        val customers = HashSet<String>()
        for (entry in entries) {
            val date = DatedLedger.localDate(entry.recordedAt, zone)
            if (date.isBefore(from)) {
                before.add(kind(entry, earlier), entry.amountKopecks)
            } else if (!date.isAfter(to)) {
                val operation = kind(entry, earlier)
                during.add(operation, entry.amountKopecks)
                daily.getOrPut(date) { Accumulator() }.add(operation, entry.amountKopecks)
                customers.add(entry.ownerId)
            }
            earlier[entry.entryId] = entry
        }
        return Report(before.freeze().net, during.freeze(), daily.mapValues { it.value.freeze() }, customers.size)
    }
}
