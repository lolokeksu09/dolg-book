package dev.ventry.raschet.domain

import java.io.File
import kotlin.random.Random

private data class Row(
    override val entryId: String, override val ownerId: String, override val operation: String,
    override val amountKopecks: Long, override val dueDate: String, override val reversedId: String
) : LedgerRecord

/** Frozen pre-migration LedgerStore algorithm: same loops and string/date contract. */
private fun legacy(rows: List<LedgerRecord>, client: String, today: String): Pair<Long, Long> {
    val removed = HashSet<String>()
    for (e in rows) if (e.operation == "reversal") removed.add(e.reversedId)
    var balance = 0L
    for (e in rows) if (e.ownerId == client && e.operation != "reversal" && e.entryId !in removed)
        balance += if (e.operation == "debt") e.amountKopecks else -e.amountKopecks
    var paid = 0L
    for (e in rows) if (e.ownerId == client && e.operation == "payment" && e.entryId !in removed) paid += e.amountKopecks
    var overdue = 0L
    for (e in rows) {
        if (e.ownerId != client || e.operation != "debt" || e.entryId in removed) continue
        val applied = minOf(paid, e.amountKopecks)
        paid -= applied
        if (e.dueDate.isNotEmpty() && e.dueDate < today) overdue += e.amountKopecks - applied
    }
    return balance to overdue
}

fun verifyProjectionContract(text: String): Int {
    var header: List<String>? = null
    val rows = mutableListOf<Row>()
    var count = 0
    fun verify() {
        val h = header ?: return
        val expected = h[4].toLong() to h[5].toLong()
        check(legacy(rows, h[2], h[3]) == expected) { "Legacy fixture: ${h[1]}" }
        val actual = LedgerProjection.balance(rows, h[2]) to LedgerProjection.overdue(rows, h[2], h[3])
        check(actual == expected) { "${h[1]}: $actual != $expected" }
        count++
    }
    for (line in text.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val fields = line.split('|')
        when (fields.first()) {
            "case" -> { verify(); check(fields.size == 6); header = fields; rows.clear() }
            "row" -> { check(fields.size == 7 && header != null); rows.add(Row(fields[1], fields[2], fields[3], fields[4].toLong(), fields[5], fields[6])) }
            else -> error("Unexpected fixture: $line")
        }
    }
    verify()
    check(count == 10)
    return count
}

/** Valid append-only histories; reversals always target the last active operation. */
fun verifyProjectionDifferential(): Int {
    val random = Random(2901)
    var checks = 0
    repeat(250) {
        val rows = mutableListOf<Row>()
        val active = mutableMapOf<String, MutableList<Row>>()
        val balances = mutableMapOf<String, Long>()
        repeat(80) { index ->
            val client = if (random.nextBoolean()) "a" else "b"
            val history = active.getOrPut(client) { mutableListOf() }
            val balance = balances[client] ?: 0L
            val id = index.toString()
            val last = history.lastOrNull()
            val canReverse = last != null && (last.operation != "debt" || balance >= last.amountKopecks)
            val row = when {
                canReverse && random.nextInt(5) == 0 -> {
                    val original = history.removeAt(history.lastIndex)
                    balances[client] = balance + if (original.operation == "debt") -original.amountKopecks else original.amountKopecks
                    Row(id, client, "reversal", original.amountKopecks, "", original.entryId)
                }
                balance > 0 && random.nextBoolean() -> {
                    val amount = random.nextLong(1, balance + 1)
                    balances[client] = balance - amount
                    Row(id, client, "payment", amount, "", "").also(history::add)
                }
                else -> {
                    val amount = random.nextLong(1, 10001)
                    balances[client] = balance + amount
                    Row(id, client, "debt", amount, listOf("", "2026-11-20", "2026-11-24", "2026-12-01").random(random), "").also(history::add)
                }
            }
            rows.add(row)
        }
        for (client in listOf("a", "b")) {
            val expected = legacy(rows, client, "2026-11-24")
            val actual = LedgerProjection.balance(rows, client) to LedgerProjection.overdue(rows, client, "2026-11-24")
            check(expected == actual && actual.first == balances[client]) { "Differential failure: $client" }
            check(actual.second in 0..actual.first)
            checks++
        }
    }
    return checks
}

fun main(args: Array<String>) {
    val fixed = verifyProjectionContract(File(args.single()).readText())
    val differential = verifyProjectionDifferential()
    println("Projection contract: $fixed fixed cases, $differential differential checks PASS")
}
