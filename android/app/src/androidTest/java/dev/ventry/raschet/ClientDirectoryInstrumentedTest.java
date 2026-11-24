package dev.ventry.raschet;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClientDirectoryInstrumentedTest {
    @Test public void archiveNeverHidesAnOutstandingDebtAndFavoritesAreReversible(){
        Context ctx=InstrumentationRegistry.getInstrumentation().getContext();
        ClientDirectory directory=new ClientDirectory(ctx);
        directory.clearAfterRestore();
        try{
            LedgerStore.Client c=new LedgerStore.Client("a","Алёна","+7 (999) 123-45-67");
            LedgerStore.Client d=new LedgerStore.Client("b","Другой","");
            var zero=new CollectionsInsights.CustomerStatus(c,0,0,0,null);
            var debt=new CollectionsInsights.CustomerStatus(d,100,0,0,null);
            directory.favorite("a",true);
            assertEquals(1,directory.select(Arrays.asList(zero,debt),ClientDirectory.FAVORITES).size());
            try{directory.archive("b",100,true);fail("outstanding debt");}
            catch(IllegalArgumentException expected){}
            directory.archive("a",0,true);
            assertEquals(1,directory.select(Arrays.asList(zero,debt),ClientDirectory.ARCHIVE).size());
            assertEquals("b",directory.select(Arrays.asList(zero,debt),ClientDirectory.ACTIVE).get(0).client.id);
            var restoredDebt=new CollectionsInsights.CustomerStatus(c,300,0,0,null);
            assertEquals(2,directory.select(Arrays.asList(restoredDebt,debt),ClientDirectory.ACTIVE).size());
            directory.archive("a",0,false);
            assertFalse(directory.archived("a",0));
            directory.clearAfterRestore();assertFalse(directory.favorite("a"));
        }finally{directory.clearAfterRestore();}
    }
    @Test public void normalizedSearchFindsYoAndFormattedPhone(){
        LedgerStore.Client client=new LedgerStore.Client("a","Алёна","+7 (999) 123-45-67");
        var status=new CollectionsInsights.CustomerStatus(client,100,0,0,null);
        var list=Arrays.asList(status);
        assertEquals(1,CollectionsInsights.visible(list,CollectionsInsights.ALL,false,"алена").size());
        assertEquals(1,CollectionsInsights.visible(list,CollectionsInsights.ALL,false,"9991234567").size());
        assertEquals(0,CollectionsInsights.visible(list,CollectionsInsights.ALL,false,"unknown").size());
        assertEquals(0,CollectionsInsights.visible(list,CollectionsInsights.LATE,false,"алена").size());
    }
    @Test public void relockBoundary(){
        assertFalse(BookPrivacy.shouldRelock(false,0,90000));
        assertFalse(BookPrivacy.shouldRelock(true,-1,90000));
        assertFalse(BookPrivacy.shouldRelock(true,1000,60999));
        assertTrue(BookPrivacy.shouldRelock(true,1000,61000));
    }
}
