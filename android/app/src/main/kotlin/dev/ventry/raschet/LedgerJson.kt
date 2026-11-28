package dev.ventry.raschet

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.JsonReader
import android.util.JsonToken
import dev.ventry.raschet.domain.ImportLedgerValidation
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate

/** Streaming v1–v5 restore/export. The caller owns the stream and SQLiteOpenHelper. */
internal object LedgerJson {
    private const val MAX_CLIENTS = 10_000
    private const val MAX_ENTRIES = 100_000
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val amountDigits = Regex("\\d{1,12}")
    private val versions = Regex("[1-5]")

    @JvmStatic fun write(db: SQLiteDatabase, output: Writer) {
        db.beginTransactionNonExclusive()
        try {
            output.write("{\"clients\":[")
            var first = true
            LedgerSql.clientRows(db).use { c ->
                while (c.moveToNext()) {
                    if (!first) output.write(','.code)
                    first = false
                    output.write(JSONObject().put("id", c.getString(0)).put("name", c.getString(1))
                        .put("phone", c.getString(2)).toString())
                }
            }
            output.write("],\"entries\":[")
            first = true
            var version = 2
            LedgerSql.entryRows(db, null).use { c ->
                while (c.moveToNext()) {
                    if (!first) output.write(','.code)
                    first = false
                    val row = JSONObject().put("id", c.getString(0)).put("clientId", c.getString(1))
                        .put("type", c.getString(2)).put("amount", c.getLong(3)).put("date", c.getString(4))
                        .put("due", c.getString(5)).put("note", c.getString(6))
                    if (c.getString(2) == "reversal") row.put("reverses", c.getString(7))
                    val purchase = Purchase.decode(c.getString(8))
                    if (purchase != null) {
                        version = if (purchase.hasUnits()) 4 else maxOf(version, 3)
                        row.put("purchase", JSONObject(purchase.encode()))
                    }
                    output.write(row.toString())
                }
            }
            output.write(']'.code)
            val catalog = LedgerCatalogSql.products(db)
            val plans = FollowUpJournal.read(db)
            if (catalog.isNotEmpty() || version == 4 || plans.isNotEmpty()) {
                version = 4
                output.write(",\"products\":[")
                first = true
                for (product in catalog) {
                    if (!first) output.write(','.code)
                    first = false
                    output.write(product.json().toString())
                }
                output.write(']'.code)
            }
            if (plans.isNotEmpty()) {
                version = 5
                output.write(",\"plans\":[")
                first = true
                for (plan in plans) {
                    if (!first) output.write(','.code)
                    first = false
                    output.write(plan.json().toString())
                }
                output.write(']'.code)
            }
            output.write(",\"version\":")
            output.write(version.toString())
            output.write('}'.code)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @JvmStatic fun restore(db: SQLiteDatabase, source: Reader): IntArray {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE raschet_import_stage (seq INTEGER PRIMARY KEY,kind TEXT NOT NULL,row_json TEXT NOT NULL)")
            var version = 0
            var clientCount = -1
            var entryCount = -1
            var productCount = -1
            var planCount = -1
            val fields = HashSet<String>()
            // Do not close the caller-owned Reader.
            val reader = JsonReader(ImportJsonGuard(source))
            reader.beginObject()
            db.compileStatement("INSERT INTO raschet_import_stage(kind,row_json) VALUES(?,?)").use { stage ->
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(fields.add(key)) { "Повтор поля JSON." }
                    require(fields.size <= 32) { "Слишком много полей JSON." }
                    when (key) {
                        "version" -> {
                            require(reader.peek() == JsonToken.NUMBER) { "Поддерживаются копии v1–v5." }
                            val raw = reader.nextString()
                            require(versions.matches(raw)) { "Поддерживаются копии v1–v5." }
                            version = raw.toInt()
                        }
                        "clients" -> clientCount = stageRows(reader, stage, key, MAX_CLIENTS)
                        "entries" -> entryCount = stageRows(reader, stage, key, MAX_ENTRIES)
                        "products" -> productCount = stageRows(reader, stage, key, Product.MAX_PRODUCTS)
                        "plans" -> planCount = stageRows(reader, stage, key, FollowUpJournal.MAX_PLANS)
                        else -> reader.skipValue()
                    }
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Лишние данные после JSON." }
            require(version in 1..5 && clientCount >= 0 && entryCount >= 0) { "Неполная книга или неподдерживаемая версия." }
            require((version >= 4) == (productCount >= 0) && (version == 5) == (planCount >= 0)) {
                "Некорректный каталог или история планов для версии JSON."
            }

            val newProducts = ArrayList<Product>()
            val productIds = HashSet<String>()
            val productKeys = HashSet<String>()
            stagedRows(db, "products").use { rows ->
                while (rows.moveToNext()) {
                    val product = Product.fromJson(JSONObject(rows.getString(0)))
                    require(productIds.add(product.id) && (product.archived || productKeys.add(product.normalizedKey()))) { "Дубли в каталоге." }
                    newProducts.add(product)
                }
            }
            val newClients = ArrayList<LedgerStore.Client>()
            val ids = HashSet<String>()
            stagedRows(db, "clients").use { rows ->
                while (rows.moveToNext()) {
                    val c = JSONObject(rows.getString(0))
                    val id = c.getString("id")
                    val name = c.getString("name")
                    val phone = c.getString("phone")
                    LedgerInput.checkClient(name, phone)
                    require(id.isNotEmpty() && id.length <= 100 && ids.add(id)) { "Повтор или неверный ID клиента." }
                    newClients.add(LedgerStore.Client(id, LedgerInput.trim(name), phone))
                }
            }
            // Replacements are uncommitted until the last financial invariant succeeds.
            db.delete("products", null, null)
            for (p in newProducts) db.insertOrThrow("products", null, LedgerCatalogSql.values(p))
            db.delete("collection_plans", null, null)
            db.delete("entries", null, null)
            db.delete("clients", null, null)
            for (c in newClients) {
                val row = ContentValues().apply { put("id", c.id); put("name", c.name); put("phone", c.phone) }
                db.insertOrThrow("clients", null, row)
            }
            val entryIds = HashSet<String>()
            val history = ImportLedgerValidation(version, ids)
            stagedRows(db, "entries").use { rows ->
                while (rows.moveToNext()) {
                    val j = JSONObject(rows.getString(0))
                    val id = j.getString("id")
                    val cid = j.getString("clientId")
                    val type = j.getString("type")
                    val date = j.getString("date")
                    val due = j.getString("due")
                    val note = j.getString("note")
                    val reverses = j.optString("reverses", "")
                    val rawAmount = j.get("amount")
                    require(rawAmount is Number && amountDigits.matches(rawAmount.toString())) {
                        "Сумма операции должна быть целым числом копеек."
                    }
                    val amount = rawAmount.toString().toLong()
                    require(id.isNotEmpty() && id.length <= 100 && entryIds.add(id) && cid in ids &&
                        (type == "debt" || type == "payment" || type == "reversal") &&
                        amount > 0 && amount <= LedgerStore.MAX_AMOUNT && note.length <= 200 && date.length <= 40 && due.length <= 10) {
                        "Некорректная запись."
                    }
                    try { Instant.parse(date) } catch (_: Exception) {
                        try { OffsetDateTime.parse(date) } catch (_: Exception) { throw IllegalArgumentException("Неверная дата операции.") }
                    }
                    if (due.isNotEmpty()) {
                        try { LocalDate.parse(due) } catch (_: Exception) { throw IllegalArgumentException("Неверный срок возврата.") }
                        require(type == "debt") { "Срок задан не для долга." }
                    }
                    val purchase = if (j.has("purchase")) {
                        require(version >= 3 && type == "debt") { "Состав покупки разрешён только для долга в v3/v4." }
                        val p = requireNotNull(Purchase.decode(j.getJSONObject("purchase").toString())) { "Неверный состав или сумма покупки." }
                        require(p.total == amount && (version >= 4 || !p.hasUnits())) { "Неверный состав или сумма покупки." }
                        p
                    } else null
                    history.accept(cid, id, type, amount, reverses, note)
                    val entry = LedgerStore.Entry(id, cid, type, amount, date, due, note, reverses, purchase)
                    db.insertOrThrow("entries", null, LedgerSql.values(entry))
                }
            }
            if (planCount >= 0) {
                val check = FollowUpJournal.ImportCheck(db, ids)
                stagedRows(db, "plans").use { rows ->
                    while (rows.moveToNext()) {
                        val plan = FollowUpJournal.Record.parse(JSONObject(rows.getString(0)))
                        check.accept(plan)
                        FollowUpJournal.insert(db, plan)
                    }
                }
            }
            db.execSQL("DROP TABLE raschet_import_stage")
            db.setTransactionSuccessful()
            return intArrayOf(clientCount, entryCount)
        } finally { db.endTransaction() }
    }

    private fun stagedRows(db: SQLiteDatabase, kind: String): Cursor =
        db.rawQuery("SELECT row_json FROM raschet_import_stage WHERE kind=? ORDER BY seq", arrayOf(kind))

    private fun stageRows(reader: JsonReader, stage: SQLiteStatement, kind: String, max: Int): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            require(++count <= max) { "Книга слишком большая." }
            val row = readImportValue(reader, 0)
            require(row is JSONObject) { "Некорректная строка книги." }
            stage.bindString(1, kind)
            stage.bindString(2, row.toString())
            stage.executeInsert()
        }
        reader.endArray()
        return count
    }

    /** Reject duplicate keys, exponent/fraction spelling and excessive nesting before staging. */
    private fun readImportValue(reader: JsonReader, depth: Int): Any {
        require(depth <= 16) { "Слишком глубокая структура JSON." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(!has(key)) { "Повтор поля JSON." }
                    put(key, readImportValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readImportValue(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(integer.matches(number)) { "Числа в книге должны быть целыми." }
                try { number.toLong() } catch (ex: NumberFormatException) { throw IllegalArgumentException("Число вне диапазона.", ex) }
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> throw IllegalArgumentException("Некорректный JSON.")
        }
    }
}
