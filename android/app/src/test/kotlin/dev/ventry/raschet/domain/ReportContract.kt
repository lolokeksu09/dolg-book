package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId

private data class ReportRow(
    override val entryId: String, override val ownerId: String, override val operation: String,
    override val amountKopecks: Long, override val recordedAt: String,
    override val reversedId: String = "", override val dueDate: String = ""
) : DatedLedgerRecord

fun verifyReports(): Int {
    val from = LocalDate.parse("2026-11-21")
    val to = LocalDate.parse("2026-11-24")
    val zone = ZoneId.of("Europe/Moscow")
    val rows = listOf(
        ReportRow("1", "a", "debt", 10000, "2026-11-20T09:00:00Z"),
        ReportRow("2", "a", "payment", 4000, "2026-11-20T22:30:00Z"),
        ReportRow("3", "b", "debt", 6000, "2026-11-22T09:00:00Z"),
        ReportRow("4", "b", "reversal", 6000, "2026-11-23T09:00:00Z", "3"),
        ReportRow("5", "a", "reversal", 4000, "2026-11-24T09:00:00Z", "2"),
        ReportRow("6", "a", "payment", 1000, "2026-11-24T10:00:00Z"),
        ReportRow("7", "c", "debt", 5000, "2026-11-25T09:00:00Z")
    )
    fun report(input: List<DatedLedgerRecord> = rows, start: LocalDate = from, end: LocalDate = to) = ReportProjection.build(input, start, end, zone)
    val result = report()
    check(result.opening == 10000L && result.closing == 9000L && result.activeCustomers == 2)
    check(result.totals == ReportProjection.Totals(6000, 5000, 6000, 4000, 5))
    check(result.daily.keys.toList() == (0L..3L).map(from::plusDays))
    check(result.daily[to] == ReportProjection.Totals(0, 1000, 0, 4000, 2))
    check(report(emptyList()) == ReportProjection.Report(0, ReportProjection.Totals(0, 0, 0, 0, 0), emptyMap(), 0))
    check(report(start = to, end = to).opening == 6000L && report(start = to, end = to).closing == 9000L)
    check(report(start = from, end = from.plusDays(365)).closing == 14000L)
    check(report(rows + ReportRow("8", "c", "debt", 1, "2026-11-21T10:00:00+03:00")).closing == 9001L)
    check(report(rows + ReportRow("8", "a", "debt", 1, "2026-11-20T11:00:00Z")).opening == 10001L)
    val bad = listOf(
        ReportRow("8", "b", "reversal", 10000, "2026-11-24T12:00:00Z", "1") to "Нарушена связь отмены.",
        ReportRow("8", "a", "reversal", 1, "2026-11-24T12:00:00Z", "1") to "Нарушена связь отмены.",
        ReportRow("8", "a", "reversal", 1, "2026-11-24T12:00:00Z", "missing") to "Нарушена связь отмены.",
        ReportRow("8", "a", "reversal", 4000, "2026-11-24T12:00:00Z", "5") to "Недопустимый тип отмены.",
        ReportRow("8", "a", "other", 1, "2026-11-24T12:00:00Z") to "Неизвестная операция."
    )
    for ((row, message) in bad) {
        val failure = runCatching { report(rows + row) }.exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message == message)
    }
    for ((start, end) in listOf(to to from, from to from.plusDays(366))) {
        check(runCatching { report(start = start, end = end) }.exceptionOrNull() is IllegalArgumentException)
    }
    return 16
}

fun main() { println("Report contract: ${verifyReports()} checks PASS") }
