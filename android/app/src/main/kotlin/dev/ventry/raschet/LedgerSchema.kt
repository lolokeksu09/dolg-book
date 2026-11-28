package dev.ventry.raschet

import android.database.sqlite.SQLiteDatabase

/** Database v4 contract. Only SQLiteOpenHelper controls the upgrade transaction and version. */
internal object LedgerSchema {
    const val VERSION = 4

    @JvmStatic fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clients (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL)")
        db.execSQL("CREATE TABLE entries (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, client_id TEXT NOT NULL REFERENCES clients(id), type TEXT NOT NULL, amount INTEGER NOT NULL, date TEXT NOT NULL, due TEXT NOT NULL, note TEXT NOT NULL, reverses TEXT NOT NULL, purchase TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX entries_client_seq ON entries(client_id, seq)")
        createCatalog(db)
        FollowUpJournal.create(db)
    }

    @JvmStatic fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int, legacy: ClientDirectory?) {
        check(oldVersion in 1..3 && newVersion == VERSION) { "Unsupported migration" }
        if (oldVersion == 1) db.execSQL("ALTER TABLE entries ADD COLUMN purchase TEXT NOT NULL DEFAULT ''")
        if (oldVersion <= 2) createCatalog(db)
        FollowUpJournal.create(db)
        if (legacy != null) FollowUpJournal.migrate(db, legacy)
    }

    private fun createCatalog(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,unit TEXT NOT NULL,price INTEGER NOT NULL,favorite INTEGER NOT NULL,archived INTEGER NOT NULL)")
    }
}
