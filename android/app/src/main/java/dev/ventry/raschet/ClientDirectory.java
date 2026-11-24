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
