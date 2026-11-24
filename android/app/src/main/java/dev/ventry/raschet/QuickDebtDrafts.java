package dev.ventry.raschet;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.UUID;

/** One unsaved quick-debt form, private to the device. No approval or selected customer ID is restored. */
final class QuickDebtDrafts {
    static final class Draft {
        final String id,name,phone,amount,due,note;
        final boolean fromClients;
        final LocalDate day;
        Draft(String id,boolean fromClients,String name,String phone,String amount,LocalDate day,String due,String note){
            this.id=UUID.fromString(id).toString();this.fromClients=fromClients;this.name=name;this.phone=phone;
            this.amount=amount;this.day=day;this.due=due;this.note=note;
        }
        boolean isEmpty(){return name.trim().isEmpty()&&phone.trim().isEmpty()&&amount.trim().isEmpty()&&due.isEmpty()&&note.trim().isEmpty();}
    }
    private final SharedPreferences prefs;
    QuickDebtDrafts(Context context){this(context,"raschet_quick_debt_draft");}
    QuickDebtDrafts(Context context,String storage){prefs=context.getSharedPreferences(storage,Context.MODE_PRIVATE);}
    Draft get(){
        String raw=prefs.getString("draft",null);
        if(raw==null)return null;
        try{
            JSONObject j=new JSONObject(raw);
            Draft d=new Draft(j.getString("id"),j.getBoolean("fromClients"),j.getString("name"),j.getString("phone"),
                    j.getString("amount"),LocalDate.parse(j.getString("day")),j.getString("due"),j.getString("note"));
            return d.isEmpty()?null:d;
        }catch(Exception invalid){return null;}
    }
    void save(Draft d){
        if(d.isEmpty()){clear();return;}
        try{
            JSONObject j=new JSONObject().put("id",d.id).put("fromClients",d.fromClients)
                    .put("name",d.name).put("phone",d.phone).put("amount",d.amount)
                    .put("day",d.day.toString()).put("due",d.due).put("note",d.note);
            prefs.edit().putString("draft",j.toString()).apply();
        }catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
    }
    void clear(){prefs.edit().remove("draft").apply();}
}
