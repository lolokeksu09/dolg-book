package dev.ventry.raschet

import android.app.Application
import android.util.Log
import dev.ventry.raschet.data.ClientCommand
import dev.ventry.raschet.data.SqliteLedgerRepository

/** Runs on IO with an independent, application-context database helper. */
object ClientWriteService {
    fun execute(application: Application, command: ClientCommand): Boolean {
        LedgerStore(application).use { store ->
            SqliteLedgerRepository(store).saveClient(command)
        }
        // A committed write must not be reported as failed if auxiliary work fails.
        if (command is ClientCommand.Create) {
            try {
                val drafts = QuickDebtDrafts(application)
                if (drafts.get()?.id == command.id) drafts.clear()
            } catch (_: Exception) {
                Log.w("Raschet", "Could not clear saved client draft")
            }
        }
        try {
            RecoveryScheduler.enqueue(application)
        } catch (_: Exception) {
            Log.w("Raschet", "Could not schedule recovery after client write")
        }
        return command is ClientCommand.Create
    }
}
