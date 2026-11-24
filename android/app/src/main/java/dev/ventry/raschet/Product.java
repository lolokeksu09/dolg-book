package dev.ventry.raschet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONException;

final class Product {
    static final int MAX_PRODUCTS=3000;
    static final String[] UNITS={"шт","кг","л","упак"};
    final String id,name,category,unit;
    final long price;
    final boolean favorite,archived;
    Product(String id,String name,String category,String unit,long price,boolean favorite,boolean archived){
        if(id==null||id.isEmpty()||id.length()>100||name==null||name.trim().isEmpty()||name.trim().length()>80||
            category==null||category.trim().length()>60||!validUnit(unit)||price<=0||price>LedgerStore.MAX_AMOUNT)
            throw new IllegalArgumentException("Проверьте название, категорию, единицу и цену товара.");
        this.id=id;this.name=name.trim();this.category=category.trim();this.unit=unit;this.price=price;
        this.favorite=favorite;this.archived=archived;
    }
    static Product create(String name,String category,String unit,long price,boolean favorite){
        return new Product(UUID.randomUUID().toString(),name,category,unit,price,favorite,false);
    }
    static boolean validUnit(String unit){
        for(String u:UNITS)if(u.equals(unit))return true;return false;
    }
    static String normalize(String text){return text.trim().toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");}
    String normalizedKey(){return normalize(category)+"\u0000"+normalize(name);}
    boolean sameName(Product other){return normalizedKey().equals(other.normalizedKey());}
    Purchase.Item item(long quantity){return new Purchase.Item(name,quantity,price,unit);}
    static List<Product> filter(List<Product> source,String search,String category,boolean favorites,boolean archive){
        String query=normalize(search);
        List<Product> result=new ArrayList<>();
        for(Product p:source)if(p.archived==archive&&(!favorites||p.favorite)&&
                (category.isEmpty()||p.category.equals(category))&&
                (normalize(p.name).contains(query)||normalize(p.category).contains(query)))result.add(p);
        result.sort(Comparator.comparing((Product p)->!p.favorite).thenComparing(p->normalize(p.name)).thenComparing(p->p.id));
        return result;
    }
    JSONObject json() throws JSONException{
        return new JSONObject().put("id",id).put("name",name).put("category",category).put("unit",unit)
            .put("price",price).put("favorite",favorite).put("archived",archived);
    }
    static Product fromJson(JSONObject j) throws JSONException{
        Object price=j.get("price"),favorite=j.get("favorite"),archived=j.get("archived");
        if(!(price instanceof Number)||!price.toString().matches("\\d{1,12}")||
                !(favorite instanceof Boolean)||!(archived instanceof Boolean))
            throw new IllegalArgumentException("Неверные данные товара.");
        return new Product(j.getString("id"),j.getString("name"),j.getString("category"),j.getString("unit"),
                Long.parseLong(price.toString()),(Boolean)favorite,(Boolean)archived);
    }
}
