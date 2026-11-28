package dev.ventry.raschet

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import dev.ventry.raschet.data.ClientCommand.FileTask
import dev.ventry.raschet.data.WriteDestination
import dev.ventry.raschet.data.WriteReceipt
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.time.ZoneId
import java.time.LocalDate
import dev.ventry.raschet.domain.AnalyticsExport
import dev.ventry.raschet.domain.CsvText
import dev.ventry.raschet.domain.AnalyticsProjection
import dev.ventry.raschet.domain.AnalyticsSeries

/** Runs under the same serialized IO boundary as financial writes. No Activity or View is retained. */
internal object BookFileService {
    fun execute(app: Application, task: FileTask, ledger: LedgerStore, vault: RecoveryVault = RecoveryVault(app)): WriteReceipt {
        try {
            return when (task) {
                is FileTask.Export -> {
                    if (task.csv) {
                        val snapshot = ledger.readSnapshot()
                        output(app, task.uri) { stream ->
                            val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
                            writer.write("\uFEFFКлиент;Телефон;Дата;Тип;Сумма (руб);Срок;Комментарий;ID;Отменяет ID;Состав покупки\r\n")
                            val people = snapshot.clients.associateBy { it.id }
                            for (entry in snapshot.entries) {
                                val client = people[entry.clientId]
                                writer.write(listOf(client?.name.orEmpty(), client?.phone.orEmpty(), entry.date,
                                    entry.type, PeriodReport.rubles(entry.amount), entry.due, entry.note,
                                    entry.id, entry.reverses, entry.purchase?.summary().orEmpty())
                                    .joinToString(";") { csvCell(it) })
                                writer.write("\r\n")
                            }
                            writer.flush()
                        }
                        receipt("Таблица CSV сохранена.")
                    } else {
                        var written = 0L
                        output(app, task.uri) { stream ->
                            val counted = object : OutputStream() {
                                override fun write(b: Int) { stream.write(b); written++ }
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    stream.write(b, off, len)
                                    written += len
                                }
                                override fun flush() = stream.flush()
                            }
                            val writer = OutputStreamWriter(counted, Charsets.UTF_8).buffered()
                            ledger.writeJson(writer)
                            writer.flush()
                        }
                        val restorable = BackupSize.isSupported(written)
                        val status = app.getSharedPreferences(task.preferencesName, Context.MODE_PRIVATE).edit()
                        if (restorable) status.putLong("last_json_export", System.currentTimeMillis())
                        else status.remove("last_json_export")
                        status.apply()
                        receipt(if (restorable) "Резервная копия сохранена."
                            else "JSON сохранён, но превышает допустимый размер импорта (${BackupSize.MAX_MIB} МиБ). Не удаляйте приложение.")
                    }
                }
                is FileTask.Report -> {
                    val bytes = PeriodReport.build(ledger.entries(), task.from, task.to,
                        ZoneId.systemDefault()).csv().toByteArray(Charsets.UTF_8)
                    output(app, task.uri) { it.write(bytes) }
                    receipt("Отчёт CSV сохранён.")
                }
                is FileTask.Analytics -> {
                    val spec = task.spec
                    val snapshot = ledger.readSnapshot()
                    val today = LocalDate.now()
                    val zone = ZoneId.systemDefault()
                    val names = snapshot.clients.associate { it.id to it.name }
                    val name = spec.customerId?.let { requireNotNull(names[it]) { "Клиент не найден." } }
                    val period = AnalyticsProjection.build(snapshot.entries, names.keys,
                        AnalyticsProjection.Range(spec.from, spec.to), today, zone, spec.customerId)
                    val previous = spec.previousFrom?.let {
                        AnalyticsProjection.build(snapshot.entries, names.keys,
                            AnalyticsProjection.Range(it, requireNotNull(spec.previousTo)), today, zone, spec.customerId)
                    }
                    val customer = period.customers.firstOrNull { it.id == spec.customerId }
                    val series = if (spec.customerId == null) AnalyticsSeries.build(period.range, period.opening, period.daily)
                        else AnalyticsSeries.build(period.range, requireNotNull(customer).opening, period.focusedDaily)
                    val lines = AnalyticsExport.lines(period, previous, spec.customerId, name, series)
                    if (spec.pdf) output(app, task.uri) { AnalyticsPdf.write(it, lines) }
                    else {
                        val bytes = AnalyticsExport.csv(lines).toByteArray(Charsets.UTF_8)
                        output(app, task.uri) { it.write(bytes) }
                    }
                    receipt(if (spec.pdf) "Статистика PDF сохранена." else "Статистика CSV сохранена.")
                }
                is FileTask.Pdf -> {
                    val client = ledger.clients().firstOrNull { it.id == task.clientId }
                        ?: throw IllegalArgumentException("Клиент не найден.")
                    output(app, task.uri) {
                        CustomerStatementPdf.write(it, task.shop, client, ledger.entriesForClient(task.clientId))
                    }
                    receipt("Выписка PDF сохранена.")
                }
                is FileTask.Import -> {
                    val stream = app.contentResolver.openInputStream(Uri.parse(task.uri))
                        ?: throw IOException("Не удалось прочитать файл.")
                    BackupSize.openUtf8(stream).use { backup ->
                        restore(app, ledger, backup, task.preferencesName, vault)
                    }
                }
                is FileTask.RestorePoint -> {
                    vault.openLatest().use { restore(app, ledger, it, task.preferencesName, vault) }
                }
                FileTask.DisableAutoCloud -> {
                    dev.ventry.raschet.cloud.AutoBackup.disable(app)
                    receipt("Автокопирование отключено. Облачные копии сохранены.")
                }
                is FileTask.Cloud.EnableAuto -> {
                    try {
                        dev.ventry.raschet.cloud.AutoBackup.enable(app,ledger,task.expected,task.passphrase)
                        receipt("Первая копия сохранена. Автокопирование включено.")
                    } finally { task.clearSecret() }
                }
                is FileTask.Cloud.Put -> {
                    try {
                        require(task.slot!=3 || !dev.ventry.raschet.cloud.AutoBackup.status(app).enabled){"Сначала выключите автокопирование для слота 3."}
                        dev.ventry.raschet.cloud.CloudBookService.save(app,ledger,task)
                        receipt("Зашифрованная облачная копия сохранена.")
                    } finally { task.clearSecret() }
                }
                is FileTask.Cloud.Restore -> {
                    try {
                        val bytes=dev.ventry.raschet.cloud.CloudBookService.open(app,task)
                        try {
                            BackupSize.openUtf8(bytes.inputStream()).use {
                                restore(app,ledger,it,task.preferencesName,vault)
                            }
                        } finally { bytes.fill(0) }
                    } finally { task.clearSecret() }
                }
                FileTask.CreatePoint -> {
                    vault.create(ledger)
                    receipt("Защищённая точка создана.")
                }
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException(failure.message ?: "Не удалось выполнить файловую операцию.", failure)
        } catch (failure: SecurityException) {
            throw IllegalArgumentException("Нет доступа к выбранному файлу или защищённой копии.", failure)
        } catch (failure: org.json.JSONException) {
            throw IllegalArgumentException("Некорректный JSON или неподдерживаемый формат копии.", failure)
        }
    }

