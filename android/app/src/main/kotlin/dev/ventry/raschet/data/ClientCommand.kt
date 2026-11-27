package dev.ventry.raschet.data

import java.time.LocalDate

/** Immutable commands captured from the form before dispatching to IO. */
sealed interface ClientCommand {
    /** File commands hold only immutable SAF URI strings and user-approved parameters. */
    sealed interface FileTask : ClientCommand {
        data class Export(val uri: String, val csv: Boolean, val preferencesName: String) : FileTask
        data class Report(val uri: String, val from: LocalDate, val to: LocalDate) : FileTask
        data class Analytics(val uri: String, val spec: dev.ventry.raschet.domain.AnalyticsExport.Spec) : FileTask
        data class Pdf(val uri: String, val clientId: String, val shop: String) : FileTask
        data class Import(val uri: String, val preferencesName: String) : FileTask
        data class RestorePoint(val preferencesName: String) : FileTask
        data object CreatePoint : FileTask
    }
    data class Create(val id: String, val name: String, val phone: String, val allowDuplicate: Boolean) : ClientCommand
    data class Edit(val id: String, val name: String, val phone: String) : ClientCommand
    data class Catalog(val record: CatalogRecord) : ClientCommand
    data class Debt(
        val id: String, val existingId: String?, val name: String, val phone: String,
        val amount: Long, val day: LocalDate, val due: String, val note: String,
        val allowDuplicate: Boolean, val approval: String, val quick: Boolean,
        val destination: WriteDestination
    ) : ClientCommand
    data class ItemizedDebt(
        val id: String, val clientId: String, val purchaseJson: String,
        val due: String, val note: String, val approval: String
    ) : ClientCommand
    data class Correction(
        val clientId: String, val expectedId: String, val amount: Long,
        val due: String, val note: String, val reason: String
    ) : ClientCommand
    data class Reversal(val clientId: String, val expectedId: String, val reason: String) : ClientCommand
    data class FollowUp(val clientId: String, val expectedId: String?, val day: LocalDate?, val note: String, val amount: Long, val restart: Boolean) : ClientCommand
    data class Payment(
        val id: String, val clientId: String, val amount: Long, val day: LocalDate,
        val note: String, val destination: WriteDestination
    ) : ClientCommand
}

enum class WriteDestination { KEEP, CLIENTS, BOOK, COLLECTIONS, DETAIL }

data class WriteReceipt @JvmOverloads constructor(
    val destination: WriteDestination,
    val kind: String,
    val clientId: String?,
    val day: LocalDate?,
    val rank: Int,
    val settled: Boolean,
    val message: String? = null
)
