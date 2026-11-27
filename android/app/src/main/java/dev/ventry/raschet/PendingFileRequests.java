package dev.ventry.raschet;

import android.os.Bundle;
import java.time.LocalDate;
import dev.ventry.raschet.data.ClientCommand.FileTask;
import dev.ventry.raschet.domain.AnalyticsExport;
import static dev.ventry.raschet.ActivityRequests.*;

/** Owns pending SAF parameters, never financial snapshots or an Activity reference.
 * A result consumes its matching request once, including cancellation or denied access. */
final class PendingFileRequests {
    private boolean pendingImport,pendingExport,csv;
    private String pdfClient;
    private LocalDate reportFrom,reportTo;
    private AnalyticsExport.Spec analytics;

    void requestImport(){pendingImport=true;}
    void export(boolean csv){pendingExport=true;this.csv=csv;}
    void pdf(String client){pdfClient=client;}
    void report(LocalDate from,LocalDate to){reportFrom=from;reportTo=to;}
    void analytics(AnalyticsExport.Spec spec){analytics=spec;}

    void clear(int request){
        switch(request){
            case REQUEST_IMPORT: pendingImport=false;break;
            case REQUEST_EXPORT: pendingExport=false;csv=false;break;
            case REQUEST_PDF: pdfClient=null;break;
            case REQUEST_REPORT: reportFrom=null;reportTo=null;break;
            case REQUEST_ANALYTICS: analytics=null;break;
            default: break;
        }
    }

    FileTask consume(int request,boolean accepted,boolean authorized,String uri,String preferences,String shop){
        try{
            if(!accepted||!authorized||uri==null||uri.isEmpty())return null;
            switch(request){
                case REQUEST_IMPORT: return pendingImport?new FileTask.Import(uri,preferences):null;
                case REQUEST_EXPORT: return pendingExport?new FileTask.Export(uri,csv,preferences):null;
                case REQUEST_PDF: return pdfClient==null?null:new FileTask.Pdf(uri,pdfClient,shop);
                case REQUEST_REPORT: return reportFrom==null||reportTo==null?null:new FileTask.Report(uri,reportFrom,reportTo);
                case REQUEST_ANALYTICS: return analytics==null?null:new FileTask.Analytics(uri,analytics);
                default: return null;
            }
        }finally{clear(request);}
    }

    void restore(Bundle state){
        pendingImport=state.getBoolean("pending_import");
        pendingExport=state.getBoolean("pending_export");
        csv=state.getBoolean("pending_export_csv");
        pdfClient=state.getString("pending_pdf_client");
        reportFrom=date(state,"pending_report_from");reportTo=date(state,"pending_report_to");
        LocalDate from=date(state,"pending_analytics_from"),to=date(state,"pending_analytics_to");
        analytics=from==null||to==null?null:new AnalyticsExport.Spec(from,to,
                date(state,"pending_analytics_prev_from"),date(state,"pending_analytics_prev_to"),
                state.getString("pending_analytics_client"),state.getBoolean("pending_analytics_pdf"));
    }

    void save(Bundle out){
        out.putBoolean("pending_import",pendingImport);
        out.putBoolean("pending_export",pendingExport);out.putBoolean("pending_export_csv",csv);
        out.putString("pending_pdf_client",pdfClient);
        date(out,"pending_report_from",reportFrom);date(out,"pending_report_to",reportTo);
        if(analytics!=null){
            date(out,"pending_analytics_from",analytics.getFrom());date(out,"pending_analytics_to",analytics.getTo());
            date(out,"pending_analytics_prev_from",analytics.getPreviousFrom());date(out,"pending_analytics_prev_to",analytics.getPreviousTo());
            out.putString("pending_analytics_client",analytics.getCustomerId());
            out.putBoolean("pending_analytics_pdf",analytics.getPdf());
        }
    }
    private static LocalDate date(Bundle state,String key){
        String value=state.getString(key);return value==null?null:LocalDate.parse(value);
    }
    private static void date(Bundle state,String key,LocalDate date){
        if(date!=null)state.putString(key,date.toString());
    }
}
