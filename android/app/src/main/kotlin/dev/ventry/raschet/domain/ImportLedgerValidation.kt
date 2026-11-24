package dev.ventry.raschet.domain

/** Validates the append-only financial history of a parsed backup before SQLite replacement. */
class ImportLedgerValidation(private val version: Int, clientIds: Collection<String>) {
    private data class Active(val id: String, val kind: String, val amount: Long)
    private class ClientState {
        var balance = 0L
        val active = ArrayList<Active>()
    }
    private val states = clientIds.associateWith { ClientState() }

    fun accept(clientId: String, id: String, kind: String, amount: Long, reverses: String, note: String) {
        val state = requireNotNull(states[clientId]) { "Некорректная запись." }
        require(amount in 1..100_000_000_000L && kind in setOf("debt", "payment", "reversal")) { "Некорректная запись." }
        val previous = state.active.lastOrNull()
        val delta = if (kind == "reversal") {
            require(version != 1 && previous != null && note.trim { it <= ' ' }.isNotEmpty()) { "Некорректная отмена." }
            require(previous.id == reverses && previous.amount == amount) { "Неверная связь отмены." }
            if (previous.kind == "debt") -amount else amount
        } else {
            require(reverses.isEmpty()) { "Неверная ссылка." }
            if (kind == "debt") amount else -amount
        }
        val updated = Math.addExact(state.balance, delta)
        require(updated in 0..100_000_000_000L) { "Книга содержит переплату или слишком большой остаток." }
        // Commit the in-memory state only after all validation passes.
        if (kind == "reversal") state.active.removeAt(state.active.lastIndex)
        else state.active.add(Active(id, kind, amount))
        state.balance = updated
    }
}
