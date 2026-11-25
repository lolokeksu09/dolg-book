package dev.ventry.raschet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only collection insights. Calculates unpaid amounts by allocating valid
 * payments against active debts oldest-first, matching LedgerStore.overdue().
 * No database migration, write operation, contacts access or network use.
 */
public final class CollectionsInsights {
    private CollectionsInsights() {}

    public static final String ALL = "Все";
    public static final String OWING = "С долгом";
    public static final String CLEAR = "Без долга";
    public static final String LATE = "Просрочка";
    public static final String SOON = "Срок скоро";

    public static final class CustomerStatus {
        public final LedgerStore.Client client;
        public final long balance, overdue, upcoming;
        public final LocalDate nextDue;
        final int lastOperationIndex;

        CustomerStatus(LedgerStore.Client client, long balance, long overdue,
                long upcoming, LocalDate nextDue) {
            this(client,balance,overdue,upcoming,nextDue,-1);
        }
        CustomerStatus(LedgerStore.Client client, long balance, long overdue,
                long upcoming, LocalDate nextDue,int lastOperationIndex) {
            this.lastOperationIndex=lastOperationIndex;
            this.client = client;
            this.balance = balance;
            this.overdue = overdue;
            this.upcoming = upcoming;
            this.nextDue = nextDue;
        }
    }

    public static List<CustomerStatus> analyze(List<LedgerStore.Client> clients,
            List<LedgerStore.Entry> entries, LocalDate today) {
        Map<String, dev.ventry.raschet.domain.CollectionProjection.Status> projections =
                dev.ventry.raschet.domain.CollectionProjection.analyze(entries,today);
        List<CustomerStatus> output = new ArrayList<>(clients.size());
        for (LedgerStore.Client client : clients) {
            dev.ventry.raschet.domain.CollectionProjection.Status status =
                    projections.getOrDefault(client.id, dev.ventry.raschet.domain.CollectionProjection.EMPTY);
            output.add(new CustomerStatus(client,status.getBalance(),status.getOverdue(),
                    status.getUpcoming(),status.getNextDue(),status.getLastOperationIndex()));
        }
        return output;
    }

    public static List<CustomerStatus> visible(List<CustomerStatus> statuses,
            String filter, boolean amountFirst, String search) {
        return visible(statuses,filter,amountFirst,false,search);
    }
    public static List<CustomerStatus> visible(List<CustomerStatus> statuses,
            String filter,boolean amountFirst,boolean recentFirst,String search) {
        String needle=normalize(search);
        List<CustomerStatus> results = new ArrayList<>();
        for (CustomerStatus status : statuses) {
            if (OWING.equals(filter) && status.balance == 0) continue;
            if (CLEAR.equals(filter) && status.balance != 0) continue;
            if (LATE.equals(filter) && status.overdue == 0) continue;
            if (SOON.equals(filter) && status.upcoming == 0) continue;
            boolean phoneMatch=ClientSearch.phoneMatches(status.client.phone,needle);
            if (!needle.isEmpty()&&!normalize(status.client.name).contains(needle)
                    &&!normalize(status.client.phone).contains(needle)&&!phoneMatch)continue;
            results.add(status);
        }
        Comparator<CustomerStatus> names = Comparator
                .comparing((CustomerStatus item) -> item.client.name,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(item -> item.client.id);
        if(recentFirst)results.sort(Comparator.comparingInt((CustomerStatus item)->item.lastOperationIndex).reversed().thenComparing(names));
        else if (amountFirst) results.sort(
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
