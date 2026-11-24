package dev.ventry.raschet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only suggestions from actual active debt entries, newest insertion first. */
final class RecentDebts {
    private RecentDebts(){}
    static Map<String,Long> lastAmounts(List<LedgerStore.Entry> entries){
        Set<String> canceled=LedgerStore.canceled(entries);
        Map<String,Long> result=new LinkedHashMap<>();
        for(int i=entries.size()-1;i>=0;i--){
            LedgerStore.Entry e=entries.get(i);
            if("debt".equals(e.type)&&!canceled.contains(e.id))result.putIfAbsent(e.clientId,e.amount);
        }
        return result;
    }
}
