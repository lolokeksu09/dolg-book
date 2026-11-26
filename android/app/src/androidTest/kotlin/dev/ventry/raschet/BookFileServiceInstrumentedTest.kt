package dev.ventry.raschet

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.data.ClientCommand.FileTask
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookFileServiceInstrumentedTest {
    @Test fun fileExportsPreserveDataAndOversizedInputDoesNotMutateBook() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name = "raschet-file-service-qa-only.db"
        val preferences = "raschet-file-service-qa-only"
        val dir = File(app.cacheDir, "raschet-file-service-qa-only")
        app.deleteDatabase(name)
        dir.mkdirs()
        try {
            LedgerStore(app, name).use { book ->
                book.addClient("=Synthetic", "")
                val client = book.clients().single()
                book.addEntry(client.id, "debt", 12345L, "", "Яблоки")
                val before = book.exportJson()
                val json = File(dir, "book.json")
                val csv = File(dir, "book.csv")
                val report = File(dir, "report.csv")
                val pdf = File(dir, "statement.pdf")
                fun uri(file: File) = Uri.fromFile(file).toString()
                val jsonResult = BookFileService.execute(app, FileTask.Export(uri(json), false, preferences), book)
                assertEquals("Резервная копия сохранена.", jsonResult.message)
                assertEquals(before, json.readText())
                assertTrue(app.getSharedPreferences(preferences, 0).getLong("last_json_export", 0L) > 0L)
                BookFileService.execute(app, FileTask.Export(uri(csv), true, preferences), book)
                val text = csv.readText()
                assertTrue(text.startsWith("\uFEFF"))
                assertTrue(text.contains("\"'=Synthetic\""))
                assertTrue(text.contains("123,45"))
                assertTrue(text.contains("Яблоки"))
                val day = LocalDate.now()
                BookFileService.execute(app, FileTask.Report(uri(report), day, day), book)
                assertTrue(report.readText().contains("123,45"))
                BookFileService.execute(app, FileTask.Pdf(uri(pdf), client.id, "Ventry QA"), book)
                val signature = ByteArray(4)
                java.io.DataInputStream(pdf.inputStream()).use { it.readFully(signature) }
                assertEquals("%PDF", String(signature, Charsets.US_ASCII))
                // The same one-shot request routing as MainActivity must produce
                // actual readable analytics CSV/PDF, not a credential result.
                for (pdfFormat in listOf(false, true)) {
                    val pending = PendingFileRequests()
                    val spec = dev.ventry.raschet.domain.AnalyticsExport.Spec(day, day, null, null, client.id, pdfFormat)
                    pending.analytics(spec)
                    val target = File(dir, if (pdfFormat) "analytics.pdf" else "analytics.csv")
                    assertNull(pending.consume(ActivityRequests.CREDENTIAL_OPEN, true, true, uri(target), preferences, "QA"))
                    val task = requireNotNull(pending.consume(ActivityRequests.REQUEST_ANALYTICS, true, true, uri(target), preferences, "QA"))
                    assertTrue(task is FileTask.Analytics)
                    BookFileService.execute(app, task, book)
                    assertTrue(target.length() > 0L)
                    if (pdfFormat) {
                        val header = ByteArray(4)
                        java.io.DataInputStream(target.inputStream()).use { it.readFully(header) }
                        assertEquals("%PDF", String(header, Charsets.US_ASCII))
                    } else {
                        assertTrue(target.readText().contains("123,45"))
                        assertTrue(target.readText().contains("'=Synthetic"))
                    }
                    assertNull(pending.consume(ActivityRequests.REQUEST_ANALYTICS, true, true, uri(target), preferences, "QA"))
                }
                assertEquals(before, book.exportJson())
                val vault = RecoveryVault(File(dir, "isolated-points"))
                val invalid = File(dir, "invalid.json").apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
                try {
                    BookFileService.execute(app, FileTask.Import(uri(invalid), preferences), book, vault)
                    fail("Invalid UTF-8 must fail before restore")
                } catch (_: IllegalArgumentException) {}
                assertEquals(before, book.exportJson())
                val oversized = File(dir, "oversized.json")
                java.io.RandomAccessFile(oversized, "rw").use { it.setLength(BackupSize.MAX_BYTES.toLong() + 1) }
                try {
                    BookFileService.execute(app, FileTask.Import(uri(oversized), preferences), book, vault)
                    fail("Oversized input must fail before restore")
                } catch (_: IllegalArgumentException) {}
                assertEquals(before, book.exportJson())
            }
        } finally {
            app.deleteDatabase(name)
            app.getSharedPreferences(preferences, 0).edit().clear().commit()
            dir.deleteRecursively()
        }
    }
}
