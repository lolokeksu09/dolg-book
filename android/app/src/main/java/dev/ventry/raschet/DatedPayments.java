package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Maximum backdated receipt without a negative end-of-day balance in any subsequent recorded day. */
final class DatedPayments {
    private DatedPayments(){}
    static long maximum(List<LedgerStore.Entry> entries,String id,LocalDate day,LocalDate today,ZoneId zone){
        if(day==null||day.isAfter(today))return 0L;
        long current=Math.max(0L,LedgerStore.balance(entries,id));
        if(day.equals(today))return current;
        TreeMap<LocalDate,Long> changes=new TreeMap<>();
        Map<String,LedgerStore.Entry> earlier=new HashMap<>();
        for(LedgerStore.Entry e:entries){
            if(!id.equals(e.clientId))continue;
            long delta;
            if("debt".equals(e.type))delta=e.amount;
            else if("payment".equals(e.type))delta=-e.amount;
            else if("reversal".equals(e.type)){
                LedgerStore.Entry source=earlier.get(e.reverses);
                if(source==null)throw new IllegalArgumentException("Нарушена связь отмены.");
                delta="debt".equals(source.type)?-e.amount:e.amount;
            }else throw new IllegalArgumentException("Неизвестная операция.");
            changes.merge(PeriodReport.dateOf(e,zone),delta,Long::sum);
            earlier.put(e.id,e);
        }
        // Include the selected day even if it originally had no operation.
        changes.putIfAbsent(day,0L);
        long running=0L,minimum=current;
        for(Map.Entry<LocalDate,Long> change:changes.entrySet()){
            if(change.getKey().isAfter(today))break;
            running+=change.getValue();
            if(!change.getKey().isBefore(day))minimum=Math.min(minimum,running);
        }
        return Math.max(0L,minimum);
    }
}
