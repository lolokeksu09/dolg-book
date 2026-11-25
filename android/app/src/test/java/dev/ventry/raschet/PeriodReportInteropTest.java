package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeriodReportInteropTest {
    @Test public void reportUsesKotlinAndPreservesCsvContract() {
        PeriodReport report=PeriodReport.build(Arrays.asList(
                entry("1","customer-a","debt",10000L,"2026-11-20T09:00:00Z",""),
                entry("2","customer-a","payment",4000L,"2026-11-20T22:30:00Z",""),
                entry("3","customer-b","debt",6000L,"2026-11-22T09:00:00Z",""),
                entry("4","customer-b","reversal",6000L,"2026-11-23T09:00:00Z","3"),
                entry("5","customer-a","reversal",4000L,"2026-11-24T09:00:00Z","2"),
                entry("6","customer-a","payment",1000L,"2026-11-24T10:00:00Z",""),
                entry("7","customer-c","debt",5000L,"2026-11-25T09:00:00Z","")),
                LocalDate.parse("2026-11-21"),LocalDate.parse("2026-11-24"),ZoneId.of("Europe/Moscow"));
        assertEquals(10000L,report.opening);
        assertEquals(9000L,report.closing);
        assertEquals(2,report.activeCustomers);
        assertEquals(5,report.totals.count);
        String expected="\uFEFFПериод;2026-11-21;2026-11-24\r\n"
                +"Показатель;Сумма (руб)\r\n"
                +"Остаток на начало;100,00\r\n"
                +"Записано долгов;60,00\r\n"
                +"Получено оплат;50,00\r\n"
                +"Отменено долгов;60,00\r\n"
                +"Отменено оплат;40,00\r\n"
                +"Изменение задолженности;-10,00\r\n"
                +"Остаток на конец;90,00\r\n"
                +"Операций;5\r\n"
                +"Клиентов с операциями;2\r\n\r\n"
                +"Дата;Долги (руб);Оплаты (руб);Отмены долгов (руб);Отмены оплат (руб);Изменение (руб);Операций\r\n"
                +"2026-11-21;0,00;40,00;0,00;0,00;-40,00;1\r\n"
                +"2026-11-22;60,00;0,00;0,00;0,00;60,00;1\r\n"
                +"2026-11-23;0,00;0,00;60,00;0,00;-60,00;1\r\n"
                +"2026-11-24;0,00;10,00;0,00;40,00;30,00;2\r\n";
        assertEquals(expected,report.csv());
        assertFalse(report.csv().contains("customer-"));
    }

    private static LedgerStore.Entry entry(String id,String client,String type,long amount,String date,String reverses) {
        return new LedgerStore.Entry(id,client,type,amount,date,"","",reverses);
    }
}
