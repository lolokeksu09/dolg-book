package dev.ventry.raschet

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.ventry.raschet.data.CatalogRecord
import dev.ventry.raschet.data.ClientCommand
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.data.WriteReceipt
import dev.ventry.raschet.domain.DatedLedgerRecord
import dev.ventry.raschet.domain.LedgerArithmetic
import dev.ventry.raschet.bridge.NativeLedgerEngine
import dev.ventry.raschet.domain.LedgerProjection
import org.json.JSONException
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Device-only ledger. This facade owns the SQLite connection and the synchronization boundary. */
class LedgerStore(
    context: Context,
    databaseName: String,
    private val legacyDirectory: ClientDirectory?
) : SQLiteOpenHelper(context, databaseName, null, LedgerSchema.VERSION) {
    constructor(context: Context) : this(context, "raschet-ledger.db", ClientDirectory(context))
    // Isolated database names are used by instrumentation; production never changes its database name.
    constructor(context: Context, databaseName: String) : this(context, databaseName, null)

    class Client(@JvmField val id: String, @JvmField val name: String, @JvmField val phone: String)

    class Entry @JvmOverloads constructor(
        @JvmField val id: String,
        @JvmField val clientId: String,
        @JvmField val type: String,
        @JvmField val amount: Long,
        @JvmField val date: String,
        @JvmField val due: String,
        @JvmField val note: String,
        @JvmField val reverses: String,
        @JvmField val purchase: Purchase? = null
    ) : DatedLedgerRecord {
        override val entryId: String get() = id
        override val ownerId: String get() = clientId
        override val operation: String get() = type
        override val amountKopecks: Long get() = amount
        override val dueDate: String get() = due
        override val reversedId: String get() = reverses
        override val recordedAt: String get() = date
    }

    class Balance {
        @JvmField var amount: Long = 0
        @JvmField var overdue: Long = 0
    }

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) = LedgerSchema.create(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        LedgerSchema.upgrade(db, oldVersion, newVersion, legacyDirectory)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    @Synchronized fun followUpHistory(): List<FollowUpJournal.Record> = FollowUpJournal.read(readableDatabase)
    @Synchronized fun currentPlan(client: String): FollowUpJournal.Record? = FollowUpJournal.current(readableDatabase, client)
    @Synchronized fun saveFollowUp(command: ClientCommand.FollowUp) {
        LedgerWrites.saveFollowUp(writableDatabase, command)
    }

    @Synchronized fun products(): MutableList<Product> = LedgerCatalogSql.products(readableDatabase)
    @Synchronized fun saveProduct(product: Product) = LedgerCatalogSql.save(writableDatabase, product)
    @Synchronized fun catalogRecords(): List<CatalogRecord> = LedgerCatalogSql.records(readableDatabase)
    @Synchronized fun saveCatalogRecord(record: CatalogRecord) {
        LedgerCatalogSql.saveRecord(writableDatabase, record)
    }

    @Synchronized fun clients(): MutableList<Client> = LedgerSql.clients(readableDatabase)
    @Synchronized fun entries(): MutableList<Entry> = LedgerSql.entries(readableDatabase, null)

    /** Indexed history, including reversals, in insertion order. */
    @Synchronized fun entriesForClient(clientId: String?): MutableList<Entry> {
        requireNotNull(clientId) { "Клиент не выбран." }
        return LedgerSql.entries(readableDatabase, clientId)
    }

    @Synchronized fun readSnapshot(): LedgerSnapshot = LedgerSql.snapshot(writableDatabase)

    @Synchronized fun addClient(name: String?, phone: String?) {
        LedgerWrites.insertClient(writableDatabase, name, phone, UUID.randomUUID().toString())
    }

    @Synchronized fun addDraftClient(id: String, name: String?, phone: String?) {
        LedgerWrites.insertClient(writableDatabase, name, phone, id)
    }

    @Synchronized fun addCheckedDraftClient(id: String, name: String?, phone: String?, allowDuplicate: Boolean) {
        LedgerWrites.checkedClient(writableDatabase, id, name, phone, allowDuplicate)
    }

    @Synchronized fun editClient(id: String?, name: String?, phone: String?) {
        LedgerWrites.editClient(writableDatabase, id, name, phone)
    }

    @Synchronized fun hasEntryId(id: String?): Boolean = LedgerSql.hasEntry(readableDatabase, id)
    @Synchronized fun hasClientId(id: String?): Boolean = LedgerSql.hasClient(readableDatabase, id)

    @Synchronized fun recordDatedDebt(existingId: String?, name: String?, phone: String?, amount: Long,
                                      day: LocalDate?, due: String?, note: String?): String =
        recordDatedDebt(existingId, name, phone, amount, day, due, note, UUID.randomUUID().toString())

    @Synchronized fun recordDatedDebt(existingId: String?, name: String?, phone: String?, amount: Long,
                                      day: LocalDate?, due: String?, note: String?, entryId: String): String =
        LedgerWrites.recordDatedDebt(writableDatabase, appContext, existingId, name, phone, amount, day, due, note, entryId)

    @Synchronized fun recordCheckedDebt(command: ClientCommand.Debt, context: Context): WriteReceipt =
        LedgerWrites.recordCheckedDebt(writableDatabase, appContext, context, command)

    @Synchronized fun recordCheckedPayment(command: ClientCommand.Payment): WriteReceipt =
        LedgerWrites.recordCheckedPayment(writableDatabase, appContext, command)

    @Synchronized fun recordCheckedPurchase(command: ClientCommand.ItemizedDebt, context: Context): WriteReceipt =
        LedgerWrites.recordCheckedPurchase(writableDatabase, appContext, context, command)

    @Synchronized fun addEntry(clientId: String?, type: String?, amount: Long, due: String?, note: String?) {
        LedgerWrites.addEntry(writableDatabase, appContext, clientId, type, amount, due, note, null)
    }

    @Synchronized fun addPurchase(clientId: String?, purchase: Purchase?, due: String?, note: String?) {
        requireNotNull(purchase) { "Добавьте товары." }
        LedgerWrites.addEntry(writableDatabase, appContext, clientId, "debt", purchase.total, due, note, purchase)
    }

    @Synchronized fun addDraftPayment(clientId: String?, amount: Long, note: String?, entryId: String) {
        addDraftPayment(clientId, amount, note, entryId, LocalDate.now())
    }

    @Synchronized fun addDraftPayment(clientId: String?, amount: Long, note: String?, entryId: String, day: LocalDate?) {
        LedgerWrites.addDraftPayment(writableDatabase, appContext, clientId, amount, note, entryId, day)
    }

    @Synchronized fun lastActive(clientId: String?): Entry? {
        requireNotNull(clientId) { "Клиент не выбран." }
        return LedgerCorrections.lastActive(readableDatabase, clientId)
    }

    @Synchronized fun reverseLast(clientId: String?, reason: String?) = reverseSelected(clientId, null, reason)
    @Synchronized fun reverseSelected(clientId: String?, expectedId: String?, reason: String?) =
        LedgerCorrections.reverse(writableDatabase, clientId, expectedId, reason)

    @Synchronized fun correctLast(clientId: String?, amount: Long, due: String?, note: String?, reason: String?) =
        correctSelected(clientId, null, amount, due, note, reason)

    @Synchronized fun correctSelected(clientId: String?, expectedId: String?, amount: Long, due: String?, note: String?, reason: String?) =
        LedgerCorrections.correct(writableDatabase, clientId, expectedId, amount, due, note, reason)

    /** Compatibility entry point; streaming remains the single serializer. */
    @Synchronized @Throws(JSONException::class)
    fun exportJson(): String {
        val output = StringWriter()
        try { writeJson(output) } catch (impossible: IOException) { throw IllegalStateException(impossible) }
        return output.toString()
    }

    @Synchronized @Throws(JSONException::class, IOException::class)
    fun writeJson(output: Writer) = LedgerJson.write(readableDatabase, output)

    @Synchronized @Throws(JSONException::class)
    fun importJson(source: String): IntArray =
        try { importJson(StringReader(source)) }
        catch (invalid: IOException) { throw IllegalArgumentException("Некорректный JSON.", invalid) }

    @Synchronized @Throws(JSONException::class, IOException::class)
    fun importJson(source: Reader): IntArray = LedgerJson.restore(writableDatabase, source)

    companion object {
        const val MAX_AMOUNT: Long = LedgerArithmetic.MAX_AMOUNT
        @JvmStatic fun parseAmount(value: String?): Long = LedgerInput.parseAmount(value)
        @JvmStatic fun journalRank(db: SQLiteDatabase, type: String?, day: LocalDate?, zone: ZoneId): Int =
            LedgerSql.journalRank(db, type, day, zone)
        @JvmStatic fun correctedBalance(available: Long, last: Entry?, amount: Long): Long =
            LedgerCorrections.correctedBalance(available, last, amount)
        @JvmStatic fun canceled(list: List<Entry>): Set<String> = LedgerProjection.canceled(list)
        @JvmStatic fun balance(list: List<Entry>, clientId: String): Long = NativeLedgerEngine.balance(list, clientId)
        @JvmStatic fun overdue(list: List<Entry>, clientId: String, today: LocalDate): Long =
            NativeLedgerEngine.overdue(list, clientId, today)
    }
}
