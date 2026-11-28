package dev.ventry.raschet.bridge

import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.domain.CollectionProjection
import dev.ventry.raschet.domain.DatedLedger
import dev.ventry.raschet.domain.DatedLedgerRecord
import dev.ventry.raschet.domain.LedgerProjection
import dev.ventry.raschet.domain.LedgerRecord
import dev.ventry.raschet.domain.ReportProjection
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Native calculation is authoritative only when the independent Kotlin contract agrees. */
object NativeLedgerEngine {
    private const val WIDTH = 7
    private const val MAX_ROWS = 100_000
    private val loaded by lazy { NativeCorrection.loadAndroidLibrary(); true }
    private data class Batch(val values: LongArray, val owners: Map<String, Int>)

    private fun encode(rows: List<LedgerRecord>, due: (LedgerRecord) -> Long = { 0L },
                       day: (LedgerRecord) -> Long = { 0L }): Batch {
        require(rows.size <= MAX_ROWS) { "Книга слишком большая." }
        val owners = LinkedHashMap<String, Int>()
        val ids = HashMap<String, Long>()
        fun id(value: String) = ids.getOrPut(value) { ids.size.toLong() + 1 }
        val data = LongArray(rows.size * WIDTH)
        rows.forEachIndexed { index, row ->
            val start = index * WIDTH
            data[start] = id(row.entryId)
            data[start + 1] = owners.getOrPut(row.ownerId) { owners.size }.toLong()
            data[start + 2] = when (row.operation) {
                "debt" -> 1L
                "payment" -> 2L
                "reversal" -> 3L
                else -> throw IllegalArgumentException("Неизвестная операция.")
            }
            data[start + 3] = row.amountKopecks
            data[start + 4] = due(row)
            data[start + 5] = if (row.operation == "reversal") id(row.reversedId) else 0L
            data[start + 6] = day(row)
        }
        return Batch(data, owners)
    }

    private fun calculate(mode: Int, batch: Batch, a: Long, b: Long = 0, c: Long = 0, size: Int): LongArray {
        val output = LongArray(size)
        try {
            check(loaded)
            val status = NativeLedger.compute(mode, batch.values, batch.owners.size, a, b, c, output)
            check(status == 0) { "Нативный финансовый расчёт отклонён (код $status)." }
        } catch (error: LinkageError) {
            throw IllegalStateException("Нативный финансовый модуль недоступен.", error)
        }
        return output
    }

    private fun <T> verified(native: T, reference: T): T {
        check(native == reference) { "Результаты финансовых расчётов Rust и Kotlin не совпадают." }
        return native
    }

    private fun Batch.owner(client: String) = (owners[client] ?: -1).toLong()

    @JvmStatic fun balance(rows: List<LedgerRecord>, client: String): Long {
        val reference = LedgerProjection.balance(rows, client)
        if (!BuildConfig.RUST_PREVIEW) return reference
        val batch = encode(rows)
        val native = calculate(NativeLedger.PROJECT, batch, batch.owner(client), size = 2)
        return verified(native[0], reference)
    }

    @JvmStatic fun overdue(rows: List<LedgerRecord>, client: String, today: LocalDate): Long {
        val iso = today.toString()
        val reference = LedgerProjection.overdue(rows, client, iso)
        if (!BuildConfig.RUST_PREVIEW) return reference
        // Preserve the established ISO string comparison, including the empty-due case.
        val batch = encode(rows, due = { if (it.dueDate.isNotEmpty() && it.dueDate < iso) 1L else 0L })
        val native = calculate(NativeLedger.PROJECT, batch, batch.owner(client), size = 2)
        return verified(native[1], reference)
    }

    @JvmStatic fun historical(rows: List<DatedLedgerRecord>, client: String, selected: LocalDate?, today: LocalDate, zone: ZoneId): Long {
        val reference = DatedLedger.maximum(rows, client, selected, today, zone)
        if (!BuildConfig.RUST_PREVIEW || selected == null || selected.isAfter(today)) return reference
        // For today's guard the existing algorithm never needs historical timestamps.
        val batch = encode(rows, day = {
            if (selected == today || it.ownerId != client) 0L
            else DatedLedger.localDate((it as DatedLedgerRecord).recordedAt, zone).toEpochDay()
        })
        val native = calculate(NativeLedger.HISTORICAL, batch, batch.owner(client), selected.toEpochDay(), today.toEpochDay(), size = 1)
        return verified(native[0], reference)
    }

    @JvmStatic fun collections(rows: List<LedgerRecord>, today: LocalDate): Map<String, CollectionProjection.Status> {
        val reference = CollectionProjection.analyze(rows, today)
        if (!BuildConfig.RUST_PREVIEW) return reference
        val batch = encode(rows, due = {
            if (it.operation == "debt" && it.dueDate.isNotEmpty()) LocalDate.parse(it.dueDate).toEpochDay() else NativeLedger.NONE
        })
        val native = calculate(NativeLedger.COLLECTIONS, batch, today.toEpochDay(), size = batch.owners.size * 5)
        val result = batch.owners.mapValues { (_, index) ->
            val start = index * 5
            CollectionProjection.Status(native[start], native[start + 1], native[start + 2],
                native[start + 3].takeUnless { it == NativeLedger.NONE }?.let(LocalDate::ofEpochDay),
                Math.toIntExact(native[start + 4]))
        }
        return verified(result, reference)
    }

    @JvmStatic fun report(rows: List<DatedLedgerRecord>, from: LocalDate, to: LocalDate, zone: ZoneId): ReportProjection.Report {
        val reference = ReportProjection.build(rows, from, to, zone)
        if (!BuildConfig.RUST_PREVIEW) return reference
        val days = Math.toIntExact(ChronoUnit.DAYS.between(from, to)) + 1
        val batch = encode(rows, day = { DatedLedger.localDate((it as DatedLedgerRecord).recordedAt, zone).toEpochDay() })
        val native = calculate(NativeLedger.REPORT, batch, from.toEpochDay(), to.toEpochDay(), size = 8 + days * 5)
        fun totals(index: Int) = ReportProjection.Totals(native[index], native[index + 1], native[index + 2],
            native[index + 3], Math.toIntExact(native[index + 4]))
        val daily = linkedMapOf<LocalDate, ReportProjection.Totals>()
        repeat(days) { index ->
            val value = totals(8 + index * 5)
            if (value.count != 0) daily[from.plusDays(index.toLong())] = value
        }
        val result = ReportProjection.Report(native[0], totals(2), daily, Math.toIntExact(native[7]))
        verified(native[1], reference.closing)
        return verified(result, reference)
    }
}
