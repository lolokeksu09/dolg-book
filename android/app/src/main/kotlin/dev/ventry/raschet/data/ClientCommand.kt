package dev.ventry.raschet.data

import java.time.LocalDate

/** Immutable commands captured from the form before dispatching to IO. */
sealed interface ClientCommand {
    data class Create(val id: String, val name: String, val phone: String, val allowDuplicate: Boolean) : ClientCommand
    data class Edit(val id: String, val name: String, val phone: String) : ClientCommand
    data class Debt(
        val id: String, val existingId: String?, val name: String, val phone: String,
        val amount: Long, val day: LocalDate, val due: String, val note: String,
        val allowDuplicate: Boolean, val approval: String, val quick: Boolean,
        val destination: WriteDestination
    ) : ClientCommand
    data class Correction(
        val clientId: String, val expectedId: String, val amount: Long,
        val due: String, val note: String, val reason: String
    ) : ClientCommand
    data class Reversal(val clientId: String, val expectedId: String, val reason: String) : ClientCommand
    data class Payment(
        val id: String, val clientId: String, val amount: Long, val day: LocalDate,
        val note: String, val destination: WriteDestination
    ) : ClientCommand
}

enum class WriteDestination { KEEP, CLIENTS, BOOK, COLLECTIONS, DETAIL }

data class WriteReceipt(
    val destination: WriteDestination,
    val kind: String,
    val clientId: String?,
    val day: LocalDate?,
    val rank: Int,
    val settled: Boolean
)
