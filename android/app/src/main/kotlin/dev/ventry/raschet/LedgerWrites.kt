package dev.ventry.raschet

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import dev.ventry.raschet.data.ClientCommand
import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.data.WriteReceipt
import dev.ventry.raschet.domain.DebtApproval
import dev.ventry.raschet.license.LicenseManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Financial and customer writes borrow the store's connection under its synchronized API. */
internal object LedgerWrites {
    private const val MAX_CLIENTS = 10_000
    private const val MAX_ENTRIES = 100_000

    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun entries(db: SQLiteDatabase, clientId: String?): List<LedgerStore.Entry> {
        requireNotNull(clientId) { "Клиент не выбран." }
        return LedgerSql.entries(db, clientId)
    }

    private fun uniqueDraft(db: SQLiteDatabase, id: String, message: String, checkClient: Boolean) {
        require(!LedgerSql.hasEntry(db, id) && (!checkClient || !LedgerSql.hasClient(db, id))) { message }
    }

    private fun rejectDuplicate(db: SQLiteDatabase, name: String, phone: String) {
        val n = LedgerInput.trim(name)
        val d = phone.replace(Regex("[^0-9]"), "")
        require(LedgerSql.clients(db).none { LedgerInput.trim(it.name).equals(n, ignoreCase = true) || ClientSearch.sameNumber(it.phone, d) }) {
            "Выберите существующего клиента или подтвердите создание отдельного."
        }
    }

    @JvmStatic fun insertClient(db: SQLiteDatabase, name: String?, phone: String?, id: String) {
        LedgerInput.checkClient(name, phone)
        require(DatabaseUtils.queryNumEntries(db, "clients") < MAX_CLIENTS) { "Достигнут лимит клиентов." }
        val row = ContentValues().apply {
            put("id", id); put("name", LedgerInput.trim(checkNotNull(name))); put("phone", LedgerInput.trim(checkNotNull(phone)))
        }
        db.insertOrThrow("clients", null, row)
    }

    @JvmStatic fun checkedClient(db: SQLiteDatabase, id: String, name: String?, phone: String?, allowDuplicate: Boolean) {
        UUID.fromString(id)
        LedgerInput.checkClient(name, phone)
        transaction(db) {
            uniqueDraft(db, id, "Этот черновик уже сохранён. Откройте книгу.", true)
            if (!allowDuplicate) rejectDuplicate(db, checkNotNull(name), checkNotNull(phone))
            insertClient(db, name, phone, id)
        }
    }

    @JvmStatic fun editClient(db: SQLiteDatabase, id: String?, name: String?, phone: String?) {
        LedgerInput.checkClient(name, phone)
        val row = ContentValues().apply {
            put("name", LedgerInput.trim(checkNotNull(name))); put("phone", LedgerInput.trim(checkNotNull(phone)))
        }
        require(db.update("clients", row, "id=?", arrayOf(id)) == 1) { "Клиент больше не найден." }
    }

    private fun checkApproval(db: SQLiteDatabase, context: Context, client: String, amount: Long, approval: String) {
        val directory = ClientDirectory(context)
        val balance = LedgerStore.balance(entries(db, client), client)
        val limit = directory.debtLimit(client)
        require(!directory.archived(client, balance)) { "Сначала верните клиента из архива." }
        val confirmed = DebtApproval.matches(approval, client, balance, limit, amount)
        require(confirmed || approval.isEmpty() || !DebtLimits.requiresConfirmation(limit, balance, amount)) {
            "Остаток или лимит изменился. Откройте форму заново и подтвердите превышение."
        }
        DebtLimits.requireConsent(limit, balance, amount, confirmed)
    }

    private fun insertEntry(db: SQLiteDatabase, app: Context, clientId: String?, type: String?, amount: Long,
                            due: String?, note: String?, purchase: Purchase?, timestamp: String, entryId: String) {
        check(db.inTransaction()) { "Financial write requires a transaction" }
        require(type == "debt" || type == "payment") { "Неверная операция." }
        require(amount > 0 && amount <= LedgerStore.MAX_AMOUNT) { "Неверная сумма." }
        require(note != null && note.length <= 200) { "Комментарий слишком длинный." }
        val effectiveDue = if (type == "debt") {
            LicenseManager.requireNewDebt(app)
            val date = due.orEmpty()
            if (date.isNotEmpty()) {
                try { LocalDate.parse(date) } catch (_: Exception) { throw IllegalArgumentException("Неверный срок.") }
            }
            date
        } else ""
        require(LedgerSql.hasClient(db, clientId)) { "Клиент не найден." }
        require(DatabaseUtils.queryNumEntries(db, "entries") < MAX_ENTRIES) { "Достигнут лимит операций." }
        val available = LedgerStore.balance(entries(db, clientId), clientId)
        require(type != "payment" || amount <= available) { "Оплата превышает остаток долга." }
        require(type != "debt" || amount <= LedgerStore.MAX_AMOUNT - available) { "Превышен максимальный остаток." }
        val entry = LedgerStore.Entry(entryId, clientId, type, amount, timestamp, effectiveDue, note, "", purchase)
        db.insertOrThrow("entries", null, LedgerSql.values(entry))
    }

