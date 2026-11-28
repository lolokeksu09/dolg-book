package dev.ventry.raschet

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.domain.DatedLedger
import java.time.LocalDate
import java.time.ZoneId

/** SQL read/mapping boundary. Borrows a database; never opens or closes the owner's connection. */
internal object LedgerSql {
    @JvmStatic fun clientRows(db: SQLiteDatabase): Cursor =
        db.rawQuery("SELECT id,name,phone FROM clients ORDER BY name COLLATE NOCASE,id", null)

    @JvmStatic fun entryRows(db: SQLiteDatabase, clientId: String?): Cursor {
        val query = "SELECT id,client_id,type,amount,date,due,note,reverses,purchase FROM entries" +
            (if (clientId == null) "" else " WHERE client_id=?") + " ORDER BY seq"
        return db.rawQuery(query, clientId?.let { arrayOf(it) })
    }

    @JvmStatic fun clients(db: SQLiteDatabase): List<LedgerStore.Client> =
        clientRows(db).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(LedgerStore.Client(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }

    @JvmStatic fun entries(db: SQLiteDatabase, clientId: String?): List<LedgerStore.Entry> =
        entryRows(db, clientId).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(LedgerStore.Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                    cursor.getString(7), Purchase.decode(cursor.getString(8))))
            }
        }

    @JvmStatic fun snapshot(db: SQLiteDatabase): LedgerSnapshot {
        db.beginTransactionNonExclusive()
        try {
            val result = LedgerSnapshot(clients(db), entries(db, null), FollowUpJournal.read(db))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @JvmStatic fun hasClient(db: SQLiteDatabase, id: String?): Boolean =
        db.rawQuery("SELECT 1 FROM clients WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    @JvmStatic fun hasEntry(db: SQLiteDatabase, id: String?): Boolean =
        db.rawQuery("SELECT 1 FROM entries WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    @JvmStatic fun journalRank(db: SQLiteDatabase, type: String?, day: LocalDate?, zone: ZoneId): Int {
        requireNotNull(day) { "Дата не выбрана." }
        val later = HashSet<LocalDate>()
        db.rawQuery("SELECT DISTINCT date FROM entries WHERE type=?", arrayOf(type)).use { cursor ->
            while (cursor.moveToNext()) {
                val date = DatedLedger.localDate(cursor.getString(0), zone)
                if (date.isAfter(day)) later.add(date)
            }
        }
        return 1 + later.size
    }

    @JvmStatic fun values(entry: LedgerStore.Entry): ContentValues = ContentValues().apply {
        put("id", entry.id); put("client_id", entry.clientId); put("type", entry.type)
        put("amount", entry.amount); put("date", entry.date); put("due", entry.due)
        put("note", entry.note); put("reverses", entry.reverses)
        put("purchase", entry.purchase?.encode() ?: "")
    }
}
