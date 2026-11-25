package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * Read-only period analytics over the same immutable ledger snapshot as the book.
 * Movement includes cancellations on their own dates; effective counts exclude
 * originals canceled by the reporting cutoff. Neither projection writes data.
 */
object AnalyticsProjection {
    data class Range(val from: LocalDate, val to: LocalDate) {
        init { require(!from.isAfter(to)) { "Начало периода позже окончания." } }
    }

    data class Movement(
        val debts: Long, val payments: Long, val debtReversals: Long, val paymentReversals: Long,
        val debtCount: Int, val paymentCount: Int, val debtReversalCount: Int, val paymentReversalCount: Int
    ) {
        val net: Long get() = Math.addExact(Math.subtractExact(Math.subtractExact(debts, payments), debtReversals), paymentReversals)
        val count: Int get() = Math.addExact(Math.addExact(debtCount, paymentCount), Math.addExact(debtReversalCount, paymentReversalCount))
    }

    data class Effective(
        val debts: Long, val payments: Long, val debtCount: Int, val paymentCount: Int,
        val maximumDebt: Long?
    )

    data class Current(val balance: Long, val overdue: Long, val upcoming: Long)

    data class Customer(
        val id: String, val current: Current, val opening: Long, val closing: Long,
        val movement: Movement, val effective: Effective, val firstDebtAsOfEnd: LocalDate?,
        val lastDebtAsOfEnd: LocalDate?, val lastPaymentAsOfEnd: LocalDate?
    )

    /** Sparse daily series. Missing dates have zero movement and carry forward the balance. */
    data class Day(val date: LocalDate, val movement: Movement, val closing: Long)

    data class Snapshot(
        val range: Range, val today: LocalDate, val current: Current,
        val currentDebtors: Int, val overdueDebtors: Int,
        val opening: Long, val closing: Long, val openingDebtors: Int, val closingDebtors: Int,
        val movement: Movement, val effective: Effective, val activeCustomers: Int,
        val borrowingCustomers: Int, val firstTimeBorrowers: Int, val settledCustomers: Int,
        val customers: List<Customer>, val daily: List<Day>
    )

    private class MovementBuilder {
        private val amounts = LongArray(4)
        private val counts = IntArray(4)
        fun add(kind: ReportProjection.Kind, amount: Long) {
            val i = when (kind) {
                ReportProjection.Kind.DEBT -> 0
                ReportProjection.Kind.PAYMENT -> 1
                ReportProjection.Kind.DEBT_REVERSAL -> 2
                ReportProjection.Kind.PAYMENT_REVERSAL -> 3
            }
            amounts[i] = Math.addExact(amounts[i], amount)
            counts[i] = Math.incrementExact(counts[i])
        }
        fun freeze() = Movement(amounts[0], amounts[1], amounts[2], amounts[3], counts[0], counts[1], counts[2], counts[3])
    }

    private class EffectiveBuilder {
        var debts = 0L
        var payments = 0L
        var debtCount = 0
        var paymentCount = 0
        var maximumDebt: Long? = null
        fun add(row: DatedLedgerRecord) {
            if (row.operation == "debt") {
                debts = Math.addExact(debts, row.amountKopecks)
                debtCount = Math.incrementExact(debtCount)
                maximumDebt = maxOf(maximumDebt ?: 0L, row.amountKopecks)
            } else {
                payments = Math.addExact(payments, row.amountKopecks)
                paymentCount = Math.incrementExact(paymentCount)
            }
        }
        fun freeze() = Effective(debts, payments, debtCount, paymentCount, maximumDebt)
    }

    private class CustomerBuilder {
        var opening = 0L
        val movement = MovementBuilder()
        val effective = EffectiveBuilder()
        var firstDebt: LocalDate? = null
        var lastDebt: LocalDate? = null
        var lastPayment: LocalDate? = null
    }

    private data class Original(val row: DatedLedgerRecord, val date: LocalDate)

    private fun delta(kind: ReportProjection.Kind, amount: Long): Long = when (kind) {
        ReportProjection.Kind.DEBT, ReportProjection.Kind.PAYMENT_REVERSAL -> amount
        ReportProjection.Kind.PAYMENT, ReportProjection.Kind.DEBT_REVERSAL -> Math.negateExact(amount)
    }

