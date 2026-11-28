package dev.ventry.raschet

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.ventry.raschet.data.CatalogRecord
import java.util.Collections

/** Catalog persistence shares the same SQLite connection and caller synchronization as the ledger. */
internal object LedgerCatalogSql {
    @JvmStatic fun products(db: SQLiteDatabase): MutableList<Product> =
        db.rawQuery("SELECT id,name,category,unit,price,favorite,archived FROM products ORDER BY name COLLATE NOCASE,id", null).use { cursor ->
            ArrayList<Product>().apply {
                while (cursor.moveToNext()) add(Product(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getInt(5) != 0, cursor.getInt(6) != 0))
            }
        }

    @JvmStatic fun save(db: SQLiteDatabase, product: Product) {
        db.beginTransaction()
        try {
            val all = products(db)
            val exists = all.any { it.id == product.id }
            require(all.none { it.id != product.id && !product.archived && !it.archived && it.sameName(product) }) {
                "Такой товар уже есть в этой категории."
            }
            require(exists || all.size < Product.MAX_PRODUCTS) { "Достигнут лимит каталога." }
            if (exists) {
                check(db.update("products", values(product), "id=?", arrayOf(product.id)) == 1) { "Товар не найден." }
            } else db.insertOrThrow("products", null, values(product))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @JvmStatic fun records(db: SQLiteDatabase): List<CatalogRecord> =
        Collections.unmodifiableList(products(db).map { CatalogRecord(it.id, it.name, it.category, it.unit, it.price, it.favorite, it.archived) })

    @JvmStatic fun saveRecord(db: SQLiteDatabase, record: CatalogRecord) =
        save(db, Product(record.id, record.name, record.category, record.unit, record.price, record.favorite, record.archived))

    @JvmStatic fun values(product: Product): ContentValues = ContentValues().apply {
        put("id", product.id); put("name", product.name); put("category", product.category)
        put("unit", product.unit); put("price", product.price)
        put("favorite", if (product.favorite) 1 else 0); put("archived", if (product.archived) 1 else 0)
    }
}
