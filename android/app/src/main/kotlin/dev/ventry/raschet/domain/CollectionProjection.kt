package dev.ventry.raschet.domain

import java.time.LocalDate

/** Independent of Android UI, SQLite, and the client directory. */
object CollectionProjection {
    data class Status(val balance: Long, val overdue: Long, val upcoming: Long, val nextDue: LocalDate?, val lastOperationIndex: Int)

    @JvmField
    val EMPTY = Status(0L, 0L, 0L, null, -1)

    @JvmStatic
    fun analyze(entries: List<LedgerRecord>, today: LocalDate): Map<String, Status> {
        val reversed = LedgerProjection.canceled(entries)
        val active = HashMap<String, MutableList<LedgerRecord>>()
        val latest = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            latest[entry.ownerId] = index
            if (entry.operation != "reversal" && entry.entryId !in reversed) {
                active.getOrPut(entry.ownerId) { ArrayList() }.add(entry)
            }
        }
        val windowEnd = today.plusDays(7)
        return latest.mapValues { (client, index) ->
            val rows = active[client].orEmpty()
            var payments = 0L
            for (row in rows) if (row.operation == "payment") payments = Math.addExact(payments, row.amountKopecks)
            var balance = 0L
            var overdue = 0L
            var upcoming = 0L
            var next: LocalDate? = null
            for (row in rows) {
                if (row.operation != "debt") continue
                val applied = minOf(payments, row.amountKopecks)
                payments -= applied
                val unpaid = row.amountKopecks - applied
                balance = Math.addExact(balance, unpaid)
                if (unpaid == 0L || row.dueDate.isEmpty()) continue
                val due = LocalDate.parse(row.dueDate)
                if (due.isBefore(today)) {
                    overdue = Math.addExact(overdue, unpaid)
                } else if (!due.isAfter(windowEnd)) {
                    upcoming = Math.addExact(upcoming, unpaid)
                    if (next == null || due.isBefore(next)) next = due
                }
            }
            Status(balance, overdue, upcoming, next, index)
        }
    }
}
