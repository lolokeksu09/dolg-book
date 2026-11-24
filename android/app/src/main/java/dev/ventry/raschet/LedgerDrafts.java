package dev.ventry.raschet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.UUID;

/** Per-client drafts with stable save identity and original debt date. */
final class LedgerDrafts {
    static final class Draft {
        final String id,amount,due,note;
        final LocalDate day;
        Draft(String id,String amount,String due,String note,LocalDate day){
            this.id=id;this.amount=amount;this.due=due;this.note=note;this.day=day;
        }
    }
    private final SharedPreferences prefs;
    LedgerDrafts(Context app){this(app,"raschet_operation_drafts");}
    LedgerDrafts(Context app,String storage){prefs=app.getSharedPreferences(storage,Context.MODE_PRIVATE);}
    private String key(String clientId,String type){
        if(clientId==null||(!"debt".equals(type)&&!"payment".equals(type)))
            throw new IllegalArgumentException("Неверный тип черновика.");
        return "draft:"+type+":"+clientId;
    }
    boolean has(String clientId,String type){return get(clientId,type)!=null;}
    Draft get(String clientId,String type){
        String raw=prefs.getString(key(clientId,type),null);
        if(raw==null)return null;
        try{
            JSONObject json=new JSONObject(raw);
            String amount=json.optString("amount",""),due=json.optString("due",""),note=json.optString("note","");
            if(amount.trim().isEmpty()&&due.isEmpty()&&note.trim().isEmpty()){clear(clientId,type);return null;}
            LocalDate day;
            try{day=LocalDate.parse(json.optString("day",""));}catch(Exception e){day=LocalDate.now();}
            String id;
            try{id=UUID.fromString(json.optString("id","")).toString();}
            catch(Exception e){
                id=UUID.randomUUID().toString();
                // Upgrade old local drafts when first opened; keep the same ID on future resumes.
                save(clientId,type,id,day,amount,due,note);
            }
            return new Draft(id,amount,due,note,day);
        }catch(Exception e){return null;}
    }
    void save(String clientId,String type,String id,LocalDate day,String amount,String due,String note){
        if(amount.trim().isEmpty()&&due.isEmpty()&&note.trim().isEmpty()){clear(clientId,type);return;}
        try{
            JSONObject json=new JSONObject();
            json.put("id",UUID.fromString(id).toString());
            json.put("day",day.toString());
            json.put("amount",limit(amount,40));
            json.put("due",limit(due,12));
            json.put("note",limit(note,200));
            prefs.edit().putString(key(clientId,type),json.toString()).apply();
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Не удалось сохранить черновик.",e);}
    }
    void clear(String clientId,String type){prefs.edit().remove(key(clientId,type)).apply();}
    void clearAll(){prefs.edit().clear().apply();}
    private static String limit(String s,int maximum){
        if(s==null)return "";
        return s.length()<=maximum?s:s.substring(0,maximum);
    }
}
