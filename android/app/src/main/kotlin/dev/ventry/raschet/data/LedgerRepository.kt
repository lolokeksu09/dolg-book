package dev.ventry.raschet.data

import android.content.Context
import dev.ventry.raschet.LedgerStore
import dev.ventry.raschet.FollowUpJournal
import java.util.Collections

/** A coherent read of one committed book; lists cannot be mutated by UI callers. */
class LedgerSnapshot @JvmOverloads constructor(clients: List<LedgerStore.Client>, entries: List<LedgerStore.Entry>, plans: List<FollowUpJournal.Record> = emptyList()) {
    val clients: List<LedgerStore.Client> = Collections.unmodifiableList(ArrayList(clients))
    val entries: List<LedgerStore.Entry> = Collections.unmodifiableList(ArrayList(entries))
    val plans: List<FollowUpJournal.Record> = Collections.unmodifiableList(ArrayList(plans))
}

/** Blocking data boundary. State holders call it on a worker dispatcher. */
interface LedgerRepository {
    fun snapshot(): LedgerSnapshot
    fun saveClient(command: ClientCommand)
    fun saveDebt(command: ClientCommand.Debt, context: Context): WriteReceipt
    fun savePayment(command: ClientCommand.Payment): WriteReceipt
    fun savePurchase(command: ClientCommand.ItemizedDebt, context: Context): WriteReceipt
    fun catalog(): List<CatalogRecord>
    fun saveCatalogProduct(record: CatalogRecord)
    fun correct(command: ClientCommand.Correction): WriteReceipt
    fun reverse(command: ClientCommand.Reversal): WriteReceipt
}

/** Borrows the existing activity-owned store; does not open or close another connection. */
class SqliteLedgerRepository(private val store: LedgerStore) : LedgerRepository {
    override fun snapshot(): LedgerSnapshot = store.readSnapshot()

    override fun saveClient(command: ClientCommand) {
        when (command) {
            is ClientCommand.Create -> store.addCheckedDraftClient(command.id, command.name, command.phone, command.allowDuplicate)
            is ClientCommand.Edit -> store.editClient(command.id, command.name, command.phone)
            else -> throw IllegalArgumentException("Неверная команда клиента.")
        }
    }
    override fun saveDebt(command: ClientCommand.Debt, context: Context): WriteReceipt = store.recordCheckedDebt(command, context)
    override fun savePayment(command: ClientCommand.Payment): WriteReceipt = store.recordCheckedPayment(command)

    override fun savePurchase(command: ClientCommand.ItemizedDebt, context: Context): WriteReceipt = store.recordCheckedPurchase(command, context)
    override fun catalog(): List<CatalogRecord> = store.catalogRecords()
    override fun saveCatalogProduct(record: CatalogRecord) = store.saveCatalogRecord(record)

    override fun correct(command: ClientCommand.Correction): WriteReceipt {
        require(command.expectedId.isNotBlank()) { "Не выбрана операция для исправления." }
        store.correctSelected(command.clientId, command.expectedId, command.amount, command.due, command.note, command.reason)
        return WriteReceipt(WriteDestination.DETAIL, "correction", command.clientId, null, 0, false)
    }

    override fun reverse(command: ClientCommand.Reversal): WriteReceipt {
        require(command.expectedId.isNotBlank()) { "Не выбрана операция для отмены." }
        store.reverseSelected(command.clientId, command.expectedId, command.reason)
        return WriteReceipt(WriteDestination.KEEP, "reversal", command.clientId, null, 0, false)
    }
}
