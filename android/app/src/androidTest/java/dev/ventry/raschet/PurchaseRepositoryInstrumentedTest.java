package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ventry.raschet.data.CatalogRecord;
import dev.ventry.raschet.data.ClientCommand;
import dev.ventry.raschet.data.SqliteLedgerRepository;
import dev.ventry.raschet.data.WriteDestination;
import dev.ventry.raschet.domain.DebtApproval;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PurchaseRepositoryInstrumentedTest {
    @Test public void purchaseSnapshotLimitAndReplay() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="raschet-purchase-repository-qa-only.db";
        context.deleteDatabase(db);
        try (LedgerStore book=new LedgerStore(context,db)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            book.addClient("Synthetic purchase","");
            String client=book.clients().get(0).id;
            ClientDirectory directory=new ClientDirectory(context);
            try {
                directory.setDebtLimit(client,1000L);
                Purchase purchase=new Purchase(Arrays.asList(new Purchase.Item("Apples",1500,1000,"кг")),100);
                String id=UUID.randomUUID().toString();
                String before=book.exportJson();
                rejected(()->repository.savePurchase(new ClientCommand.ItemizedDebt(id,client,purchase.encode(),"","",""),context));
                rejected(()->repository.savePurchase(new ClientCommand.ItemizedDebt(id,client,purchase.encode(),"","",DebtApproval.key(client,1L,1000L,1400L)),context));
                assertEquals(before,book.exportJson());
                ClientCommand.ItemizedDebt approved=new ClientCommand.ItemizedDebt(id,client,purchase.encode(),"","",DebtApproval.key(client,0L,1000L,1400L));
                assertEquals(WriteDestination.DETAIL,repository.savePurchase(approved,context).getDestination());
                assertEquals(1400L,LedgerStore.balance(book.entries(),client));
                assertEquals(purchase.encode(),book.entries().get(0).purchase.encode());
                assertEquals(id,book.entries().get(0).id);
                String committed=book.exportJson();
                rejected(()->repository.savePurchase(approved,context));
                rejected(()->repository.savePurchase(new ClientCommand.ItemizedDebt(UUID.randomUUID().toString(),client,"{}","","",""),context));
                assertEquals(committed,book.exportJson());
            } finally {
                directory.setDebtLimit(client,0L);
            }
        } finally { context.deleteDatabase(db); }
    }

    @Test public void catalogRepositoryPreservesExistingBackupAndDuplicateRules() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="raschet-catalog-repository-qa-only.db";
        context.deleteDatabase(db);
        try (LedgerStore book=new LedgerStore(context,db)) {
            SqliteLedgerRepository repository=new SqliteLedgerRepository(book);
            CatalogRecord first=new CatalogRecord("item-a","Apples","Fruit","кг",1000,false,false);
            repository.saveCatalogProduct(first);
            assertEquals(first,repository.catalog().get(0));
            String before=book.exportJson();
            rejected(()->repository.saveCatalogProduct(new CatalogRecord("item-b"," apples ","fruit","кг",1200,false,false)));
            assertEquals(before,book.exportJson());
            repository.saveCatalogProduct(new CatalogRecord("item-a","Apples","Fruit","кг",1500,true,false));
            assertEquals(1500L,repository.catalog().get(0).getPrice());
            String backup=book.exportJson();
            book.importJson(backup);
            assertEquals(backup,book.exportJson());
            assertEquals(1,repository.catalog().size());
        } finally { context.deleteDatabase(db); }
    }

    private static void rejected(Runnable action){
        try { action.run(); fail("Invalid command accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
