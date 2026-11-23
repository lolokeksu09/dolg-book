package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DebtReminderServiceInstrumentedTest {
    private static final String DB="reminder-qa-only.db";
    private Context context;private LedgerStore book;
    @Before public void before(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB);book=new LedgerStore(context,DB);
    }
    @After public void after(){
        book.close();context.deleteDatabase(DB);
    }
    @Test public void summaryCountsOnlyCustomersWithUnpaidDueEntries(){
        book.addClient("Первый","");
        book.addClient("Второй","");
        String a=book.clients().get(0).id;
        String b=book.clients().get(1).id;
        LocalDate today=LocalDate.now();
        book.addEntry(a,"debt",1500,today.minusDays(2).toString(),"");
        book.addEntry(b,"debt",2000,today.plusDays(3).toString(),"");
        DebtReminderService.Status status=DebtReminderService.summarize(
                CollectionsInsights.analyze(book.clients(),book.entries(),today));
        assertEquals(1,status.late);assertEquals(1,status.soon);
        book.addEntry(a,"payment",1500,"","");
        status=DebtReminderService.summarize(
                CollectionsInsights.analyze(book.clients(),book.entries(),today));
        assertEquals(0,status.late);assertEquals(1,status.soon);
    }
}
