package dev.ventry.raschet

import android.os.Bundle
import dev.ventry.raschet.ActivityRequests.REQUEST_ANALYTICS
import dev.ventry.raschet.ActivityRequests.REQUEST_EXPORT
import dev.ventry.raschet.ActivityRequests.REQUEST_IMPORT
import dev.ventry.raschet.ActivityRequests.REQUEST_PDF
import dev.ventry.raschet.ActivityRequests.REQUEST_REPORT
import dev.ventry.raschet.data.ClientCommand.FileTask
import dev.ventry.raschet.domain.AnalyticsExport
import java.time.LocalDate

/** Keeps the parameters of a file picker until the associated result is consumed. */
class PendingFileRequests {
    private var pendingImport = false
    private var pendingExport = false
    private var csv = false
    private var pdfClient: String? = null
    private var reportFrom: LocalDate? = null
    private var reportTo: LocalDate? = null
    private var analytics: AnalyticsExport.Spec? = null

    fun requestImport() { pendingImport = true }

    fun export(csv: Boolean) {
        pendingExport = true
        this.csv = csv
    }

    fun pdf(client: String) { pdfClient = client }

    fun report(from: LocalDate, to: LocalDate) {
        reportFrom = from
        reportTo = to
    }

    fun analytics(spec: AnalyticsExport.Spec) { analytics = spec }

    fun clear(request: Int) {
        when (request) {
            REQUEST_IMPORT -> pendingImport = false
            REQUEST_EXPORT -> {
                pendingExport = false
                csv = false
            }
            REQUEST_PDF -> pdfClient = null
            REQUEST_REPORT -> {
                reportFrom = null
                reportTo = null
            }
            REQUEST_ANALYTICS -> analytics = null
        }
    }

    fun consume(request: Int, accepted: Boolean, authorized: Boolean, uri: String?,
                preferences: String, shop: String): FileTask? {
        try {
            if (!accepted || !authorized || uri.isNullOrEmpty()) return null
            return when (request) {
                REQUEST_IMPORT -> if (pendingImport) FileTask.Import(uri, preferences) else null
                REQUEST_EXPORT -> if (pendingExport) FileTask.Export(uri, csv, preferences) else null
                REQUEST_PDF -> pdfClient?.let { FileTask.Pdf(uri, it, shop) }
                REQUEST_REPORT -> {
                    val from = reportFrom ?: return null
                    val to = reportTo ?: return null
                    FileTask.Report(uri, from, to)
                }
                REQUEST_ANALYTICS -> analytics?.let { FileTask.Analytics(uri, it) }
                else -> null
            }
        } finally {
            clear(request)
        }
    }

    fun restore(state: Bundle) {
        pendingImport = state.getBoolean("pending_import")
        pendingExport = state.getBoolean("pending_export")
        csv = state.getBoolean("pending_export_csv")
        pdfClient = state.getString("pending_pdf_client")
        reportFrom = date(state, "pending_report_from")
        reportTo = date(state, "pending_report_to")
        val from = date(state, "pending_analytics_from")
        val to = date(state, "pending_analytics_to")
        analytics = if (from != null && to != null) AnalyticsExport.Spec(
            from, to, date(state, "pending_analytics_prev_from"), date(state, "pending_analytics_prev_to"),
            state.getString("pending_analytics_client"), state.getBoolean("pending_analytics_pdf"),
        ) else null
    }

    fun save(out: Bundle) {
        out.putBoolean("pending_import", pendingImport)
        out.putBoolean("pending_export", pendingExport)
        out.putBoolean("pending_export_csv", csv)
        out.putString("pending_pdf_client", pdfClient)
        out.putString("pending_report_from", reportFrom?.toString())
        out.putString("pending_report_to", reportTo?.toString())
        analytics?.let { spec ->
            out.putString("pending_analytics_from", spec.from.toString())
            out.putString("pending_analytics_to", spec.to.toString())
            out.putString("pending_analytics_prev_from", spec.previousFrom?.toString())
            out.putString("pending_analytics_prev_to", spec.previousTo?.toString())
            out.putString("pending_analytics_client", spec.customerId)
            out.putBoolean("pending_analytics_pdf", spec.pdf)
        }
    }

    private fun date(state: Bundle, key: String): LocalDate? =
        state.getString(key)?.let(LocalDate::parse)
}
