package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Day journal shows recorded debt, not unpaid balance. Payments never erase original records. */
final class BookDays {
    static final class Day {
        final LocalDate date;
        final List<LedgerStore.Entry> entries=new ArrayList<>();
        final Set<String> canceled=new HashSet<>();
        long recorded;
        Day(LocalDate date){this.date=date;}
    }
    static List<Day> group(List<LedgerStore.Entry> entries,ZoneId zone){
        Map<LocalDate,Day> grouped=new TreeMap<>(Collections.reverseOrder());
        Set<String> canceled=LedgerStore.canceled(entries);
        for(int i=entries.size()-1;i>=0;i--){
            LedgerStore.Entry e=entries.get(i);
            if(!"debt".equals(e.type))continue;
            LocalDate date=PeriodReport.dateOf(e,zone);
            Day day=grouped.computeIfAbsent(date,Day::new);
            day.entries.add(e);
            if(canceled.contains(e.id))day.canceled.add(e.id);else day.recorded+=e.amount;
        }
        return new ArrayList<>(grouped.values());
    }
}
