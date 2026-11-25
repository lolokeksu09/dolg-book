package dev.ventry.raschet

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dev.ventry.raschet.domain.AnalyticsExport
import java.io.OutputStream

/** Bounded, paginated summary; no network, no raw ledger rows or phone numbers. */
internal object AnalyticsPdf {
    fun write(output: OutputStream, lines: List<AnalyticsExport.Line>) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        try {
            var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            var pageNumber = 1
            var y = 82f
            fun header() {
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                paint.color = Color.rgb(25, 26, 29)
                paint.typeface = medium
                paint.textSize = 19f
                canvas.drawText("Расчёт · Статистика", 42f, 47f, paint)
                paint.color = Color.LTGRAY
                canvas.drawLine(42f, 62f, 553f, 62f, paint)
            }
            fun finish() {
                paint.typeface = normal
                paint.textSize = 10f
                paint.color = Color.DKGRAY
                page.canvas.drawText("Ventry · Страница $pageNumber", 42f, 812f, paint)
                document.finishPage(page)
            }
            header()
            for (row in lines) {
                val section = row.label == "Раздел"
                val heading = if (section) row.value else row.label + ": " + row.value
                var remaining = heading.replace('\n', ' ').replace('\r', ' ')
                while (remaining.isNotEmpty()) {
                    if (y > 775f) {
                        finish()
                        pageNumber++
                        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                        header()
                        y = 82f
                    }
                    paint.typeface = if (section) medium else normal
                    paint.textSize = if (section) 13f else 11f
                    paint.color = if (section) Color.BLACK else Color.DKGRAY
                    var n = paint.breakText(remaining, true, 511f, null).coerceAtLeast(1)
                    if (n < remaining.length && Character.isHighSurrogate(remaining[n - 1])) n = if (n > 1) n - 1 else 2
                    page.canvas.drawText(remaining.substring(0, n), 42f, y, paint)
                    remaining = remaining.substring(n)
                    y += 17f
                }
                if (section) y += 6f
            }
            finish()
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
