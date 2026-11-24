package dev.ventry.raschet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only collection insights. Calculates unpaid amounts by allocating valid
 * payments against active debts oldest-first, matching LedgerStore.overdue().
 * No database migration, write operation, contacts access or network use.
 */
public final class CollectionsInsights {
    private CollectionsInsights() {}

    public static final String ALL = "Все";
    public static final String OWING = "С долгом";
    public static final String LATE = "Просрочка";
    public static final String SOON = "Срок скоро";

    public static final class CustomerStatus {
        public final LedgerStore.Client client;
        public final long balance, overdue, upcoming;
        public final LocalDate nextDue;

        CustomerStatus(LedgerStore.Client client, long balance, long overdue,
                long upcoming, LocalDate nextDue) {
            this.client = client;
            this.balance = balance;
            this.overdue = overdue;
            this.upcoming = upcoming;
            this.nextDue = nextDue;
        }
    }

    public static List<CustomerStatus> analyze(List<LedgerStore.Client> clients,
            List<LedgerStore.Entry> entries, LocalDate today) {
        Set<String> reversed = LedgerStore.canceled(entries);
        Map<String, List<LedgerStore.Entry>> active = new HashMap<>();
        for (LedgerStore.Entry entry : entries) {
            if ("reversal".equals(entry.type) || reversed.contains(entry.id)) continue;
            active.computeIfAbsent(entry.clientId, ignored -> new ArrayList<>()).add(entry);
        }
        List<CustomerStatus> output = new ArrayList<>(clients.size());
        LocalDate windowEnd = today.plusDays(7);
        for (LedgerStore.Client client : clients) {
            List<LedgerStore.Entry> rows = active.get(client.id);
            if (rows == null) rows = java.util.Collections.emptyList();
            long payments = 0L;
            for (LedgerStore.Entry entry : rows)
                if ("payment".equals(entry.type)) payments += entry.amount;
            long balance = 0L, overdue = 0L, upcoming = 0L;
            LocalDate next = null;
            for (LedgerStore.Entry entry : rows) {
                if (!"debt".equals(entry.type)) continue;
                long applied = Math.min(payments, entry.amount);
                payments -= applied;
                long unpaid = entry.amount - applied;
                balance += unpaid;
                if (unpaid == 0 || entry.due.isEmpty()) continue;
                LocalDate due = LocalDate.parse(entry.due);
                if (due.isBefore(today)) {
                    overdue += unpaid;
                } else if (!due.isAfter(windowEnd)) {
                    upcoming += unpaid;
                    if (next == null || due.isBefore(next)) next = due;
                }
            }
            output.add(new CustomerStatus(client, balance, overdue, upcoming, next));
        }
        return output;
    }

    public static List<CustomerStatus> visible(List<CustomerStatus> statuses,
            String filter, boolean amountFirst, String search) {
        String needle=normalize(search);
        String digits=needle.replaceAll("[^0-9]","");
        boolean phoneQuery=!digits.isEmpty()&&needle.matches("[0-9+() .\\-]+");
        List<CustomerStatus> results = new ArrayList<>();
        for (CustomerStatus status : statuses) {
            if (OWING.equals(filter) && status.balance == 0) continue;
            if (LATE.equals(filter) && status.overdue == 0) continue;
            if (SOON.equals(filter) && status.upcoming == 0) continue;
            boolean phoneMatch=phoneQuery&&status.client.phone.replaceAll("[^0-9]","").contains(digits);
            if (!needle.isEmpty()&&!normalize(status.client.name).contains(needle)
                    &&!normalize(status.client.phone).contains(needle)&&!phoneMatch)continue;
            results.add(status);
        }
        Comparator<CustomerStatus> names = Comparator
                .comparing((CustomerStatus item) -> item.client.name,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(item -> item.client.id);
        if (amountFirst) results.sort(
                Comparator.comparingLong((CustomerStatus item) -> item.balance)
                        .reversed().thenComparing(names));
        else results.sort(names);
        return results;
    }
    private static String normalize(String text){
        return text==null?"":text.trim().toLowerCase(Locale.ROOT).replace('ё','е')
                .replaceAll("\\s+"," ");
    }

}