    private fun restore(app: Application, ledger: LedgerStore, backup: Reader, preferencesName: String, vault: RecoveryVault): WriteReceipt {
        // Preserve the pre-restore state before any replacement; abort if encryption or storage fails.
        vault.create(ledger)
        val count = ledger.importJson(backup)
        // The transaction has committed. Housekeeping failure must not report a retryable restore failure.
        var warning = ""
        try {
            app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().remove("last_json_export").apply()
            ClientDirectory(app).clearAfterRestore()
            QuickDebtDrafts(app).clear()
            LedgerDrafts(app).clearAll()
        } catch (_: Exception) {
            Log.w("Raschet", "Restore committed; local preferences cleanup failed")
            warning = " Не удалось очистить часть локальных настроек."
        }
        try { RecoveryScheduler.enqueue(app) }
        catch (_: Exception) { Log.w("Raschet", "Restore committed; recovery scheduling failed") }
        return WriteReceipt(WriteDestination.CLIENTS, "restore", null, null, 0, false,
            "Восстановлено: ${count[0]} клиентов, ${count[1]} операций.$warning")
    }

    private fun receipt(message: String) = WriteReceipt(WriteDestination.KEEP, "file", null, null, 0, false, message)

    private fun output(app: Application, uri: String, write: (OutputStream) -> Unit) {
        val stream = app.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw IOException("Нет доступа к файлу.")
        stream.use { write(it); it.flush() }
    }

    internal fun csvCell(value: String): String = CsvText.cell(value)
}
