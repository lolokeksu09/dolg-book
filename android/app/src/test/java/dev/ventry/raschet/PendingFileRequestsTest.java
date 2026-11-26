package dev.ventry.raschet;

import java.time.LocalDate;
import dev.ventry.raschet.data.ClientCommand.FileTask;
import dev.ventry.raschet.domain.AnalyticsExport;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.ventry.raschet.ActivityRequests.*;

public class PendingFileRequestsTest {
    private static final String URI="content://synthetic/report";
    private static final LocalDate DAY=LocalDate.of(2026,11,26);
    private final PendingFileRequests pending=new PendingFileRequests();

    @Test public void analyticsResultIsScopedAndConsumedOnce(){
        AnalyticsExport.Spec spec=new AnalyticsExport.Spec(DAY,DAY,null,null,"synthetic",true);
        pending.analytics(spec);
        assertNull(pending.consume(CREDENTIAL_OPEN,true,true,URI,"qa","QA"));
        FileTask.Analytics task=(FileTask.Analytics)pending.consume(REQUEST_ANALYTICS,true,true,URI,"qa","QA");
        assertEquals(spec,task.getSpec());
        assertEquals(URI,task.getUri());
        assertNull(pending.consume(REQUEST_ANALYTICS,true,true,URI,"qa","QA"));
    }
    @Test public void cancelledAndLockedResultsCannotBeReplayed(){
        pending.export(true);
        assertNull(pending.consume(REQUEST_EXPORT,false,true,URI,"qa","QA"));
        assertNull(pending.consume(REQUEST_EXPORT,true,true,URI,"qa","QA"));
        pending.requestImport();
        assertNull(pending.consume(REQUEST_IMPORT,true,false,URI,"qa","QA"));
        assertNull(pending.consume(REQUEST_IMPORT,true,true,URI,"qa","QA"));
        pending.pdf("synthetic");
        assertNull(pending.consume(REQUEST_PDF,true,true,null,"qa","QA"));
        assertNull(pending.consume(REQUEST_PDF,true,true,URI,"qa","QA"));
    }
    @Test public void legitimateFileResultsKeepParameters(){
        pending.report(DAY.minusDays(1),DAY);
        FileTask.Report report=(FileTask.Report)pending.consume(REQUEST_REPORT,true,true,URI,"qa","QA");
        assertEquals(DAY,report.getTo());
        pending.export(true);
        assertTrue(((FileTask.Export)pending.consume(REQUEST_EXPORT,true,true,URI,"qa","QA")).getCsv());
        pending.requestImport();
        assertEquals("qa",((FileTask.Import)pending.consume(REQUEST_IMPORT,true,true,URI,"qa","QA")).getPreferencesName());
        pending.pdf("synthetic");
        assertEquals("synthetic",((FileTask.Pdf)pending.consume(REQUEST_PDF,true,true,URI,"qa","QA")).getClientId());
    }
}
