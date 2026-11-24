package dev.ventry.raschet.domain

import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private data class DatedRow(
    override val entryId: String, override val ownerId: String, override val operation: String,
    override val amountKopecks: Long, override val recordedAt: String, override val reversedId: String,
    override val dueDate: String = ""
) : DatedLedgerRecord

fun verifyDatedContract(text: String): Int {
    var header: List<String>? = null
    val rows = mutableListOf<DatedRow>()
    var count = 0
    fun verify() {
        val h = header ?: return
        val actual = try {
            DatedLedger.maximum(rows, h[2], h[3].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), LocalDate.parse(h[4]), ZoneId.of(h[5])).toString()
        } catch (e: IllegalArgumentException) {
            if (e.message != "Нарушена связь отмены.") throw e
            "error"
        }
        check(actual == h[6]) { "${h[1]}: $actual != ${h[6]}" }
        count++
    }
    for (line in text.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val f = line.split('|')
        when (f[0]) {
            "case" -> { verify(); check(f.size == 7); header = f; rows.clear() }
            "row" -> { check(f.size == 7 && header != null); rows += DatedRow(f[1], f[2], f[3], f[4].toLong(), f[5], f[6]) }
            else -> error(line)
        }
    }
    verify()
    check(count == 14)
    return count
}

fun verifyCollections(): Int {
    val today = LocalDate.parse("2026-11-24")
    fun row(id: String, client: String, kind: String, amount: Long, due: String = "", reverses: String = "") =
        DatedRow(id, client, kind, amount, "2026-11-24T00:00:00Z", reverses, due)
    val rows = listOf(
        row("1", "a", "debt", 1000, "2026-12-02"),
        row("2", "a", "debt", 2000, "2026-11-20"),
        row("3", "a", "debt", 3000, "2026-11-24"),
        row("4", "a", "debt", 4000, "2026-12-01"),
        row("5", "a", "debt", 5000, "2026-12-02"),
        row("6", "a", "payment", 1500),
        row("7", "b", "debt", 1000, "2026-11-23"),
        row("8", "b", "reversal", 1000, reverses = "7"),
        row("9", "a", "payment", 2000),
        row("10", "a", "reversal", 2000, reverses = "9"),
        row("11", "c", "debt", 100, ""),
        row("12", "c", "payment", 100)
    )
    val result = CollectionProjection.analyze(rows, today)
    check(result["a"] == CollectionProjection.Status(13500, 1500, 7000, today, 9))
    check(result["b"] == CollectionProjection.Status(0, 0, 0, null, 7))
    check(result["c"] == CollectionProjection.Status(0, 0, 0, null, 11))
    check(result.getOrDefault("missing", CollectionProjection.EMPTY) == CollectionProjection.EMPTY)
    check(CollectionProjection.analyze(emptyList(), today).isEmpty())
    for ((client, status) in result) {
        check(status.balance == LedgerProjection.balance(rows, client))
        check(status.overdue == LedgerProjection.overdue(rows, client, today.toString()))
    }
    return 5
}

fun main(args: Array<String>) {
    println("Dated contract: ${verifyDatedContract(File(args.single()).readText())} PASS")
    println("Collection contract: ${verifyCollections()} scenarios PASS")
}