    private fun datedDebt(db: SQLiteDatabase, app: Context, existingId: String?, name: String?, phone: String?,
                         amount: Long, day: LocalDate?, due: String?, note: String?, entryId: String): String {
        require(day != null && !day.isAfter(LocalDate.now())) { "Дата записи не может быть в будущем." }
        val timestamp = if (day == LocalDate.now()) Instant.now().toString() else day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        uniqueDraft(db, entryId, "Этот черновик уже сохранён. Откройте книгу.", false)
        val id = existingId ?: UUID.randomUUID().toString().also { insertClient(db, name, phone, it) }
        insertEntry(db, app, id, "debt", amount, due, note, null, timestamp, entryId)
        return id
    }

    @JvmStatic fun recordDatedDebt(db: SQLiteDatabase, app: Context, existingId: String?, name: String?, phone: String?,
                                   amount: Long, day: LocalDate?, due: String?, note: String?, entryId: String): String =
        transaction(db) { datedDebt(db, app, existingId, name, phone, amount, day, due, note, entryId) }

    @JvmStatic fun recordCheckedDebt(db: SQLiteDatabase, app: Context, context: Context, command: ClientCommand.Debt): WriteReceipt {
        UUID.fromString(command.id)
        return transaction(db) {
            val client = command.existingId
            uniqueDraft(db, command.id, "Этот черновик уже сохранён. Откройте книгу.", true)
            if (client == null) {
                LedgerInput.checkClient(command.name, command.phone)
                if (!command.allowDuplicate) rejectDuplicate(db, command.name, command.phone)
            } else checkApproval(db, context, client, command.amount, command.approval)
            val rank = LedgerSql.journalRank(db, "debt", command.day, ZoneId.systemDefault())
            val id = datedDebt(db, app, client, command.name, command.phone, command.amount, command.day, command.due, command.note, command.id)
            WriteReceipt(command.destination, "debt", id, command.day, rank, false)
        }
    }

    private fun datedPayment(db: SQLiteDatabase, app: Context, clientId: String?, amount: Long, note: String?, entryId: String, day: LocalDate?) {
        val today = LocalDate.now()
        require(day != null && !day.isAfter(today)) { "Дата оплаты не может быть в будущем." }
        val zone = ZoneId.systemDefault()
        val timestamp = if (day == today) Instant.now().toString() else day.atStartOfDay(zone).toInstant().toString()
        uniqueDraft(db, entryId, "Этот черновик уже сохранён. Откройте историю.", false)
        if (day.isBefore(today)) {
            requireNotNull(clientId) { "Клиент не выбран." }
            require(amount <= DatedPayments.maximum(entries(db, clientId), clientId, day, today, zone)) {
                "Оплата на эту дату создаст переплату в истории. Уменьшите сумму или выберите более позднюю дату."
            }
        }
        insertEntry(db, app, clientId, "payment", amount, "", note, null, timestamp, entryId)
    }

    @JvmStatic fun addDraftPayment(db: SQLiteDatabase, app: Context, clientId: String?, amount: Long, note: String?, entryId: String, day: LocalDate?) =
        transaction(db) { datedPayment(db, app, clientId, amount, note, entryId, day) }

    @JvmStatic fun recordCheckedPayment(db: SQLiteDatabase, app: Context, command: ClientCommand.Payment): WriteReceipt {
        UUID.fromString(command.id)
        return transaction(db) {
            val rank = LedgerSql.journalRank(db, "payment", command.day, ZoneId.systemDefault())
            val settled = LedgerStore.balance(entries(db, command.clientId), command.clientId) == command.amount
            datedPayment(db, app, command.clientId, command.amount, command.note, command.id, command.day)
            if (settled) {
                val plan = FollowUpJournal.current(db, command.clientId)
                if (plan != null && plan.amount == 0L) FollowUpJournal.close(db, plan, command.id, LocalDate.now(), "settled")
            }
            WriteReceipt(command.destination, "payment", command.clientId, command.day, rank, settled)
        }
    }

    @JvmStatic fun addEntry(db: SQLiteDatabase, app: Context, clientId: String?, type: String?, amount: Long,
                            due: String?, note: String?, purchase: Purchase?) =
        transaction(db) { insertEntry(db, app, clientId, type, amount, due, note, purchase, Instant.now().toString(), UUID.randomUUID().toString()) }

    @JvmStatic fun recordCheckedPurchase(db: SQLiteDatabase, app: Context, context: Context, command: ClientCommand.ItemizedDebt): WriteReceipt {
        UUID.fromString(command.id)
        val purchase = requireNotNull(Purchase.decode(command.purchaseJson)) { "Добавьте товары." }
        return transaction(db) {
            uniqueDraft(db, command.id, "Эта покупка уже сохранена. Откройте историю.", true)
            val client = command.clientId
            checkApproval(db, context, client, purchase.total, command.approval)
            insertEntry(db, app, client, "debt", purchase.total, command.due, command.note, purchase, Instant.now().toString(), command.id)
            WriteReceipt(WriteDestination.DETAIL, "purchase", client, null, 0, false)
        }
    }

    @JvmStatic fun saveFollowUp(db: SQLiteDatabase, command: ClientCommand.FollowUp) =
        transaction(db) {
            require(LedgerSql.hasClient(db, command.clientId)) { "Клиент не найден." }
            FollowUpJournal.save(db, command.clientId, command.expectedId, command.day,
                command.note, command.amount, command.restart, entries(db, command.clientId), LocalDate.now())
        }
}
