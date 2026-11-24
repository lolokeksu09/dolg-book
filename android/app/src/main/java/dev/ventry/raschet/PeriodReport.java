package dev.ventry.raschet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only report, reversals take effect on their recorded date. */
final class PeriodReport {
    static final class Totals {
        long debts,payments,debtReversals,paymentReversals;
        int count;
        long net(){return debts-payments-debtReversals+paymentReversals;}
    }
    final LocalDate from,to;
    final long opening,closing;
    final Totals totals;
    final Map<LocalDate,Totals> daily;
    final int activeCustomers;
    private PeriodReport(LocalDate from,LocalDate to,long opening,Totals totals,
                         Map<LocalDate,Totals> daily,int activeCustomers){
        this.from=from;this.to=to;this.opening=opening;
        this.totals=totals;this.closing=opening+totals.net();
        this.daily=daily;this.activeCustomers=activeCustomers;
    }
    static LocalDate dateOf(LedgerStore.Entry entry,ZoneId zone){
        Instant instant;
        try{instant=Instant.parse(entry.date);}
        catch(java.time.format.DateTimeParseException ignored){
            instant=OffsetDateTime.parse(entry.date).toInstant();
        }
        return instant.atZone(zone).toLocalDate();
    }
    private static void apply(Totals t,LedgerStore.Entry e,Map<String,LedgerStore.Entry> earlier){
        if("debt".equals(e.type))t.debts+=e.amount;
        else if("payment".equals(e.type))t.payments+=e.amount;
        else if("reversal".equals(e.type)){
            LedgerStore.Entry original=earlier.get(e.reverses);
            if(original==null||!original.clientId.equals(e.clientId)||original.amount!=e.amount)
                throw new IllegalArgumentException("Нарушена связь отмены.");
            if("debt".equals(original.type))t.debtReversals+=e.amount;
            else if("payment".equals(original.type))t.paymentReversals+=e.amount;
            else throw new IllegalArgumentException("Недопустимый тип отмены.");
        }else throw new IllegalArgumentException("Неизвестная операция.");
        t.count++;
    }
    static PeriodReport build(List<LedgerStore.Entry> entries,LocalDate from,LocalDate to,ZoneId zone){
        if(from.isAfter(to)||to.isAfter(from.plusDays(365)))
            throw new IllegalArgumentException("Выберите период от 1 до 366 дней.");
        Map<String,LedgerStore.Entry> earlier=new HashMap<>();
        Totals before=new Totals(),during=new Totals();
        Map<LocalDate,Totals> daily=new TreeMap<>();
        Set<String> customers=new HashSet<>();
        for(LedgerStore.Entry entry:entries){
            LocalDate date=dateOf(entry,zone);
            if(date.isBefore(from))apply(before,entry,earlier);
            else if(!date.isAfter(to)){
                apply(during,entry,earlier);
                apply(daily.computeIfAbsent(date,d->new Totals()),entry,earlier);
                customers.add(entry.clientId);
            }
            earlier.put(entry.id,entry);
        }
        return new PeriodReport(from,to,before.net(),during,daily,customers.size());
    }
    static String rubles(long cents){
        return java.math.BigDecimal.valueOf(cents,2).toPlainString().replace('.',',');
    }
    /** Aggregates only: no customer identifiers or personal data. */
    String csv(){
        StringBuilder out=new StringBuilder("\uFEFFПериод;"+from+";"+to+"\r\n");
        out.append("Показатель;Сумма (руб)\r\n");
        append(out,"Остаток на начало",opening);
        append(out,"Записано долгов",totals.debts);
        append(out,"Получено оплат",totals.payments);
        append(out,"Отменено долгов",totals.debtReversals);
        append(out,"Отменено оплат",totals.paymentReversals);
        append(out,"Изменение задолженности",totals.net());
        append(out,"Остаток на конец",closing);
        out.append("Операций;").append(totals.count).append("\r\n");
        out.append("Клиентов с операциями;").append(activeCustomers).append("\r\n\r\n");
        out.append("Дата;Долги (руб);Оплаты (руб);Отмены долгов (руб);Отмены оплат (руб);Изменение (руб);Операций\r\n");
        daily.forEach((d,t)->out.append(d).append(';').append(rubles(t.debts)).append(';')
            .append(rubles(t.payments)).append(';').append(rubles(t.debtReversals)).append(';')
            .append(rubles(t.paymentReversals)).append(';').append(rubles(t.net())).append(';')
            .append(t.count).append("\r\n"));
        return out.toString();
    }
    private static void append(StringBuilder out,String label,long cents){
        out.append(label).append(';').append(rubles(cents)).append("\r\n");
    }
}
