package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PurchaseDraftsInstrumentedTest {
    @Test public void restoresIncompleteRowsAndClearsOnlyCommittedIdentity() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storage="raschet-purchase-draft-qa-only";
        context.getSharedPreferences(storage,0).edit().clear().commit();
        try{
            PurchaseDrafts drafts=new PurchaseDrafts(context,storage);
            String id=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
            drafts.save("a",new PurchaseDrafts.Draft(id,Arrays.asList(
                    new PurchaseDrafts.Row("Яблоки","1,25","120,50","кг"),
                    new PurchaseDrafts.Row("Груши","2,","","кг")),"10,","2026-12-01","Запись"));
            PurchaseDrafts.Draft restored=new PurchaseDrafts(context,storage).get("a");
            assertNotNull(restored);
            assertEquals(id,restored.id);
            assertEquals(2,restored.rows.size());
            assertEquals("1,25",restored.rows.get(0).quantity);
            assertEquals("120,50",restored.rows.get(0).price);
            assertEquals("2,",restored.rows.get(1).quantity);
            assertEquals("",restored.rows.get(1).price);
            assertEquals("кг",restored.rows.get(1).unit);
            assertEquals("10,",restored.discount);
            assertEquals("2026-12-01",restored.due);
            assertEquals("Запись",restored.note);
            assertNull(drafts.get("b"));
            drafts.clearCommitted("a",other);
            assertNotNull(drafts.get("a"));
            drafts.clearCommitted("a",id);
            assertNull(drafts.get("a"));
            drafts.save("b",new PurchaseDrafts.Draft(other,Collections.singletonList(
                    new PurchaseDrafts.Row("","1","","")),"","",""));
            assertNull(drafts.get("b"));
            drafts.save("a",restored);
            new LedgerDrafts(context,storage).clearAll();
            assertNull(drafts.get("a"));
        }finally{context.getSharedPreferences(storage,0).edit().clear().commit();}
    }
}
