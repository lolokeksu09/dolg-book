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
import java.time.ZoneId

/** Runs under the same serialized IO boundary as financial writes. No Activity or View is retained. */
internal object BookFileService {
    fun execute(app: Application, task: FileTask, ledger: LedgerStore): WriteReceipt {
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
                        val bytes = ledger.exportJson().toByteArray(Charsets.UTF_8)
                        val restorable = BackupSize.isSupported(bytes.size.toLong())
                        output(app, task.uri) { it.write(bytes) }
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
                is FileTask.Pdf -> {
                    val client = ledger.clients().firstOrNull { it.id == task.clientId }
                        ?: throw IllegalArgumentException("Клиент не найден.")
                    output(app, task.uri) {
                        CustomerStatementPdf.write(it, task.shop, client, ledger.entriesForClient(task.clientId))
                    }
                    receipt("Выписка PDF сохранена.")
                }
                is FileTask.Import -> {
                    val backup = app.contentResolver.openInputStream(Uri.parse(task.uri))?.use {
                        BackupSize.readUtf8(it)
                    } ?: throw IOException("Не удалось прочитать файл.")
                    restore(app, ledger, backup, task.preferencesName)
                }
                is FileTask.RestorePoint -> {
                    val backup = RecoveryVault(app).readLatest()
                    restore(app, ledger, backup, task.preferencesName)
                }
                FileTask.CreatePoint -> {
                    RecoveryVault(app).create(ledger)
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

    private fun restore(app: Application, ledger: LedgerStore, backup: String, preferencesName: String): WriteReceipt {
        // Preserve the pre-restore state before any replacement; abort if encryption or storage fails.
        RecoveryVault(app).create(ledger)
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

    internal fun csvCell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
