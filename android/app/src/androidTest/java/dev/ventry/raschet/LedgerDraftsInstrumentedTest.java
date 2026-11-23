package dev.ventry.raschet;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LedgerDraftsInstrumentedTest {
    @Test public void draftsAreIsolatedByClientAndOperationAndCanBeCleared(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        LedgerDrafts drafts=new LedgerDrafts(context);
        String left="qa-one-"+System.nanoTime(),right="qa-two-"+System.nanoTime();
        try{
            drafts.save(left,"debt","1 200,50","2026-12-01","Хлеб");
            drafts.save(left,"payment","500","","Часть");
            drafts.save(right,"debt","100","","Другое");
            assertEquals("1 200,50",drafts.get(left,"debt").amount);
            assertEquals("2026-12-01",drafts.get(left,"debt").due);
            assertEquals("500",drafts.get(left,"payment").amount);
            assertEquals("100",drafts.get(right,"debt").amount);
            drafts.clear(left,"debt");
            assertFalse(drafts.has(left,"debt"));
            assertEquals("500",drafts.get(left,"payment").amount);
        }finally{
            drafts.clear(left,"debt");
            drafts.clear(left,"payment");
            drafts.clear(right,"debt");
        }
    }
}
