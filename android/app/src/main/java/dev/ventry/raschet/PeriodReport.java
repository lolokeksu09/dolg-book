package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
        return dev.ventry.raschet.domain.DatedLedger.localDate(entry.date,zone);
    }
    private static Totals adapt(dev.ventry.raschet.domain.ReportProjection.Totals source){
        Totals totals=new Totals();
        totals.debts=source.getDebts();
        totals.payments=source.getPayments();
        totals.debtReversals=source.getDebtReversals();
        totals.paymentReversals=source.getPaymentReversals();
        totals.count=source.getCount();
        return totals;
    }
    static PeriodReport build(List<LedgerStore.Entry> entries,LocalDate from,LocalDate to,ZoneId zone){
        dev.ventry.raschet.domain.ReportProjection.Report report=
                dev.ventry.raschet.domain.ReportProjection.build(entries,from,to,zone);
        Map<LocalDate,Totals> daily=new TreeMap<>();
        report.getDaily().forEach((date,totals)->daily.put(date,adapt(totals)));
        return new PeriodReport(from,to,report.getOpening(),adapt(report.getTotals()),daily,report.getActiveCustomers());
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
