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
                is ClientCommand.Create -> {
                    repository.saveClient(command)
                    WriteReceipt(WriteDestination.CLIENTS, "client", command.id, null, 0, false)
                }
                is ClientCommand.Edit -> {
                    repository.saveClient(command)
                    WriteReceipt(WriteDestination.KEEP, "client", command.id, null, 0, false)
                }
                is ClientCommand.Debt -> repository.saveDebt(command, application)
                is ClientCommand.Payment -> repository.savePayment(command)
            }
        }
        // Post-commit housekeeping must never turn a committed operation into a retryable failure.
        try {
            when (command) {
                is ClientCommand.Create -> clearQuick(application, command.id)
                is ClientCommand.Edit -> Unit
                is ClientCommand.Debt -> {
                    if (command.quick) clearQuick(application, command.id)
                    else clearOperation(application, checkNotNull(command.existingId), "debt", command.id)
                }
                is ClientCommand.Payment -> {
                    clearOperation(application, command.clientId, "payment", command.id)
                    if (receipt.settled) {
                        val directory = ClientDirectory(application)
                        val plan = directory.followUp(command.clientId)
                        if (plan != null && plan.amount == 0L) directory.setFollowUp(command.clientId, null, "")
                    }
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
