package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only history projection, actual calendar dates descending, stable insertion order within a date. */
final class HistoryRows {
    private HistoryRows(){}
    static List<LedgerStore.Entry> select(List<LedgerStore.Entry> entries,Map<String,LedgerStore.Client> people,
            String clientId,String query,LocalDate from,LocalDate to,String type,ZoneId zone){
        String needle=normalize(query);
        String digits=needle.replaceAll("[^0-9]","");
        boolean phoneQuery=!digits.isEmpty()&&needle.matches("[+0-9() .\\-]+");
        List<LedgerStore.Entry> result=new ArrayList<>();
        for(int i=entries.size()-1;i>=0;i--){
            LedgerStore.Entry e=entries.get(i);
            if(clientId!=null&&!clientId.equals(e.clientId))continue;
            if("Долги".equals(type)&&!"debt".equals(e.type)||"Оплаты".equals(type)&&!"payment".equals(e.type)
                    ||"Отмены".equals(type)&&!"reversal".equals(e.type))continue;
            LocalDate date=PeriodReport.dateOf(e,zone);
            if(from!=null&&date.isBefore(from)||to!=null&&date.isAfter(to))continue;
            LedgerStore.Client c=people.get(e.clientId);
            boolean contact=c!=null&&(normalize(c.name).contains(needle)||normalize(c.phone).contains(needle)
                    ||phoneQuery&&c.phone.replaceAll("[^0-9]","").contains(digits));
            if(!needle.isEmpty()&&!contact&&!normalize(e.note).contains(needle))continue;
            result.add(e);
        }
        result.sort(Comparator.comparing((LedgerStore.Entry e)->PeriodReport.dateOf(e,zone)).reversed());
        return result;
    }
    private static String normalize(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");}
}
