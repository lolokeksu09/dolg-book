package dev.ventry.raschet;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only progress from active payment entries inserted after a saved entry ID. */
final class PaymentPromises {
    private PaymentPromises(){}
    static final class Progress{
        final long target,paid,onTime;
        final boolean valid,missed;
        Progress(long target,long paid,long onTime,boolean valid,boolean missed){
            this.target=target;this.paid=paid;this.onTime=onTime;this.valid=valid;this.missed=missed;
        }
        boolean fulfilled(){return valid&&paid>=target;}
    }
    static String lastEntryId(List<LedgerStore.Entry> entries,String clientId){
        for(int i=entries.size()-1;i>=0;i--)if(clientId.equals(entries.get(i).clientId))return entries.get(i).id;
        return "";
    }
    static Progress calculate(List<LedgerStore.Entry> entries,String clientId,ClientDirectory.FollowUp plan,LocalDate today,ZoneId zone){
        return calculate(entries,clientId,plan,today,zone,LedgerStore.canceled(entries));
    }
    private static Progress calculate(List<LedgerStore.Entry> entries,String clientId,ClientDirectory.FollowUp plan,LocalDate today,ZoneId zone,Set<String> canceled){
        boolean found=false;long paid=0,onTime=0;
        for(LedgerStore.Entry e:entries){
            if(!clientId.equals(e.clientId))continue;
            if(!found){if(e.id.equals(plan.anchor))found=true;continue;}
            if(!"payment".equals(e.type)||canceled.contains(e.id))continue;
            paid+=e.amount;
            if(!PeriodReport.dateOf(e,zone).isAfter(plan.date))onTime+=e.amount;
        }
        return new Progress(plan.amount,paid,onTime,found,found&&paid<plan.amount&&today.isAfter(plan.date));
    }
    static Map<String,Progress> project(List<CollectionsInsights.CustomerStatus> statuses,ClientDirectory directory,List<LedgerStore.Entry> entries,LocalDate today,ZoneId zone){
        Map<String,ClientDirectory.FollowUp> plans=new HashMap<>();
        for(CollectionsInsights.CustomerStatus s:statuses){ClientDirectory.FollowUp plan=directory.followUp(s.client.id);if(plan!=null)plans.put(s.client.id,plan);}
        return project(statuses,plans,entries,today,zone);
    }
    static Map<String,Progress> project(List<CollectionsInsights.CustomerStatus> statuses,Map<String,ClientDirectory.FollowUp> plans,List<LedgerStore.Entry> entries,LocalDate today,ZoneId zone){
        Map<String,List<LedgerStore.Entry>> byClient=new HashMap<>();
        for(LedgerStore.Entry e:entries)byClient.computeIfAbsent(e.clientId,k->new ArrayList<>()).add(e);
        Set<String> canceled=LedgerStore.canceled(entries);
        Map<String,Progress> result=new HashMap<>();
        for(CollectionsInsights.CustomerStatus s:statuses){
            ClientDirectory.FollowUp plan=plans.get(s.client.id);
            if(plan!=null&&plan.amount>0)result.put(s.client.id,calculate(byClient.getOrDefault(s.client.id,java.util.Collections.emptyList()),s.client.id,plan,today,zone,canceled));
        }
        return result;
    }
}
