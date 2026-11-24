package dev.ventry.raschet;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local presentation preferences; never removes or changes ledger operations. */
final class ClientDirectory {
    static final String ACTIVE="Рабочие", FAVORITES="Избранные", ARCHIVE="Архив";
    private final SharedPreferences prefs;
    ClientDirectory(Context context){
        this(context,"raschet_client_directory");
    }
    ClientDirectory(Context context,String storage){
        prefs=context.getSharedPreferences(storage,Context.MODE_PRIVATE);
    }
    private Set<String> ids(String key){
        return new HashSet<>(prefs.getStringSet(key,java.util.Collections.emptySet()));
    }
    private void set(String key,String id,boolean value){
        Set<String> items=ids(key);
        if(value)items.add(id);else items.remove(id);
        if(!prefs.edit().putStringSet(key,items).commit())
            throw new IllegalStateException("Не удалось сохранить настройку клиента.");
    }
    boolean favorite(String id){return ids("favorites").contains(id);}
    void favorite(String id,boolean value){set("favorites",id,value);}
    boolean archived(String id,long balance){return balance==0&&ids("archived").contains(id);}
    void archive(String id,long balance,boolean value){
        if(value&&balance!=0)throw new IllegalArgumentException("Сначала погасите долг.");
        set("archived",id,value);
    }
    long debtLimit(String id){return prefs.getLong("debt_limit:"+id,0L);}
    void setDebtLimit(String id,long cents){
        if(id==null||id.isEmpty()||cents<0||cents>LedgerStore.MAX_AMOUNT)
            throw new IllegalArgumentException("Неверный лимит задолженности.");
        SharedPreferences.Editor editor=prefs.edit();
        if(cents==0)editor.remove("debt_limit:"+id);else editor.putLong("debt_limit:"+id,cents);
        if(!editor.commit())throw new IllegalStateException("Не удалось сохранить лимит.");
    }
    static final class FollowUp{
        final java.time.LocalDate date;
        final String note;
        FollowUp(java.time.LocalDate date,String note){this.date=date;this.note=note;}
    }
    FollowUp followUp(String id){
        String day=prefs.getString("follow_up_date:"+id,null);
        if(day==null)return null;
        try{return new FollowUp(java.time.LocalDate.parse(day),prefs.getString("follow_up_note:"+id,""));}
        catch(java.time.format.DateTimeParseException invalid){return null;}
    }
    void setFollowUp(String id,java.time.LocalDate day,String note){
        if(id==null||id.isEmpty()||note==null||note.length()>200)throw new IllegalArgumentException("Комментарий: до 200 знаков.");
        SharedPreferences.Editor editor=prefs.edit();
        if(day==null)editor.remove("follow_up_date:"+id).remove("follow_up_note:"+id);
        else editor.putString("follow_up_date:"+id,day.toString()).putString("follow_up_note:"+id,note.trim());
        if(!editor.commit())throw new IllegalStateException("Не удалось сохранить план возврата.");
    }
    void clearAfterRestore(){prefs.edit().clear().apply();}
    List<CollectionsInsights.CustomerStatus> select(List<CollectionsInsights.CustomerStatus> source,String scope){
        Set<String> archived=ids("archived"),favorites=ids("favorites");
        List<CollectionsInsights.CustomerStatus> result=new ArrayList<>();
        for(CollectionsInsights.CustomerStatus item:source){
            boolean hidden=item.balance==0&&archived.contains(item.client.id);
            if(ARCHIVE.equals(scope)?hidden:!hidden&&(!FAVORITES.equals(scope)||favorites.contains(item.client.id)))
                result.add(item);
        }
        return result;
    }
}