    @JvmStatic
    fun build(
        entries: List<DatedLedgerRecord>, customerIds: Collection<String>, range: Range,
        today: LocalDate, zone: ZoneId
    ): Snapshot {
        require(!range.to.isAfter(today)) { "Период не может заканчиваться в будущем." }
        val customers = LinkedHashMap<String, CustomerBuilder>()
        customerIds.forEach { customers.getOrPut(it) { CustomerBuilder() } }
        val earlier = HashMap<String, DatedLedgerRecord>()
        val originals = ArrayList<Original>()
        val canceledAsOfEnd = HashSet<String>()
        val allCanceled = HashSet<String>()
        val movement = MovementBuilder()
        val effective = EffectiveBuilder()
        val daily = TreeMap<LocalDate, MovementBuilder>()
        var opening = 0L

        for (row in entries) {
            require(row.amountKopecks > 0 && row.entryId !in earlier) { "Некорректная операция книги." }
            val date = DatedLedger.localDate(row.recordedAt, zone)
            val kind = ReportProjection.kind(row, earlier)
            val customer = customers.getOrPut(row.ownerId) { CustomerBuilder() }
            if (row.operation == "reversal") {
                require(allCanceled.add(row.reversedId)) { "Повторная отмена операции." }
                if (!date.isAfter(range.to)) canceledAsOfEnd.add(row.reversedId)
            } else if (!date.isAfter(range.to)) originals.add(Original(row, date))

            when {
                date.isBefore(range.from) -> {
                    val change = delta(kind, row.amountKopecks)
                    opening = Math.addExact(opening, change)
                    customer.opening = Math.addExact(customer.opening, change)
                }
                !date.isAfter(range.to) -> {
                    movement.add(kind, row.amountKopecks)
                    customer.movement.add(kind, row.amountKopecks)
                    daily.getOrPut(date) { MovementBuilder() }.add(kind, row.amountKopecks)
                }
            }
            earlier[row.entryId] = row
        }

        for ((row, date) in originals) {
            if (row.entryId in canceledAsOfEnd) continue
            val customer = customers.getValue(row.ownerId)
            if (row.operation == "debt") {
                if (customer.firstDebt?.let { date.isBefore(it) } != false) customer.firstDebt = date
                if (customer.lastDebt?.let { date.isAfter(it) } != false) customer.lastDebt = date
            } else if (customer.lastPayment?.let { date.isAfter(it) } != false) {
                customer.lastPayment = date
            }
            if (!date.isBefore(range.from)) {
                customer.effective.add(row)
                effective.add(row)
            }
        }

        // Reuse the production allocation rules for current balances and overdue debts.
        val statuses = CollectionProjection.analyze(entries, today)
        val result = customers.map { (id, state) ->
            val status = statuses[id] ?: CollectionProjection.EMPTY
            val flow = state.movement.freeze()
            Customer(id, Current(status.balance, status.overdue, status.upcoming), state.opening,
                Math.addExact(state.opening, flow.net), flow, state.effective.freeze(),
                state.firstDebt, state.lastDebt, state.lastPayment)
        }

        val totalMovement = movement.freeze()
        val closing = Math.addExact(opening, totalMovement.net)
        var running = opening
        val days = daily.map { (date, builder) ->
            val flow = builder.freeze()
            running = Math.addExact(running, flow.net)
            Day(date, flow, running)
        }
        check(running == closing) { "Нарушена согласованность дневных остатков." }
        fun sum(selector: (Customer) -> Long): Long = result.fold(0L) { total, customer ->
            Math.addExact(total, selector(customer))
        }
        check(sum { it.opening } == opening && sum { it.closing } == closing) { "Нарушена согласованность остатков клиентов." }
        return Snapshot(range, today,
            Current(sum { it.current.balance }, sum { it.current.overdue }, sum { it.current.upcoming }),
            result.count { it.current.balance > 0L }, result.count { it.current.overdue > 0L },
            opening, closing, result.count { it.opening > 0L }, result.count { it.closing > 0L },
            totalMovement, effective.freeze(), result.count { it.movement.count > 0 },
            result.count { it.effective.debtCount > 0 },
            result.count { it.firstDebtAsOfEnd?.let { date -> !date.isBefore(range.from) } == true },
            result.count { it.opening > 0L && it.closing == 0L }, result.toList(), days)
    }
}
