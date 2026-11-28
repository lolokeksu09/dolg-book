package dev.ventry.raschet

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import dev.ventry.raschet.bridge.CorrectionEngine
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Atomic reversal/correction. No new debt creation or bypass of existing JNI validation. */
internal object LedgerCorrections {
    private const val MAX_ENTRIES = 100_000
    @JvmStatic fun lastActive(db: SQLiteDatabase, clientId: String): LedgerStore.Entry? {
        val all = LedgerSql.entries(db, clientId)
        val undone = all.filter { it.type == "reversal" }.mapTo(HashSet()) { it.reverses }
        return all.asReversed().firstOrNull { it.clientId == clientId && it.type != "reversal" && it.id !in undone }
    }

    @JvmStatic fun correctedBalance(available: Long, last: LedgerStore.Entry?, amount: Long): Long {
        requireNotNull(last) { "Неверная операция." }
        return CorrectionEngine.correctedBalance(available, last.type, last.amount, amount)
    }

    @JvmStatic fun reverse(db: SQLiteDatabase, clientId: String?, expectedId: String?, reason: String?) {
        require(reason != null && LedgerInput.trim(reason).isNotEmpty() && LedgerInput.trim(reason).length <= 200) { "Укажите причину отмены." }
        db.beginTransaction()
        try {
            requireNotNull(clientId) { "Клиент не выбран." }
            val last = requireNotNull(lastActive(db, clientId)) { "Нет действующей операции для отмены." }
            require(expectedId == null || expectedId == last.id) { "Запись уже не последняя. Откройте актуальную историю." }
            require(DatabaseUtils.queryNumEntries(db, "entries") < MAX_ENTRIES) { "Достигнут лимит операций." }
            val balance = LedgerStore.balance(LedgerSql.entries(db, clientId), clientId)
            require(last.type != "debt" || balance >= last.amount) {
                "Этот долг уже частично погашен. Сначала отмените последнюю оплату."
            }
            val undo = LedgerStore.Entry(UUID.randomUUID().toString(), clientId, "reversal", last.amount,
                Instant.now().toString(), "", LedgerInput.trim(reason), last.id)
            db.insertOrThrow("entries", null, LedgerSql.values(undo))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @JvmStatic fun correct(db: SQLiteDatabase, clientId: String?, expectedId: String?, amount: Long, due: String?, note: String?, reason: String?) {
        require(reason != null && LedgerInput.trim(reason).isNotEmpty() && LedgerInput.trim(reason).length <= 200) { "Укажите причину исправления." }
        require(note != null && note.length <= 200) { "Комментарий слишком длинный." }
        require(amount > 0 && amount <= LedgerStore.MAX_AMOUNT) { "Неверная сумма." }
        db.beginTransaction()
        try {
            requireNotNull(clientId) { "Клиент не выбран." }
            val last = requireNotNull(lastActive(db, clientId)) { "Нет операции для исправления." }
            require(expectedId == null || expectedId == last.id) { "Запись уже не последняя. Откройте актуальную историю." }
            val effectiveDue = if (last.type == "debt") due.orEmpty() else ""
            if (effectiveDue.isNotEmpty()) {
                try { LocalDate.parse(effectiveDue) } catch (_: Exception) { throw IllegalArgumentException("Неверный срок.") }
            }
            val all = LedgerSql.entries(db, clientId)
            require(DatabaseUtils.queryNumEntries(db, "entries") + 2 <= MAX_ENTRIES) { "Недостаточно места в истории операций." }
            correctedBalance(LedgerStore.balance(all, clientId), last, amount)
            require(last.purchase == null || amount == last.amount) {
                "Для изменения суммы покупки отмените её и создайте новую с правильными товарами."
            }
            require(last.amount != amount || last.due != effectiveDue || last.note != note) { "Новые данные совпадают со старыми." }
            val timestamp = Instant.now().toString()
            val undo = LedgerStore.Entry(UUID.randomUUID().toString(), clientId, "reversal", last.amount, timestamp,
                "", LedgerInput.trim(reason), last.id)
            val replacement = LedgerStore.Entry(UUID.randomUUID().toString(), clientId, last.type, amount, timestamp,
                effectiveDue, note, "", last.purchase)
            db.insertOrThrow("entries", null, LedgerSql.values(undo))
            db.insertOrThrow("entries", null, LedgerSql.values(replacement))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
