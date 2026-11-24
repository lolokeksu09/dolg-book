package dev.ventry.raschet.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.TreeMap

interface DatedLedgerRecord : LedgerRecord {
    val recordedAt: String
}

/** Calendar projection uses local dates; allocation continues to use insertion order. */
object DatedLedger {
    @JvmStatic
    fun localDate(timestamp: String, zone: ZoneId): LocalDate {
        val instant = try {
            Instant.parse(timestamp)
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(timestamp).toInstant()
        }
        return instant.atZone(zone).toLocalDate()
    }

    @JvmStatic
    fun maximum(rows: List<DatedLedgerRecord>, client: String, day: LocalDate?, today: LocalDate, zone: ZoneId): Long {
        if (day == null || day.isAfter(today)) return 0L
        val current = maxOf(0L, LedgerProjection.balance(rows, client))
        if (day == today) return current
        val changes = TreeMap<LocalDate, Long>()
        val earlier = HashMap<String, DatedLedgerRecord>()
        for (row in rows) {
            if (row.ownerId != client) continue
            val delta = when (row.operation) {
                "debt" -> row.amountKopecks
                "payment" -> -row.amountKopecks
                "reversal" -> {
                    val source = requireNotNull(earlier[row.reversedId]) { "Нарушена связь отмены." }
                    if (source.operation == "debt") -row.amountKopecks else row.amountKopecks
                }
                else -> throw IllegalArgumentException("Неизвестная операция.")
            }
            val date = localDate(row.recordedAt, zone)
            changes[date] = Math.addExact(changes[date] ?: 0L, delta)
            earlier[row.entryId] = row
        }
        changes.putIfAbsent(day, 0L)
        var running = 0L
        var minimum = current
        for ((date, delta) in changes) {
            if (date.isAfter(today)) break
            running = Math.addExact(running, delta)
            if (!date.isBefore(day)) minimum = minOf(minimum, running)
        }
        return maxOf(0L, minimum)
    }
}
