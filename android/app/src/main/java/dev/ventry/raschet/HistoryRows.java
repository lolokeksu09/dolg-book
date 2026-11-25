package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only history projection, actual calendar dates descending, stable insertion order within a date. */
final class HistoryRows {
    private HistoryRows(){}
    static List<LedgerStore.Entry> select(List<LedgerStore.Entry> entries,Map<String,LedgerStore.Client> people,
            String clientId,String query,LocalDate from,LocalDate to,String type,ZoneId zone){
        String needle=normalize(query);
        Map<LocalDate,List<LedgerStore.Entry>> days=new TreeMap<>(Collections.reverseOrder());
        Map<String,Boolean> matchingContacts=new HashMap<>();
        for(int i=entries.size()-1;i>=0;i--){
            LedgerStore.Entry e=entries.get(i);
            if(clientId!=null&&!clientId.equals(e.clientId))continue;
            if("Долги".equals(type)&&!"debt".equals(e.type)||"Оплаты".equals(type)&&!"payment".equals(e.type)
                    ||"Отмены".equals(type)&&!"reversal".equals(e.type))continue;
            LocalDate date=PeriodReport.dateOf(e,zone);
            if(from!=null&&date.isBefore(from)||to!=null&&date.isAfter(to))continue;
            if(!needle.isEmpty()){
                boolean contact=matchingContacts.computeIfAbsent(e.clientId,id->{
                    LedgerStore.Client c=people.get(id);
                    return c!=null&&(normalize(c.name).contains(needle)||normalize(c.phone).contains(needle)
                            ||ClientSearch.phoneMatches(c.phone,needle));
                });
                if(!contact&&!normalize(e.note).contains(needle))continue;
            }
            days.computeIfAbsent(date,d->new ArrayList<>()).add(e);
        }
        List<LedgerStore.Entry> result=new ArrayList<>();
        // Reverse insertion order is already established within each calendar day.
        for(List<LedgerStore.Entry> day:days.values())result.addAll(day);
        return result;
    }
    private static String normalize(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");}
}
