package dev.ventry.raschet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.ventry.raschet.data.CatalogRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CatalogPickerInstrumentedTest {
    private CatalogRecord record(String id,String name,String category,boolean favorite,boolean archived){
        return new CatalogRecord(id,name,category,"кг",10000L,favorite,archived);
    }
    @Test public void searchRanksExactAndPrefixMatchesAndExcludesArchived(){
        CatalogPicker picker=new CatalogPicker(Arrays.asList(
                record("1","Яблоки","Фрукты",false,false),
                record("2","Яблоки зелёные","Фрукты",true,false),
                record("3","Груши","Фрукты",true,false),
                record("4","Яблоки","Архив",true,true)));
        assertEquals("1",picker.search("ЯБЛОКИ",false).rows.get(0).getId());
        assertEquals("2",picker.search("зеленые фрукты",false).rows.get(0).getId());
        assertEquals(2,picker.search("фрукты",true).total);
        assertEquals(3,picker.search("",false).total);
        assertEquals(0,picker.search("Архив",false).total);
    }
    @Test public void fullCatalogReturnsBoundedResultsAndKeepsAllSearchable(){
        List<CatalogRecord> records=new ArrayList<>();
        for(int i=0;i<3000;i++)records.add(record("id"+i,"Товар "+i,"Овощи",i==2999,false));
        CatalogPicker picker=new CatalogPicker(records);
        CatalogPicker.Result first=picker.search("",false);
        assertEquals(3000,first.total);
        assertEquals(CatalogPicker.LIMIT,first.rows.size());
        assertEquals("id2999",first.rows.get(0).getId());
        CatalogPicker.Result match=picker.search("товар 2999",false);
        assertEquals(1,match.total);
        assertEquals("id2999",match.rows.get(0).getId());
    }
}
