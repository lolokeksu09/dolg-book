package dev.ventry.raschet;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only customer statement. Allocate before filtering; never change financial history. */
final class ClientStatement {
    static final String ALL="Все", UNPAID="Не погашено", DEBTS="Долги", PAYMENTS="Оплаты", REVERSED="Отмены";
    static final String[] FILTERS={ALL,UNPAID,DEBTS,PAYMENTS,REVERSED};
    final List<LedgerStore.Entry> entries;
    final Set<String> canceled,ids=new HashSet<>();
    final Map<String,Long> remaining=new HashMap<>();
    private ClientStatement(List<LedgerStore.Entry> own,ZoneId zone){
        canceled=LedgerStore.canceled(own);
        for(LedgerStore.Entry e:own)ids.add(e.id);
        for(BookDays.Day day:BookDays.group(own,zone))remaining.putAll(day.unpaid);
        entries=HistoryRows.select(own,java.util.Collections.emptyMap(),null,"",null,null,ALL,zone);
    }
    static ClientStatement build(List<LedgerStore.Entry> all,String id,ZoneId zone){
        List<LedgerStore.Entry> own=new ArrayList<>();
        for(LedgerStore.Entry e:all)if(id.equals(e.clientId))own.add(e);
        return new ClientStatement(own,zone);
    }
    List<LedgerStore.Entry> select(String filter){
        List<LedgerStore.Entry> result=new ArrayList<>();
        for(LedgerStore.Entry e:entries){
            if(UNPAID.equals(filter)&&remaining.getOrDefault(e.id,0L)<=0)continue;
            if(DEBTS.equals(filter)&&!"debt".equals(e.type))continue;
            if(PAYMENTS.equals(filter)&&!"payment".equals(e.type))continue;
            if(REVERSED.equals(filter)&&!"reversal".equals(e.type)&&!canceled.contains(e.id))continue;
            result.add(e);
        }
        return result;
    }
}
