package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Journal projections. Allocate payments in insertion order, exactly as CollectionsInsights and LedgerStore.overdue. */
final class BookDays {
    static final class Day {
        final LocalDate date;
        final List<LedgerStore.Entry> entries=new ArrayList<>();
        final Set<String> canceled=new HashSet<>();
        final Map<String,Long> unpaid=new HashMap<>();
        long recorded,remaining;
        Day(LocalDate date){this.date=date;}
        void add(LedgerStore.Entry e,boolean reversed,long rest){
            entries.add(e);unpaid.put(e.id,rest);
            if(reversed)canceled.add(e.id);
            else{recorded+=e.amount;remaining+=rest;}
        }
    }
    static List<Day> group(List<LedgerStore.Entry> entries,ZoneId zone){
        Set<String> canceled=LedgerStore.canceled(entries);
        Map<String,Long> payments=new HashMap<>(),unpaid=new HashMap<>();
        for(LedgerStore.Entry e:entries)
            if("payment".equals(e.type)&&!canceled.contains(e.id))
                payments.put(e.clientId,payments.getOrDefault(e.clientId,0L)+e.amount);
        // Allocate on the complete book before any presentation filter, never by the visible rows.
        for(LedgerStore.Entry e:entries){
            if(!"debt".equals(e.type)||canceled.contains(e.id))continue;
            long available=payments.getOrDefault(e.clientId,0L),applied=Math.min(available,e.amount);
            payments.put(e.clientId,available-applied);unpaid.put(e.id,e.amount-applied);
        }
        Map<LocalDate,Day> grouped=new TreeMap<>(Collections.reverseOrder());
        for(int i=entries.size()-1;i>=0;i--){
            LedgerStore.Entry e=entries.get(i);
            if(!"debt".equals(e.type))continue;
            LocalDate date=PeriodReport.dateOf(e,zone);
            grouped.computeIfAbsent(date,Day::new).add(e,canceled.contains(e.id),unpaid.getOrDefault(e.id,0L));
        }
        return new ArrayList<>(grouped.values());
    }
    static List<Day> select(List<Day> days,Map<String,LedgerStore.Client> clients,
            LocalDate from,LocalDate to,String query,boolean onlyUnpaid){
        String needle=normalize(query);
        String digits=needle.replaceAll("[^0-9]","");
        boolean phoneQuery=!digits.isEmpty()&&needle.matches("[0-9+() .\\-]+");
        List<Day> result=new ArrayList<>();
        for(Day source:days){
            if(from!=null&&source.date.isBefore(from)||to!=null&&source.date.isAfter(to))continue;
            Day visible=new Day(source.date);
            for(LedgerStore.Entry e:source.entries){
                long rest=source.unpaid.getOrDefault(e.id,0L);
                if(onlyUnpaid&&rest==0)continue;
                LedgerStore.Client c=clients.get(e.clientId);
                if(!needle.isEmpty()&&(c==null||!normalize(c.name).contains(needle)
                        &&!normalize(c.phone).contains(needle)
                        &&!(phoneQuery&&c.phone.replaceAll("[^0-9]","").contains(digits))))continue;
                visible.add(e,source.canceled.contains(e.id),rest);
            }
            if(!visible.entries.isEmpty())result.add(visible);
        }
        return result;
    }
    private static String normalize(String value){
        return value==null?"":value.trim().toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");
    }
}
