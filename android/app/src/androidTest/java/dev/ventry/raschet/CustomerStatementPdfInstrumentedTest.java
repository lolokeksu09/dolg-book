package dev.ventry.raschet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CustomerStatementPdfInstrumentedTest {
    private static final String DB="statement-pdf-qa-only.db";
    private Context context;
    private LedgerStore ledger;
    @Before public void before(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DB);
        ledger=new LedgerStore(context,DB);
    }
    @After public void after(){
        if(ledger!=null)ledger.close();
        if(context!=null)context.deleteDatabase(DB);
    }
    @Test public void customerStatementHasPdfHeaderAndDoesNotModifyLedger() throws Exception {
        ledger.addClient("Адам Проверка","123");
        String id=ledger.clients().get(0).id;
        ledger.addEntry(id,"debt",255500L,"","Проверка");
        ledger.addEntry(id,"payment",50000L,"","Часть");
        String before=ledger.exportJson();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        CustomerStatementPdf.write(out,"Тестовый магазин",ledger.clients().get(0),ledger.entries());
        byte[] pdf=out.toByteArray();
        assertTrue(pdf.length>500);
        assertEquals("%PDF-",new String(Arrays.copyOfRange(pdf,0,5),"US-ASCII"));
        assertEquals(before,ledger.exportJson());
        assertEquals(205500L,LedgerStore.balance(ledger.entries(),id));
    }
    @Test public void longHistoryPaginates() throws Exception {
        ledger.addClient("Тест","000");
        String id=ledger.clients().get(0).id;
        for(int i=0;i<48;i++)ledger.addEntry(id,"debt",100L,"","Позиция "+i);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        CustomerStatementPdf.write(out,"Магазин",ledger.clients().get(0),ledger.entries());
        assertTrue(out.size()>1000);
    }
}
