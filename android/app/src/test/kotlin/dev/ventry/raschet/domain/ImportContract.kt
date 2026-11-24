package dev.ventry.raschet.domain

fun verifyImportContract(): Int {
    fun fresh(version: Int = 4) = ImportLedgerValidation(version, listOf("a", "b"))
    fun ImportLedgerValidation.debt(id: String = "1", amount: Long = 100, client: String = "a") = accept(client, id, "debt", amount, "", "")
    fun ImportLedgerValidation.payment(id: String = "2", amount: Long = 40, client: String = "a") = accept(client, id, "payment", amount, "", "")
    fun ImportLedgerValidation.undo(id: String = "3", amount: Long = 40, target: String = "2", client: String = "a", note: String = "reason") = accept(client, id, "reversal", amount, target, note)
    var count = 0
    fun valid(block: () -> Unit) { block(); count++ }
    fun invalid(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is IllegalArgumentException && failure.message == message) { "expected $message, got $failure" }
        count++
    }
    val balance = "Книга содержит переплату или слишком большой остаток."
    val reversal = "Некорректная отмена."
    val link = "Неверная связь отмены."
    valid { fresh().apply { debt(); payment(); undo(); payment("4", 100) } }
    valid { fresh().apply { debt(); undo(amount = 100, target = "1"); debt("4", 50); payment("5", 50) } }
    valid { fresh(1).apply { debt(); payment() } }
    valid { fresh().apply { debt(client = "b"); debt("4"); payment(client = "b"); undo(client = "b") } }
    valid { fresh().apply { debt(amount = 100_000_000_000L); payment(amount = 100_000_000_000L) } }
    invalid(balance) { fresh().payment() }
    invalid(balance) { fresh().apply { debt(); payment(amount = 101) } }
    invalid(balance) { fresh().apply { debt(amount = 100_000_000_000L); debt("4", 1) } }
    invalid(reversal) { fresh(1).apply { debt(); undo(amount = 100, target = "1") } }
    invalid(reversal) { fresh().undo() }
    invalid(reversal) { fresh().apply { debt(); payment(); undo(note = " \t") } }
    invalid(link) { fresh().apply { debt(); payment(); undo(amount = 100, target = "1") } }
    invalid(link) { fresh().apply { debt(); payment(); undo(amount = 39) } }
    invalid(link) { fresh().apply { debt(); payment(); undo(); undo(id = "4") } }
    invalid("Неверная ссылка.") { fresh().accept("a", "1", "debt", 100, "old", "") }
    invalid("Некорректная запись.") { fresh().debt(client = "missing") }
    invalid("Некорректная запись.") { fresh().debt(amount = 0) }
    valid { fresh().apply { debt(); payment(); runCatching { undo(amount = 39) }; undo(); payment("4", 100) } }
    check(count == 18)
    return count
}

fun main() = println("Import contract: ${verifyImportContract()} PASS")
