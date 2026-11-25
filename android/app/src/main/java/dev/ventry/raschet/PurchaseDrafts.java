package dev.ventry.raschet;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Raw, possibly incomplete purchase fields. Shares the restore-cleared operation draft store. */
final class PurchaseDrafts {
    static final class Row {
        final String name,quantity,price,unit;
        Row(String name,String quantity,String price,String unit){
            this.name=limit(name,80);this.quantity=limit(quantity,40);
            this.price=limit(price,40);this.unit=limit(unit,16);
        }
        boolean meaningful(){return !name.trim().isEmpty()||!price.trim().isEmpty()||
                (!quantity.trim().isEmpty()&&!"1".equals(quantity.trim()))||!unit.isEmpty();}
    }
    static final class Draft {
        final String id,discount,due,note;
        final List<Row> rows;
        Draft(String id,List<Row> rows,String discount,String due,String note){
            this.id=UUID.fromString(id).toString();
            if(rows.isEmpty()||rows.size()>Purchase.MAX_ITEMS)throw new IllegalArgumentException("Неверное число позиций.");
            this.rows=Collections.unmodifiableList(new ArrayList<>(rows));
            this.discount=limit(discount,40);this.due=limit(due,12);this.note=limit(note,200);
        }
        boolean meaningful(){
            if(rows.size()>1||!discount.trim().isEmpty()||!due.isEmpty()||!note.trim().isEmpty())return true;
            return rows.get(0).meaningful();
        }
    }
    private final SharedPreferences prefs;
    PurchaseDrafts(Context context){this(context,"raschet_operation_drafts");}
    PurchaseDrafts(Context context,String storage){prefs=context.getSharedPreferences(storage,Context.MODE_PRIVATE);}
    private String key(String client){return "draft:purchase:"+client;}
    Draft get(String client){
        String raw=prefs.getString(key(client),null);
        if(raw==null)return null;
        try{
            JSONObject json=new JSONObject(raw);
            JSONArray values=json.getJSONArray("rows");
            if(values.length()<1||values.length()>Purchase.MAX_ITEMS)return null;
            List<Row> rows=new ArrayList<>();
            for(int i=0;i<values.length();i++){
                JSONObject item=values.getJSONObject(i);
                rows.add(new Row(item.getString("name"),item.getString("quantity"),item.getString("price"),item.getString("unit")));
            }
            Draft draft=new Draft(json.getString("id"),rows,json.getString("discount"),json.getString("due"),json.getString("note"));
            return draft.meaningful()?draft:null;
        }catch(Exception invalid){return null;}
    }
    void save(String client,Draft draft){
        if(!draft.meaningful()){clear(client);return;}
        try{
            JSONArray rows=new JSONArray();
            for(Row r:draft.rows)rows.put(new JSONObject().put("name",r.name).put("quantity",r.quantity)
                    .put("price",r.price).put("unit",r.unit));
            JSONObject json=new JSONObject().put("id",draft.id).put("rows",rows)
                    .put("discount",draft.discount).put("due",draft.due).put("note",draft.note);
            prefs.edit().putString(key(client),json.toString()).apply();
        }catch(org.json.JSONException ex){throw new IllegalArgumentException("Не удалось сохранить черновик покупки.",ex);}
    }
    void clear(String client){prefs.edit().remove(key(client)).apply();}
    void clearCommitted(String client,String id){
        Draft current=get(client);
        if(current!=null&&current.id.equals(id))clear(client);
    }
    private static String limit(String value,int max){return value==null?"":value.substring(0,Math.min(max,value.length()));}
}
