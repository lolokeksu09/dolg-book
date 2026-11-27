package dev.ventry.raschet

import android.app.Application
import android.util.Log
import dev.ventry.raschet.data.ClientCommand
import dev.ventry.raschet.data.SqliteLedgerRepository
import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.data.WriteReceipt

/** Runs on IO with an independent, application-context database helper. */
object ClientWriteService {
    fun execute(application: Application, command: ClientCommand): WriteReceipt {
        val receipt = LedgerStore(application).use { store ->
            val repository = SqliteLedgerRepository(store)
            when (command) {
                is ClientCommand.FileTask -> return BookFileService.execute(application, command, store)
                is ClientCommand.Create -> {
                    repository.saveClient(command)
                    WriteReceipt(WriteDestination.CLIENTS, "client", command.id, null, 0, false)
                }
                is ClientCommand.Edit -> {
                    repository.saveClient(command)
                    WriteReceipt(WriteDestination.KEEP, "client", command.id, null, 0, false)
                }
                is ClientCommand.Catalog -> {
                    repository.saveCatalogProduct(command.record)
                    WriteReceipt(WriteDestination.KEEP, "catalog", null, null, 0, false)
                }
                is ClientCommand.Debt -> repository.saveDebt(command, application)
                is ClientCommand.Payment -> repository.savePayment(command)
                is ClientCommand.ItemizedDebt -> repository.savePurchase(command, application)
                is ClientCommand.Correction -> repository.correct(command)
                is ClientCommand.Reversal -> repository.reverse(command)
                is ClientCommand.FollowUp -> {
                    store.saveFollowUp(command)
                    WriteReceipt(WriteDestination.KEEP, "follow_up", command.clientId, null, 0, false)
                }
            }
        }
        // Post-commit housekeeping must never turn a committed operation into a retryable failure.
        try {
            when (command) {
                is ClientCommand.Create -> clearQuick(application, command.id)
                is ClientCommand.FileTask, is ClientCommand.Edit, is ClientCommand.Catalog, is ClientCommand.Correction, is ClientCommand.Reversal, is ClientCommand.FollowUp -> Unit
                is ClientCommand.ItemizedDebt -> PurchaseDrafts(application).clearCommitted(command.clientId, command.id)
                is ClientCommand.Debt -> {
                    if (command.quick) clearQuick(application, command.id)
                    else clearOperation(application, checkNotNull(command.existingId), "debt", command.id)
                }
                is ClientCommand.Payment -> {
                    clearOperation(application, command.clientId, "payment", command.id)
                }
            }
        } catch (_: Exception) {
            Log.w("Raschet", "Could not clear committed draft or completed follow-up")
        }
        try {
            RecoveryScheduler.enqueue(application)
        } catch (_: Exception) {
            Log.w("Raschet", "Could not schedule recovery after committed write")
        }
        return receipt
    }

    private fun clearQuick(application: Application, id: String) {
        val drafts = QuickDebtDrafts(application)
        if (drafts.get()?.id == id) drafts.clear()
    }

    private fun clearOperation(application: Application, clientId: String, kind: String, id: String) {
        val drafts = LedgerDrafts(application)
        if (drafts.get(clientId, kind)?.id == id) drafts.clear(clientId, kind)
    }
}
