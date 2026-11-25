package dev.ventry.raschet

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ventry.raschet.domain.AnalyticsExport
import dev.ventry.raschet.domain.AnalyticsProjection
import dev.ventry.raschet.domain.AnalyticsSeries
import dev.ventry.raschet.domain.DatedLedgerRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsExportInstrumentedTest {
    private data class Record(
        override val entryId: String, override val ownerId: String,
        override val operation: String, override val amountKopecks: Long,
        override val recordedAt: String, override val dueDate: String = "",
        override val reversedId: String = ""
    ) : DatedLedgerRecord

    @Test fun writeAndOpenBothScopedFormats() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val today = LocalDate.of(2026, 11, 25)
        val range = AnalyticsProjection.Range(today.minusDays(119), today)
        val records = listOf(
            Record("d1", "a", "debt", 35000, "2026-11-20T12:00:00Z"),
            Record("p1", "a", "payment", 10000, "2026-11-21T12:00:00Z"),
            Record("d2", "b", "debt", 98765, "2026-11-22T12:00:00Z")
        )
        val period = AnalyticsProjection.build(records, listOf("a", "b"), range, today, ZoneId.of("UTC"), "a")
        val scopes = listOf<String?>(null, "a")
        for (scope in scopes) {
            val client = period.customers.firstOrNull { it.id == scope }
            val series = if (scope == null) AnalyticsSeries.build(range, period.opening, period.daily)
                else AnalyticsSeries.build(range, requireNotNull(client).opening, period.focusedDaily)
            val lines = AnalyticsExport.lines(period, null, scope, if (scope == null) null else "Клиент QA", series)
            val csv = File.createTempFile("analytics-qa-", ".csv", context.cacheDir)
            val pdf = File.createTempFile("analytics-qa-", ".pdf", context.cacheDir)
            try {
                csv.writeText(AnalyticsExport.csv(lines), Charsets.UTF_8)
                val actual = csv.readText(Charsets.UTF_8)
                assertTrue(actual.startsWith("\uFEFF"))
                assertTrue(actual.contains(if (scope == null) "1237,65" else "250,00"))
                assertEquals(scope != null, actual.contains("Клиент QA"))
                if (scope != null) assertFalse(actual.contains("987,65"))
                pdf.outputStream().use { AnalyticsPdf.write(it, lines) }
                assertTrue(pdf.length() > 0)
                PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertTrue("Report must paginate", renderer.pageCount >= 2)
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val bitmap = Bitmap.createBitmap(298, 421, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertTrue("Rendered page must contain nonwhite content", pixels.any { it != android.graphics.Color.WHITE })
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            } finally {
                csv.delete()
                pdf.delete()
            }
        }
    }
}
