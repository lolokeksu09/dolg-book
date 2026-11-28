package dev.ventry.raschet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable purchase snapshot; prices and discounts are integer kopecks. */
public final class Purchase {
    static final int MAX_ITEMS=8;
    static final class Item {
        final String name,unit;
        final long quantityMilli,unitPrice,total;
        Item(String name,long quantityMilli,long unitPrice){this(name,quantityMilli,unitPrice,"");}
        Item(String name,long quantityMilli,long unitPrice,String unit){
            if(name==null||name.trim().isEmpty()||name.trim().length()>80
                    ||quantityMilli<=0||quantityMilli>1_000_000L
                    ||unitPrice<=0||unitPrice>LedgerStore.MAX_AMOUNT)
                throw new IllegalArgumentException("Проверьте название, количество (до 1000) и цену.");
            this.name=name.trim();this.quantityMilli=quantityMilli;this.unitPrice=unitPrice;
            if(unit==null||(!unit.isEmpty()&&!Product.validUnit(unit)))throw new IllegalArgumentException("Неверная единица измерения.");
            if("шт".equals(unit)&&quantityMilli%1000!=0)throw new IllegalArgumentException("Штучный товар: только целое количество.");
            this.unit=unit;
            total=BigDecimal.valueOf(quantityMilli,3).multiply(BigDecimal.valueOf(unitPrice))
                    .setScale(0,RoundingMode.HALF_UP).longValueExact();
            if(total<=0||total>LedgerStore.MAX_AMOUNT)
                throw new IllegalArgumentException("Недопустимая сумма позиции.");
        }
        String quantity(){return BigDecimal.valueOf(quantityMilli,3).stripTrailingZeros().toPlainString().replace('.',',');}
        String description(){return name+" · "+quantity()+(unit.isEmpty()?"":" "+unit)+" × "+PeriodReport.rubles(unitPrice)+" ₽ = "+PeriodReport.rubles(total)+" ₽";}
    }
    final List<Item> items;
    final long subtotal,discount,total;
    Purchase(List<Item> items,long discount){
        if(items==null||items.isEmpty()||items.size()>MAX_ITEMS)
            throw new IllegalArgumentException("Добавьте от 1 до 8 позиций.");
        this.items=Collections.unmodifiableList(new ArrayList<>(items));
        long sum=0;
        for(Item item:this.items)sum=Math.addExact(sum,item.total);
        if(sum>LedgerStore.MAX_AMOUNT||discount<0||discount>=sum)
            throw new IllegalArgumentException("Скидка должна быть меньше суммы покупки, итог — положительным.");
        subtotal=sum;this.discount=discount;total=sum-discount;
    }
    static long quantity(String input){
        String v=input.trim().replace(',','.');
        if(!v.matches("\\d{1,4}(\\.\\d{1,3})?"))throw new IllegalArgumentException("Количество: до трёх знаков после запятой.");
        return new BigDecimal(v).movePointRight(3).longValueExact();
    }
    static long discount(String input){
        String v=input.trim().replace(',','.');
        if(v.isEmpty())return 0;
        if(!v.matches("\\d{1,10}(\\.\\d{1,2})?"))throw new IllegalArgumentException("Введите скидку в рублях.");
        return new BigDecimal(v).movePointRight(2).longValueExact();
    }
    boolean hasUnits(){for(Item i:items)if(!i.unit.isEmpty())return true;return false;}
    String encode(){
        try{
            JSONArray rows=new JSONArray();
            for(Item i:items){
                JSONObject item=new JSONObject().put("name",i.name).put("quantityMilli",i.quantityMilli).put("unitPrice",i.unitPrice);
                if(!i.unit.isEmpty())item.put("unit",i.unit);
                rows.put(item);
            }
            return new JSONObject().put("items",rows).put("discount",discount).toString();
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    private static long integer(JSONObject obj,String key) throws org.json.JSONException{
        Object value=obj.get(key);
        if(!(value instanceof Number)||!value.toString().matches("\\d{1,12}"))
            throw new IllegalArgumentException("Неверное целое число в покупке.");
        return Long.parseLong(value.toString());
    }
    static Purchase decode(String raw){
        if(raw.isEmpty())return null;
        try{
            JSONObject data=new JSONObject(raw);
            JSONArray array=data.getJSONArray("items");
            if(array.length()==0||array.length()>MAX_ITEMS)throw new IllegalArgumentException("Недопустимое число товаров.");
            List<Item> rows=new ArrayList<>();
            for(int j=0;j<array.length();j++){
                JSONObject item=array.getJSONObject(j);
                rows.add(new Item(item.getString("name"),integer(item,"quantityMilli"),integer(item,"unitPrice"),item.optString("unit","")));
            }
            return new Purchase(rows,integer(data,"discount"));
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Некорректный состав покупки.",e);}
    }
    String summary(){
        StringBuilder result=new StringBuilder();
        for(Item item:items)result.append(item.description()).append('\n');
        if(discount>0)result.append("Скидка: ").append(PeriodReport.rubles(discount)).append(" ₽\n");
        return result.toString().trim();
    }
}
