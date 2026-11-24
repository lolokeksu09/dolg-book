package dev.ventry.raschet.data

import dev.ventry.raschet.LedgerStore
import java.util.Collections

/** A coherent read of one committed book; lists cannot be mutated by UI callers. */
class LedgerSnapshot(clients: List<LedgerStore.Client>, entries: List<LedgerStore.Entry>) {
    val clients: List<LedgerStore.Client> = Collections.unmodifiableList(ArrayList(clients))
    val entries: List<LedgerStore.Entry> = Collections.unmodifiableList(ArrayList(entries))
}

/** Blocking data boundary. State holders call it on a worker dispatcher. */
interface LedgerRepository {
    fun snapshot(): LedgerSnapshot
    fun saveClient(command: ClientCommand)
}

/** Borrows the existing activity-owned store; does not open or close another connection. */
class SqliteLedgerRepository(private val store: LedgerStore) : LedgerRepository {
    override fun snapshot(): LedgerSnapshot = store.readSnapshot()

    override fun saveClient(command: ClientCommand) {
        when (command) {
            is ClientCommand.Create -> store.addCheckedDraftClient(command.id, command.name, command.phone, command.allowDuplicate)
            is ClientCommand.Edit -> store.editClient(command.id, command.name, command.phone)
        }
    }
}
