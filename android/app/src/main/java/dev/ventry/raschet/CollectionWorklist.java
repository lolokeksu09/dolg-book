package dev.ventry.raschet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Unique client-level collection queue. Accounting is read-only and never changes contractual due dates. */
final class CollectionWorklist {
    static final int LATE=0,CONTACT=1,SOON=2,LIMIT=3,PLANNED=4,OTHER=5;
    private CollectionWorklist(){}
    static final class Item{
        final CollectionsInsights.CustomerStatus status;
        final ClientDirectory.FollowUp followUp;
        final long limit;
        final int priority;
        final PaymentPromises.Progress promise;
        Item(CollectionsInsights.CustomerStatus s,ClientDirectory.FollowUp f,long l,LocalDate today,PaymentPromises.Progress p){
            status=s;followUp=f;limit=l;promise=p;priority=priority(s,f,l,today,p);
        }
    }
    static int priority(CollectionsInsights.CustomerStatus s,ClientDirectory.FollowUp f,long limit,LocalDate today){return priority(s,f,limit,today,null);}
    static int priority(CollectionsInsights.CustomerStatus s,ClientDirectory.FollowUp f,long limit,LocalDate today,PaymentPromises.Progress promise){
        if(promise!=null&&promise.fulfilled())f=null;
        if(s.overdue>0)return LATE;
        if(f!=null&&!f.date.isAfter(today))return CONTACT;
        if(s.upcoming>0)return SOON;
        if(limit>0&&s.balance>limit)return LIMIT;
        if(f!=null)return PLANNED;
        return OTHER;
    }
    static String title(int priority){
        switch(priority){
            case LATE:return "Просроченные долги";
            case CONTACT:return "Связаться сегодня или раньше";
            case SOON:return "Ближайшие сроки";
            case LIMIT:return "Превышен лимит";
            case PLANNED:return "Запланированные контакты";
            default:return "Остальные должники";
        }
    }
    static List<Item> build(List<CollectionsInsights.CustomerStatus> statuses,ClientDirectory directory,LocalDate today){return build(statuses,directory,today,java.util.Collections.emptyMap());}
    static List<Item> build(List<CollectionsInsights.CustomerStatus> statuses,ClientDirectory directory,LocalDate today,java.util.Map<String,PaymentPromises.Progress> promises){
        List<Item> result=new ArrayList<>();
        for(CollectionsInsights.CustomerStatus s:statuses)
            if(s.balance>0)result.add(new Item(s,directory.followUp(s.client.id),directory.debtLimit(s.client.id),today,promises.get(s.client.id)));
        result.sort(Comparator.comparingInt((Item i)->i.priority)
                .thenComparing(i->i.followUp==null?LocalDate.MAX:i.followUp.date)
                .thenComparing(Comparator.comparingLong((Item i)->i.status.overdue).reversed())
                .thenComparing(Comparator.comparingLong((Item i)->i.status.balance).reversed())
                .thenComparing(i->i.status.client.name,String.CASE_INSENSITIVE_ORDER)
                .thenComparing(i->i.status.client.id));
        return result;
    }
    static List<Item> select(List<Item> all,String query,boolean urgent){
        List<CollectionsInsights.CustomerStatus> statuses=new ArrayList<>();
        for(Item item:all)statuses.add(item.status);
        Set<String> matched=new HashSet<>();
        for(CollectionsInsights.CustomerStatus s:CollectionsInsights.visible(statuses,CollectionsInsights.OWING,false,query))matched.add(s.client.id);
        List<Item> result=new ArrayList<>();
        for(Item item:all)if(matched.contains(item.status.client.id)&&(!urgent||item.priority<=LIMIT))result.add(item);
        return result;
    }
}
