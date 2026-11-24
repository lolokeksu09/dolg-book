package dev.ventry.raschet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Per-client, per-operation form drafts stay in app-private storage only. */
final class LedgerDrafts {
    static final class Draft {
        final String amount, due, note;
        Draft(String amount,String due,String note){
            this.amount=amount;this.due=due;this.note=note;
        }
    }
    private final SharedPreferences prefs;
    LedgerDrafts(Context app){prefs=app.getSharedPreferences("raschet_operation_drafts",Context.MODE_PRIVATE);}
    private String key(String clientId,String type){
        if(clientId==null||(!"debt".equals(type)&&!"payment".equals(type)))
            throw new IllegalArgumentException("Неверный тип черновика.");
        return "draft:"+type+":"+clientId;
    }
    boolean has(String clientId,String type){return prefs.contains(key(clientId,type));}
    Draft get(String clientId,String type){
        String value=prefs.getString(key(clientId,type),null);
        if(value==null)return new Draft("","","");
        try{
            JSONObject json=new JSONObject(value);
            return new Draft(json.optString("amount",""),json.optString("due",""),json.optString("note",""));
        }catch(Exception e){return new Draft("","","");}
    }
    void save(String clientId,String type,String amount,String due,String note){
        try{
            JSONObject json=new JSONObject();
            json.put("amount",limit(amount,40));
            json.put("due",limit(due,12));
            json.put("note",limit(note,200));
            prefs.edit().putString(key(clientId,type),json.toString()).apply();
        }catch(Exception e){throw new IllegalArgumentException("Не удалось сохранить черновик.",e);}
    }
    void clear(String clientId,String type){prefs.edit().remove(key(clientId,type)).apply();}
    private static String limit(String s,int maximum){
        if(s==null)return "";
        return s.length()<=maximum?s:s.substring(0,maximum);
    }
}
