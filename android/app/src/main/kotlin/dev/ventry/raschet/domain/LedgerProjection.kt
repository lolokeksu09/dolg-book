package dev.ventry.raschet.domain

/** Immutable view of a stored entry; the existing SQLite model implements it without copying rows. */
interface LedgerRecord {
    val entryId: String
    val ownerId: String
    val operation: String
    val amountKopecks: Long
    val dueDate: String
    val reversedId: String
}

/** Read-only calculations over insertion order, never calendar order. */
object LedgerProjection {
    @JvmStatic
    fun canceled(rows: List<LedgerRecord>): Set<String> = rows.asSequence()
        .filter { it.operation == "reversal" }.map { it.reversedId }.toHashSet()

    @JvmStatic
    fun balance(rows: List<LedgerRecord>, clientId: String): Long {
        val reversed = canceled(rows)
        var sum = 0L
        for (row in rows) {
            if (row.ownerId != clientId || row.operation == "reversal" || row.entryId in reversed) continue
            sum = if (row.operation == "debt") Math.addExact(sum, row.amountKopecks)
            else Math.subtractExact(sum, row.amountKopecks)
        }
        return sum
    }

    @JvmStatic
    fun overdue(rows: List<LedgerRecord>, clientId: String, todayIso: String): Long {
        val reversed = canceled(rows)
        var paid = 0L
        for (row in rows) {
            if (row.ownerId == clientId && row.operation == "payment" && row.entryId !in reversed) {
                paid = Math.addExact(paid, row.amountKopecks)
            }
        }
        var overdue = 0L
        for (row in rows) {
            if (row.ownerId != clientId || row.operation != "debt" || row.entryId in reversed) continue
            val applied = minOf(paid, row.amountKopecks)
            paid -= applied
            // Preserve the existing LedgerStore ISO-string comparison exactly.
            if (row.dueDate.isNotEmpty() && row.dueDate < todayIso) {
                overdue = Math.addExact(overdue, row.amountKopecks - applied)
            }
        }
        return overdue
    }
}
