package dev.ventry.raschet;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProductInstrumentedTest {
    @Test public void catalogRoundtripAndPriceChangeDoNotRewritePurchase() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="product-catalog-qa-only.db";
        context.deleteDatabase(db);
        try(LedgerStore book=new LedgerStore(context,db)){
            Product apple=Product.create("Алёнка","Продукты","кг",15000,true);
            book.saveProduct(apple);
            book.addClient("QA","");
            String id=book.clients().get(0).id;
            book.addPurchase(id,new Purchase(Collections.singletonList(apple.item(1500)),0),"","");
            book.saveProduct(new Product(apple.id,apple.name,apple.category,apple.unit,20000,true,false));
            assertEquals(22500,LedgerStore.balance(book.entries(),id));
            assertEquals(15000,book.entries().get(0).purchase.items.get(0).unitPrice);
            assertEquals("кг",book.entries().get(0).purchase.items.get(0).unit);
            assertEquals(1,Product.filter(book.products(),"аленка","Продукты",true,false).size());
            String snapshot=book.exportJson();
            assertEquals(4,new JSONObject(snapshot).getInt("version"));
            book.importJson(snapshot);
            assertEquals(snapshot,book.exportJson());
            assertEquals(20000,book.products().get(0).price);
            JSONObject bad=new JSONObject(snapshot);
            bad.getJSONArray("products").getJSONObject(0).put("price",-10);
            try{book.importJson(bad.toString());fail("invalid product");}
            catch(IllegalArgumentException expected){}
            assertEquals(snapshot,book.exportJson());
            try{book.saveProduct(Product.create("аленка","Продукты","кг",100,true));fail("duplicate");}
            catch(IllegalArgumentException expected){}
            book.saveProduct(new Product(apple.id,apple.name,apple.category,apple.unit,20000,true,true));
            assertTrue(Product.filter(book.products(),"","",false,false).isEmpty());
            assertEquals(1,Product.filter(book.products(),"","",false,true).size());
            assertEquals(22500,LedgerStore.balance(book.entries(),id));
        }finally{context.deleteDatabase(db);}
    }
    @Test public void wholeUnitsRejectFractionalQuantity(){
        Product p=Product.create("Хлеб","","шт",6000,false);
        try{p.item(1500);fail("fractional pieces");}
        catch(IllegalArgumentException expected){}
        assertEquals(12000,p.item(2000).total);
    }
    @Test public void migrateVersionTwoPreservesPurchase(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="catalog-v2-migration-qa-only.db";
        context.deleteDatabase(db);
        try{
            try(LedgerStore book=new LedgerStore(context,db)){
                book.addClient("QA","");
                book.addPurchase(book.clients().get(0).id,new Purchase(Collections.singletonList(
                        new Purchase.Item("Старый товар",1000,12345)),0),"","");
                SQLiteDatabase sql=book.getWritableDatabase();
                sql.execSQL("DROP TABLE products");
                sql.setVersion(2);
            }
            try(LedgerStore book=new LedgerStore(context,db)){
                assertEquals(12345,book.entries().get(0).purchase.total);
                assertTrue(book.products().isEmpty());
                assertEquals(3,book.getReadableDatabase().getVersion());
            }
        }finally{context.deleteDatabase(db);}
    }
}
