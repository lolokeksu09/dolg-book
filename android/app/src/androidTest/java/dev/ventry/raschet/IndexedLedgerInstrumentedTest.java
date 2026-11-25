package dev.ventry.raschet;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IndexedLedgerInstrumentedTest {
    @Test public void indexedHistoryAndRankMatchWholeBookWithReversals() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String db="raschet-indexed-ledger-qa-only.db";
        context.deleteDatabase(db);
        try(LedgerStore book=new LedgerStore(context,db)){
            book.addClient("A","");
            book.addClient("B","");
            String a=book.clients().get(0).id,b=book.clients().get(1).id;
            LocalDate today=LocalDate.now(), earlier=today.minusDays(3);
            ZoneId zone=ZoneId.systemDefault();
            book.recordDatedDebt(a,"","",10000,earlier,"","");
            book.recordDatedDebt(b,"","",20000,today.minusDays(1),"","");
            book.addPurchase(a,new Purchase(java.util.Arrays.asList(new Purchase.Item("Apples",1500,1000,"кг")),0),"","");
            book.addDraftPayment(a,300,"",UUID.randomUUID().toString(),today.minusDays(1));
            book.reverseLast(a,"test");
            book.addDraftPayment(b,100,"",UUID.randomUUID().toString(),today);
            List<LedgerStore.Entry> all=book.entries();
            List<LedgerStore.Entry> own=book.entriesForClient(a);
            assertEquals(all.stream().filter(e->e.clientId.equals(a)).map(e->e.id).collect(Collectors.toList()),
                         own.stream().map(e->e.id).collect(Collectors.toList()));
            assertEquals(4,own.size());
            assertEquals(11500L,LedgerStore.balance(own,a));
            assertEquals(LedgerStore.balance(all,a),LedgerStore.balance(own,a));
            assertEquals(DatedPayments.maximum(all,a,earlier,today,zone),
                         DatedPayments.maximum(own,a,earlier,today,zone));
            assertEquals(0,book.entriesForClient("missing").size());
            for(LocalDate day:java.util.Arrays.asList(earlier.minusDays(1),earlier,today.minusDays(1),today)){
                int debts=1,payments=1;
                for(BookDays.Day d:BookDays.group(all,zone))if(d.date.isAfter(day))debts++;
                for(BookDays.Day d:BookDays.groupPayments(all,zone))if(d.date.isAfter(day))payments++;
                assertEquals(debts,LedgerStore.journalRank(book.getReadableDatabase(),"debt",day,zone));
                assertEquals(payments,LedgerStore.journalRank(book.getReadableDatabase(),"payment",day,zone));
            }
            book.correctSelected(a,book.lastActive(a).id,1500,"","updated","test");
            assertEquals(11500L,LedgerStore.balance(book.entriesForClient(a),a));
            assertEquals(19900L,LedgerStore.balance(book.entriesForClient(b),b));
        }finally{context.deleteDatabase(db);}
    }
}
