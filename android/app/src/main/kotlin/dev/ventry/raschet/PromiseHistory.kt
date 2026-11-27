package dev.ventry.raschet

import dev.ventry.raschet.data.LedgerSnapshot
import java.time.LocalDate
import java.time.ZoneId

internal fun LedgerSnapshot.currentFollowUps(): Map<String, ClientDirectory.FollowUp> =
    plans.filter { it.active() }.associate { it.clientId to it.plan() }

/** History uses disjoint insertion intervals, not a second balance or a second payment ledger.
 * Reversals are evaluated from the current committed snapshot; historical results can be corrected.
 */
internal object PromiseHistory {
    enum class Status(val caption: String) {
        TIMELY("Выполнено в срок"), LATE("Выполнено после срока"),
        MISSED("Не выполнено в срок"), CANCELED("Закрыто до срока"),
        WAITING("Ожидается"), REVIEW("Требуется проверка")
    }
    data class Row(val record: FollowUpJournal.Record, val name: String, val received: Long,
        val onTime: Long, val status: Status)
    data class Summary(val rows: List<Row>, val counts: Map<Status, Int>,
        val promised: Long, val received: Long) {
        fun count(status: Status) = counts[status] ?: 0
    }

    fun project(snapshot: LedgerSnapshot, today: LocalDate, zone: ZoneId): List<Row> {
        val people = snapshot.clients.associate { it.id to it.name }
        val grouped = snapshot.entries.groupBy { it.clientId }
        val indexes = grouped.mapValues { (_, entries) -> entries.mapIndexed { i, e -> e.id to i }.toMap() }
        val canceled = LedgerStore.canceled(snapshot.entries)
        return snapshot.plans.asSequence().filter { it.amount > 0L }.map { record ->
            val own = grouped[record.clientId].orEmpty()
            val index = indexes[record.clientId].orEmpty()
            val start = index[record.anchor]
            val end = if (record.active()) own.lastIndex else index[record.endAnchor]
            val name = people[record.clientId] ?: "Клиент не найден"
            if (start == null || end == null || end < start || record.clientId !in people) {
                Row(record, name, 0, 0, Status.REVIEW)
            } else {
                var received = 0L
                var onTime = 0L
                for (i in start + 1..end) {
                    val entry = own[i]
                    if (entry.type != "payment" || entry.id in canceled) continue
                    received = Math.addExact(received, entry.amount)
                    if (!PeriodReport.dateOf(entry, zone).isAfter(record.due))
                        onTime = Math.addExact(onTime, entry.amount)
                }
                val status = when {
                    received >= record.amount && onTime >= record.amount -> Status.TIMELY
                    received >= record.amount -> Status.LATE
                    record.closed != null && !record.closed.isAfter(record.due) -> Status.CANCELED
                    (record.closed ?: today).isAfter(record.due) -> Status.MISSED
                    else -> Status.WAITING
                }
                Row(record, name, minOf(received, record.amount), minOf(onTime, record.amount), status)
            }
        }.sortedWith(compareByDescending<Row> { it.record.due }.thenByDescending { it.record.opened }.thenBy { it.record.id }).toList()
    }

    /** Range is the latest agreed due date, inclusive, not the date of entry or cash receipt. */
    fun select(rows: List<Row>, today: LocalDate, days: Int?, query: String): Summary {
        require(days == null || days in listOf(7, 30, 90))
        val first = days?.let { today.minusDays(it.toLong() - 1) }
        val filtered = rows.filter {
            (first == null || (!it.record.due.isBefore(first) && !it.record.due.isAfter(today))) &&
                it.name.contains(query.trim(), ignoreCase = true)
        }
        val valid = filtered.filter { it.status != Status.REVIEW }
        return Summary(filtered, filtered.groupingBy { it.status }.eachCount(),
            valid.sumOf { it.record.amount }, valid.sumOf { it.received })
    }
}
