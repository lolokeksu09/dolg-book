package dev.ventry.raschet;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Original multi-page customer statement. No contact or network permissions. */
public final class CustomerStatementPdf {
    private static final int PAGE_WIDTH=595, PAGE_HEIGHT=842, MAX_LINES=2000;
    private CustomerStatementPdf(){}

    public static void write(OutputStream output,String shop,LedgerStore.Client client,
            List<LedgerStore.Entry> all) throws IOException {
        List<LedgerStore.Entry> entries=new ArrayList<>();
        for(LedgerStore.Entry e:all)if(e.clientId.equals(client.id))entries.add(e);
        if(entries.size()>MAX_LINES)throw new IOException(
                "Слишком много операций для PDF. Сохраните полную CSV-копию.");
        Set<String> canceled=LedgerStore.canceled(all);
        PdfDocument document=new PdfDocument();
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        int page=0,offset=0;
        try {
            do {
                PdfDocument.Page sheet=document.startPage(
                        new PdfDocument.PageInfo.Builder(PAGE_WIDTH,PAGE_HEIGHT,page+1).create());
                Canvas canvas=sheet.getCanvas();
                canvas.drawColor(Color.WHITE);
                paint.setColor(Color.rgb(15,43,30));
                paint.setTextSize(20);
                paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                canvas.drawText(fit(shop,paint,490),42,54,paint);
                paint.setTextSize(16);
                canvas.drawText("Выписка клиента",42,82,paint);
                paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
                paint.setTextSize(12);
                canvas.drawText(fit(client.name,paint,500),42,105,paint);
                paint.setColor(Color.DKGRAY);
                canvas.drawText("Дата: "+LocalDate.now(),42,126,paint);
                long balance=LedgerStore.balance(all,client.id);
                long overdue=LedgerStore.overdue(all,client.id,LocalDate.now());
                canvas.drawText("Остаток: "+rubles(balance)+" ₽   |   Просрочка: "+
                        rubles(overdue)+" ₽",42,148,paint);
                paint.setColor(Color.rgb(210,217,211));
                canvas.drawLine(42,162,552,162,paint);
                paint.setTextSize(10);
                paint.setColor(Color.GRAY);
                canvas.drawText("Дата / Операция",42,183,paint);
                canvas.drawText("Сумма",465,183,paint);
                int y=207,rows=0;
                while(offset<entries.size() && y<=752){
                    LedgerStore.Entry entry=entries.get(offset++);
                    boolean canceledEntry=canceled.contains(entry.id);
                    String type="debt".equals(entry.type)?"Долг":
                            "payment".equals(entry.type)?"Оплата":"Отмена";
                    String date=entry.date.length()>=10?entry.date.substring(0,10):entry.date;
                    String line=date+" · "+type+(canceledEntry?" (отменено)":"");
                    paint.setColor(canceledEntry?Color.GRAY:Color.rgb(23,38,30));
                    paint.setTextSize(11);
                    canvas.drawText(fit(line,paint,390),42,y,paint);
                    paint.setColor(Color.DKGRAY);
                    canvas.drawText(("payment".equals(entry.type)?"−":"debt".equals(entry.type)?"+":"↶ ")
                            +rubles(entry.amount),465,y,paint);
                    y+=18;
                    if(!entry.note.isEmpty()){
                        paint.setColor(Color.GRAY);
                        paint.setTextSize(10);
                        canvas.drawText(fit(entry.note,paint,485),52,y,paint);
                        y+=17;
                    }
                    rows++;
                }
                if(entries.isEmpty() && page==0){
                    paint.setColor(Color.GRAY);paint.setTextSize(12);
                    canvas.drawText("Операций пока нет.",42,225,paint);
                }
                paint.setTextSize(10);paint.setColor(Color.GRAY);
                canvas.drawText("Расчёт · Ventry   ·   Страница "+(page+1),42,810,paint);
                document.finishPage(sheet);
                page++;
                if(page>150)throw new IOException("Слишком длинная PDF-выписка.");
            }while(offset<entries.size());
            document.writeTo(output);
        }finally{
            document.close();
        }
    }
    private static String fit(String text,Paint paint,float width){
        if(paint.measureText(text)<=width)return text;
        String suffix="…";
        int end=text.length();
        while(end>0&&paint.measureText(text.substring(0,end)+suffix)>width)end--;
        return text.substring(0,end)+suffix;
    }
    private static String rubles(long kopecks){
        return String.format(new Locale("ru","RU"),"%,.2f",kopecks/100d);
    }
}
