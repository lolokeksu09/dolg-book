package dev.ventry.raschet;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Native Android debt ledger, with platform views and SQLite persistence. */
public final class MainActivity extends Activity {
    private static final int BG=0xff090e0c, PANEL=0xff141c18, ELEVATED=0xff1b2820;
    private static final int BORDER=0xff2b3b32, TEXT=0xfff4f7f3, MUTED=0xff9eb4a6;
    private static final int ACCENT=0xffb9efd0, INK=0xff12271b, ALERT=0xffffca97, RED=0xffffacac;
    private static final int REQUEST_IMPORT=101, REQUEST_EXPORT=102, MAX_IMPORT=5*1024*1024;
    public static final String EXTRA_FIRST_START = "dev.ventry.raschet.FIRST_START";
    private static final String[] TABS={"Главная","Клиенты","История","Настройки"};
    private static final int[] ICONS={R.drawable.ic_nav_home,R.drawable.ic_nav_people,R.drawable.ic_nav_history,R.drawable.ic_nav_settings};
    private final DateTimeFormatter dayFormat=DateTimeFormatter.ofPattern("d MMM yyyy",new Locale("ru","RU"));
    private LedgerStore ledger;
    private LinearLayout root, mainPanel, navigation;
    private TextView shopCaption;
    private FrameLayout page;
    private String tab="Главная", selectedClient=null, search="", historyFilter="Все";
    private String shopName="Мой магазин";
    private boolean pendingExport, pendingExportCsv;
    private boolean tablet=false, largeText=false, saving=false;
    private final int pad=18;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ledger=new LedgerStore(this);
        shopName=getPreferences(MODE_PRIVATE).getString("shop","Мой магазин");
        largeText=getPreferences(MODE_PRIVATE).getBoolean("large",false);
        if(state!=null){
            tab=state.getString("tab","Главная");selectedClient=state.getString("client");
            pendingExport=state.getBoolean("pending_export");
            pendingExportCsv=state.getBoolean("pending_export_csv");
        }
        else if(getIntent().getBooleanExtra(EXTRA_FIRST_START,false)&&ledger.clients().isEmpty())tab="Клиенты";
        tablet=getResources().getConfiguration().screenWidthDp>=720;
        setContentView(createShell());
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab",tab);out.putString("client",selectedClient);
        out.putBoolean("pending_export",pendingExport);
        out.putBoolean("pending_export_csv",pendingExportCsv);
        super.onSaveInstanceState(out);
    }
    private View createShell() {
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                var edges=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                view.setPadding(edges.left,edges.top,edges.right,edges.bottom);
            }else{
                view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header=row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView insignia=label("Р",25,INK,true);
        insignia.setGravity(Gravity.CENTER);
        insignia.setBackground(shape(ACCENT,16,0));
        header.addView(insignia,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout heading=stack(2);
        heading.addView(label("Расчёт",21,TEXT,true));
        shopCaption=label(shopName,12,MUTED,false);
        shopCaption.setSingleLine(true);
        shopCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(shopCaption);
        LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(0,-2,1);
        headingParams.leftMargin=dp(12);header.addView(heading,headingParams);
        root.addView(header);

        LinearLayout body=row();
        if(tablet) {
            navigation=stack(8);
            navigation.setPadding(dp(12),dp(22),dp(12),dp(12));
            navigation.setBackgroundColor(PANEL);
            body.addView(navigation,new LinearLayout.LayoutParams(dp(190),-1));
        }
        page=new FrameLayout(this);
        body.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(!tablet) {
            navigation=row();
            navigation.setGravity(Gravity.CENTER_VERTICAL);
            navigation.setPadding(dp(8),dp(6),dp(8),dp(6));
            navigation.setBackgroundColor(PANEL);
            root.addView(navigation,new LinearLayout.LayoutParams(-1,dp(88)));
        }
        return root;
    }
    private void render() {
        navigation.removeAllViews();
        for(int i=0;i<TABS.length;i++){
            final String destination=TABS[i];
            LinearLayout item=tablet?row():stack(4);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(tablet?14:2),dp(tablet?11:5),dp(tablet?10:2),dp(tablet?11:5));
            item.setBackground(shape(destination.equals(tab)?ELEVATED:PANEL,14, destination.equals(tab)?BORDER:0));
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setColorFilter(destination.equals(tab)?ACCENT:MUTED);
            icon.setContentDescription(null);
            item.addView(icon,new LinearLayout.LayoutParams(dp(23),dp(23)));
            TextView name=label(destination,tablet?14:11,destination.equals(tab)?TEXT:MUTED,destination.equals(tab));
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setIncludeFontPadding(false);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);
            if(tablet)np.leftMargin=dp(12);
            item.addView(name,np);
            item.setContentDescription(destination);
            item.setSelected(destination.equals(tab));
            item.setFocusable(true);
            item.setOnClickListener(v->{tab=destination;selectedClient=null;render();});
            LinearLayout.LayoutParams ip=tablet?new LinearLayout.LayoutParams(-1,dp(64)):
                    new LinearLayout.LayoutParams(0,-1,1);
            if(tablet)ip.bottomMargin=dp(6);else {ip.leftMargin=dp(2);ip.rightMargin=dp(2);}
            navigation.addView(item,ip);
        }
        page.removeAllViews();
        try {
            View contents=selectedClient!=null?detailView(selectedClient):
                    tab.equals("Главная")?homeView():
                    tab.equals("Клиенты")?clientsView():
                    tab.equals("История")?historyView():settingsView();
            page.addView(contents,new FrameLayout.LayoutParams(-1,-1));
        }catch(Exception e){
            LinearLayout error=screen("Ошибка чтения книги","Данные не были изменены.");
            error.addView(label(e.getMessage()==null?"Не удалось открыть базу":e.getMessage(),15,RED,false));
            page.addView(scroll(error));
        }
    }
    private View homeView() {
        LinearLayout view=screen("Обзор","Сегодня · "+dayFormat.format(LocalDate.now()));
        List<LedgerStore.Client> customers=ledger.clients();
        List<LedgerStore.Entry> entries=ledger.entries();
        long debt=0,overdue=0;int debtors=0;
        for(LedgerStore.Client c:customers){
            long amount=LedgerStore.balance(entries,c.id);
            debt+=amount;overdue+=LedgerStore.overdue(entries,c.id,LocalDate.now());
            if(amount>0)debtors++;
        }
        LinearLayout hero=stack(8);
        hero.setPadding(dp(19),dp(19),dp(19),dp(19));
        hero.setBackground(shape(ELEVATED,24,BORDER));
        hero.addView(eyebrow("ОБЩАЯ ЗАДОЛЖЕННОСТЬ"));
        TextView figure=label(money(debt),largeText?35:32,TEXT,true);
        hero.addView(figure);
        hero.addView(label("Деньги, которые вам должны клиенты",13,MUTED,false));
        boolean stackedActions=getResources().getConfiguration().screenWidthDp<365
                ||getResources().getConfiguration().fontScale>1.2f;
        LinearLayout quick=stackedActions?stack(8):row();
        quick.setGravity(Gravity.CENTER_VERTICAL);
        addButton(quick,"+ Записать долг",true,()->chooseClient("debt"),1);
        addButton(quick,"Принять оплату",false,()->chooseClient("payment"),1);
        hero.addView(quick,margin(-1,dp(stackedActions?116:54),7));
        view.addView(hero,margin(-1,-2,4));
        if(!entries.isEmpty() && getPreferences(MODE_PRIVATE).getLong("last_json_export",0L)==0L) {
            LinearLayout backupHint=card();
            backupHint.addView(label("Сохраните копию книги",16,TEXT,true));
            backupHint.addView(label("На этом устройстве ещё не подтверждён ни один JSON-экспорт. Создайте копию перед обновлением или сменой телефона.",13,MUTED,false));
            addButton(backupHint,"Создать JSON-копию",true,()->exportBook(false),1);
            view.addView(backupHint,margin(-1,-2,10));
        }

        LinearLayout metrics=row();
        metrics.addView(metric("КЛИЕНТЫ",String.valueOf(customers.size()),"Всего в книге"),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(10);
        metrics.addView(metric("С ДОЛГОМ",String.valueOf(debtors),"Не закрыли долг"),second);
        view.addView(metrics,margin(-1,-2,10));
        LinearLayout late=card();
        late.addView(eyebrow("ПРОСРОЧЕННЫЕ ДОЛГИ"));
        late.addView(label(money(overdue),25,overdue>0?ALERT:TEXT,true),margin(-1,-2,5));
        late.addView(label(overdue>0?"Проверьте сроки возврата":"Просроченных долгов нет",13,MUTED,false),margin(-1,-2,4));
        view.addView(late,margin(-1,-2,10));

        section(view,"Быстрые действия","Операции в несколько касаний");
        actionRow(view,"＋","Новый клиент","Добавить человека в книгу",()->showClientForm(null));
        actionRow(view,"▤","Все клиенты","Остаток и история по каждому",()->{tab="Клиенты";render();});
        if(!entries.isEmpty()){
            section(view,"Последние операции","История без удаления записей");
            int shown=0;
            Map<String,String> names=new HashMap<>();
            for(LedgerStore.Client c:customers)names.put(c.id,c.name);
            for(int i=entries.size()-1;i>=0&&shown<3;i--,shown++){
                LedgerStore.Entry e=entries.get(i);
                view.addView(entryCard(e,names.get(e.clientId),LedgerStore.canceled(entries).contains(e.id)),margin(-1,-2,7));
            }
        }
        return scroll(view);
    }
    private LinearLayout metric(String title,String figure,String caption) {
        LinearLayout block=stack(4);
        block.setPadding(dp(16),dp(16),dp(16),dp(16));
        block.setBackground(shape(PANEL,18,BORDER));
        block.addView(eyebrow(title));
        block.addView(label(figure,27,TEXT,true),margin(-1,-2,3));
        block.addView(label(caption,12,MUTED,false));
        return block;
    }
    private View clientsView() {
        LinearLayout view=screen("Клиенты","Остатки и записи по каждому человеку");
        addButton(view,"+ Добавить клиента",true,()->showClientForm(null),1);
        EditText searchInput=field("Поиск по имени или телефону",search);
        searchInput.setSingleLine(true);
        view.addView(searchInput,margin(-1,dp(54),14));
        LinearLayout results=stack(8);view.addView(results);
        Runnable fill=()->fillClients(results);
        searchInput.addTextChangedListener(watcher(value->{search=value;fill.run();}));
        fill.run();
        return scroll(view);
    }
    private void fillClients(LinearLayout rows) {
        rows.removeAllViews();
        List<LedgerStore.Client> customers=ledger.clients();
        List<LedgerStore.Entry> entries=ledger.entries();
        String needle=search.toLowerCase(new Locale("ru","RU"));
        int shown=0;
        for(LedgerStore.Client c:customers){
            if(!c.name.toLowerCase(new Locale("ru","RU")).contains(needle)&&!c.phone.contains(needle))continue;
            shown++;
            long b=LedgerStore.balance(entries,c.id);
            LinearLayout card=row();
            card.setPadding(dp(18),dp(17),dp(16),dp(17));
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(shape(PANEL,18,BORDER));
            TextView badge=label(c.name.substring(0,1).toUpperCase(new Locale("ru","RU")),19,ACCENT,true);
            badge.setGravity(Gravity.CENTER);badge.setBackground(shape(ELEVATED,16,0));
            card.addView(badge,new LinearLayout.LayoutParams(dp(46),dp(46)));
            LinearLayout labels=stack(5);
            labels.addView(label(c.name,16,TEXT,true));
            labels.addView(label(c.phone.isEmpty()?"Открыть записи":c.phone,12,MUTED,false));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            lp.leftMargin=dp(12);card.addView(labels,lp);
            LinearLayout right=stack(4);right.setGravity(Gravity.RIGHT);
            right.addView(label(money(b),16,b>0?ACCENT:TEXT,true));
            right.addView(label(b>0?"Должен":"Оплачено",11,MUTED,false));
            card.addView(right);
            card.setOnClickListener(v->{selectedClient=c.id;render();});
            card.setContentDescription(c.name+", остаток "+money(b));
            rows.addView(card,margin(-1,-2,4));
        }
        if(shown==0){
            if(customers.isEmpty()) rows.addView(emptyState(R.drawable.ic_nav_people,
                    "Пока нет клиентов", "Добавьте первого клиента, затем записывайте долги и оплаты.",
                    "Добавить первого клиента", ()->showClientForm(null)));
            else rows.addView(emptyState(R.drawable.ic_nav_people,
                    "Никого не нашли", "Попробуйте другое имя или номер телефона.", null, null));
        }
    }
    private View detailView(String id) {
        LedgerStore.Client client=null;
        for(LedgerStore.Client c:ledger.clients())if(c.id.equals(id))client=c;
        if(client==null){selectedClient=null;return clientsView();}
        final LedgerStore.Client current=client;
        List<LedgerStore.Entry> all=ledger.entries();
        long amount=LedgerStore.balance(all,id);
        LinearLayout view=screen(current.name,current.phone.isEmpty()?"Карточка клиента":current.phone);
        TextView back=label("←  Все клиенты",14,ACCENT,true);
        back.setPadding(0,0,0,dp(16));back.setOnClickListener(v->{selectedClient=null;tab="Клиенты";render();});
        view.addView(back,0);
        LinearLayout total=card();
        total.addView(eyebrow("ОСТАТОК ДОЛГА"));
        total.addView(label(money(amount),36,TEXT,true),margin(-1,-2,8));
        long overdue=LedgerStore.overdue(all,id,LocalDate.now());
        if(overdue>0)total.addView(label("Просрочено: "+money(overdue),13,ALERT,true));
        else total.addView(label("Все операции учтены",13,MUTED,false));
        view.addView(total,margin(-1,-2,12));
        LinearLayout actions=row();
        addButton(actions,"+ Долг",true,()->showEntryForm(id,"debt"),1);
        addButton(actions,"Оплата",false,()->showEntryForm(id,"payment"),1);
        view.addView(actions,margin(-1,dp(54),9));
        LinearLayout tools=row();
        addButton(tools,"Изменить",false,()->showClientForm(current),1);
        addButton(tools,"Отменить запись",false,()->showReversal(id),1);
        view.addView(tools,margin(-1,dp(48),14));
        section(view,"История клиента","Долги, оплаты и отмены");
        Set<String> canceled=LedgerStore.canceled(all);
        int shown=0;
        for(int i=all.size()-1;i>=0;i--){
            LedgerStore.Entry e=all.get(i);
            if(e.clientId.equals(id)){
                view.addView(entryCard(e,null,canceled.contains(e.id)),margin(-1,-2,7));shown++;
            }
        }
        if(shown==0)view.addView(label("Пока нет операций.",14,MUTED,false));
        return scroll(view);
    }
    private View historyView() {
        LinearLayout view=screen("История","Все операции магазина");
        LinearLayout filters=row();filters.setGravity(Gravity.CENTER_VERTICAL);
        for(String choice:new String[]{"Все","Долги","Оплаты","Отмены"}){
            TextView chip=label(choice,12,choice.equals(historyFilter)?INK:MUTED,true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8),dp(11),dp(8),dp(11));
            chip.setBackground(shape(choice.equals(historyFilter)?ACCENT:ELEVATED,13,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(5);
            filters.addView(chip,p);
            chip.setOnClickListener(v->{historyFilter=choice;render();});
        }
        view.addView(filters,margin(-1,-2,10));
        Map<String,String> names=new HashMap<>();
        for(LedgerStore.Client c:ledger.clients())names.put(c.id,c.name);
        List<LedgerStore.Entry> all=ledger.entries();
        Set<String> canceled=LedgerStore.canceled(all);
        int shown=0;
        for(int i=all.size()-1;i>=0;i--){
            LedgerStore.Entry e=all.get(i);
            if(!historyFilter.equals("Все") && !(historyFilter.equals("Долги")&&e.type.equals("debt"))
                    &&!(historyFilter.equals("Оплаты")&&e.type.equals("payment"))
                    &&!(historyFilter.equals("Отмены")&&e.type.equals("reversal")))continue;
            view.addView(entryCard(e,names.get(e.clientId),canceled.contains(e.id)),margin(-1,-2,8));
            shown++;
        }
        if(shown==0){
            if(all.isEmpty()) view.addView(emptyState(R.drawable.ic_nav_history,
                    "История пока пустая", "Когда вы запишете долг или оплату, они появятся здесь.",
                    ledger.clients().isEmpty()?"Добавить первого клиента":"Записать долг",
                    ()->{if(ledger.clients().isEmpty())showClientForm(null);else chooseClient("debt");}));
            else view.addView(emptyState(R.drawable.ic_nav_history,
                    "Нет операций в фильтре", "Выберите другой тип операции или посмотрите всю историю.",
                    "Показать все", ()->{historyFilter="Все";render();}));
        }
        return scroll(view);
    }
    private View settingsView() {
        LinearLayout view=screen("Настройки","Книга и резервные копии");
        section(view,"Магазин","Название показывается только в приложении");
        actionRow(view,"✎",shopName,"Изменить название",()->showShopName());
        view.addView(textSizeSetting(),margin(-1,-2,8));
        section(view,"Сохранность данных","Рекомендуется копировать книгу регулярно");
        long lastExport=getPreferences(MODE_PRIVATE).getLong("last_json_export",0L);
        String lastExportText=lastExport==0L
                ? "JSON-копия ещё не сохранена через это приложение."
                : "Последний подтверждённый экспорт: "+dayFormat.format(
                    Instant.ofEpochMilli(lastExport).atZone(ZoneId.systemDefault()).toLocalDate());
        view.addView(label(lastExportText,13,lastExport==0L?ALERT:MUTED,false),margin(-1,-2,12));
        actionRow(view,"↓","Скачать JSON-копию","Вся история, клиенты и суммы",()->exportBook(false));
        actionRow(view,"≡","Экспортировать CSV","Таблица операций",()->exportBook(true));
        actionRow(view,"↑","Восстановить из JSON","Полная замена данных после подтверждения",()->pickImport());
        LinearLayout warning=card();
        warning.addView(label("Важно о резервных копиях",15,ALERT,true));
        warning.addView(label("При удалении приложения локальная книга будет потеряна. JSON-копия содержит имена, телефоны и суммы без шифрования.",13,MUTED,false),margin(-1,-2,7));
        view.addView(warning,margin(-1,-2,10));
        return scroll(view);
    }
    private LinearLayout entryCard(LedgerStore.Entry e,String clientName,boolean canceled) {
        LinearLayout card=row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(PANEL,17,BORDER));
        card.setPadding(dp(16),dp(15),dp(16),dp(15));
        int color=e.type.equals("payment")?ACCENT:e.type.equals("reversal")?ALERT:TEXT;
        LinearLayout left=stack(5);
        String kind=e.type.equals("debt")?"Долг":e.type.equals("payment")?"Оплата":"Отмена";
        left.addView(label(kind+(canceled?" · отменено":""),15,canceled?MUTED:color,true));
        if(clientName!=null)left.addView(label(clientName,13,TEXT,false));
        left.addView(label(formatDate(e.date)+(e.note.isEmpty()?"":" · "+e.note),12,MUTED,false));
        if(!e.due.isEmpty())left.addView(label("Вернуть до "+e.due,11,ALERT,false));
        card.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView sum=label((e.type.equals("payment")?"−":e.type.equals("debt")?"+":"↶ ")+money(e.amount),15,canceled?MUTED:color,true);
        card.addView(sum);
        return card;
    }
    private LinearLayout emptyState(int iconRes,String title,String subtitle,
                                    String actionLabel,Runnable action) {
        LinearLayout box=stack(10);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(20),dp(26),dp(20),dp(26));
        box.setBackground(shape(PANEL,20,BORDER));
        android.widget.ImageView image=new android.widget.ImageView(this);
        image.setImageResource(iconRes);
        image.setColorFilter(ACCENT);
        image.setPadding(dp(13),dp(13),dp(13),dp(13));
        image.setBackground(shape(ELEVATED,17,0));
        image.setContentDescription(null);
        box.addView(image,new LinearLayout.LayoutParams(dp(62),dp(62)));
        TextView heading=label(title,20,TEXT,true);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading,margin(-1,-2,3));
        TextView details=label(subtitle,14,MUTED,false);
        details.setGravity(Gravity.CENTER);
        details.setLineSpacing(dp(3),1f);
        box.addView(details);
        if(action!=null&&actionLabel!=null){
            TextView button=label(actionLabel,14,INK,true);
            button.setGravity(Gravity.CENTER);
            button.setBackground(shape(ACCENT,14,0));
            button.setContentDescription(actionLabel);
            button.setFocusable(true);
            button.setOnClickListener(v->action.run());
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));
            params.topMargin=dp(14);
            box.addView(button,params);
        }
        return box;
    }

    private LinearLayout textSizeSetting() {
        LinearLayout row=row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(17),dp(16),dp(17),dp(16));
        row.setBackground(shape(PANEL,17,BORDER));
        LinearLayout details=stack(5);
        details.addView(label("Крупный текст",16,TEXT,true));
        details.addView(label("Увеличить текст для удобного чтения",12,MUTED,false));
        row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(largeText);
        toggle.setContentDescription("Крупный текст");
        toggle.setOnCheckedChangeListener((buttonView,checked)->{
            if(largeText!=checked){
                largeText=checked;
                getPreferences(MODE_PRIVATE).edit().putBoolean("large",checked).apply();
                render();
            }
        });
        row.addView(toggle,new LinearLayout.LayoutParams(-2,dp(50)));
        row.setContentDescription("Крупный текст");
        row.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));
        return row;
    }

    private void chooseClient(String type) {
        List<LedgerStore.Client> customers=ledger.clients();
        if(customers.isEmpty()){showClientForm(null);return;}
        LinearLayout content=stack(8);
        for(LedgerStore.Client c:customers){
            if(type.equals("payment")&&LedgerStore.balance(ledger.entries(),c.id)<=0)continue;
            TextView b=label(c.name+"     "+money(LedgerStore.balance(ledger.entries(),c.id)),16,TEXT,false);
            b.setPadding(dp(14),dp(16),dp(14),dp(16));
            b.setBackground(shape(ELEVATED,14,BORDER));
            content.addView(b);
            b.setOnClickListener(v->{if(chooser!=null)chooser.dismiss();showEntryForm(c.id,type);});
        }
        if(content.getChildCount()==0)content.addView(label("Нет клиентов с непогашенным долгом.",14,MUTED,false));
        showDialog("Выберите клиента",content,null);
    }
    private Dialog chooser;
    private void showClientForm(LedgerStore.Client client) {
        LinearLayout content=stack(12);
        EditText name=field("Имя клиента",client==null?"":client.name);
        EditText phone=field("Телефон (необязательно)",client==null?"":client.phone);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        content.addView(formGroup("Имя",name));content.addView(formGroup("Телефон",phone));
        showDialog(client==null?"Новый клиент":"Изменить клиента",content,()->{
            if(client==null){
                ledger.addClient(name.getText().toString(),phone.getText().toString());
                tab="Клиенты";selectedClient=null;
            }else ledger.editClient(client.id,name.getText().toString(),phone.getText().toString());
            return true;
        });
    }
    private void showShopName() {
        LinearLayout content=stack(10);
        EditText field=field("Название магазина",shopName);
        content.addView(formGroup("Название",field));
        showDialog("Название магазина",content,()->{
            String s=field.getText().toString().trim();
            if(s.isEmpty()||s.length()>80)throw new IllegalArgumentException("Введите название до 80 знаков.");
            shopName=s;getPreferences(MODE_PRIVATE).edit().putString("shop",s).apply();
            if(shopCaption!=null)shopCaption.setText(s);
            return true;
        });
    }
    private void showEntryForm(String clientId,String type) {
        LedgerStore.Client c=null;
        for(LedgerStore.Client person:ledger.clients())if(person.id.equals(clientId))c=person;
        if(c==null){message("Клиент не найден.");return;}
        final long available=LedgerStore.balance(ledger.entries(),clientId);
        if(type.equals("payment")&&available<=0){message("Нет долга для оплаты.");return;}
        LinearLayout content=stack(12);
        content.addView(label(c.name+" · остаток "+money(available),13,MUTED,false));
        EditText amount=field("0,00",null);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        content.addView(formGroup("Сумма, ₽",amount));
        EditText due=field("Без срока",null);
        due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(String.format(Locale.ROOT,"%04d-%02d-%02d",y,m+1,d)),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        if(type.equals("debt"))content.addView(formGroup("Вернуть до (необязательно)",due));
        EditText note=field("Комментарий",null);
        content.addView(formGroup("Комментарий (необязательно)",note));
        showDialog(type.equals("debt")?"Новый долг":"Принять оплату",content,()->{
            ledger.addEntry(clientId,type,LedgerStore.parseAmount(amount.getText().toString()),
                    type.equals("debt")?due.getText().toString():"",
                    note.getText().toString().trim());
            selectedClient=clientId;tab="Клиенты";
            return true;
        });
    }
    private void showReversal(String clientId) {
        LedgerStore.Entry last=ledger.lastActive(clientId);
        if(last==null){message("Отменять пока нечего.");return;}
        LinearLayout content=stack(12);
        content.addView(label((last.type.equals("debt")?"Долг":"Оплата")+" "+money(last.amount)
                +". Операция останется в истории с отметкой об отмене.",14,MUTED,false));
        EditText reason=field("Например, запись внесена ошибочно",null);
        content.addView(formGroup("Причина отмены",reason));
        showDialog("Отменить последнюю запись",content,()->{
            ledger.reverseLast(clientId,reason.getText().toString());return true;
        });
    }
    private interface SaveAction { boolean save() throws Exception; }
    private void showDialog(String title,LinearLayout form,SaveAction save) {
        Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=stack(12);
        box.setPadding(dp(22),dp(22),dp(22),dp(22));
        box.setBackground(shape(PANEL,23,BORDER));
        box.addView(label(title,23,TEXT,true));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        scroll.addView(form);
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView error=label("",12,RED,false);
        error.setVisibility(View.GONE);
        box.addView(error);
        LinearLayout buttons=row();
        addButton(buttons,"Закрыть",false,dialog::dismiss,1);
        if(save!=null)addButton(buttons,"Сохранить",true,()->{
            if(saving)return;
            saving=true;
            try{if(save.save()){dialog.dismiss();render();message("Сохранено.");}}
            catch(Exception ex){error.setText(ex.getMessage()==null?"Не удалось сохранить запись.":ex.getMessage());error.setVisibility(View.VISIBLE);}
            finally{saving=false;}
        },1);
        box.addView(buttons,new LinearLayout.LayoutParams(-1,dp(48)));
        dialog.setContentView(box);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(510)),
                    Math.min(getResources().getDisplayMetrics().heightPixels-dp(96),dp(550)));
        }
        chooser=dialog;
        dialog.show();
        if(window!=null)window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(510)),
                Math.min(getResources().getDisplayMetrics().heightPixels-dp(96),dp(550)));
    }
    private void pickImport() {
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("application/json");
        try{startActivityForResult(picker,REQUEST_IMPORT);}
        catch(ActivityNotFoundException e){message("Системный выбор файлов недоступен.");}
    }
    private void exportBook(boolean csv) {
        try {
            Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            save.addCategory(Intent.CATEGORY_OPENABLE);
            save.setType(csv?"text/csv":"application/json");
            save.putExtra(Intent.EXTRA_TITLE,"raschet-"+LocalDate.now()+(csv?".csv":".json"));
            // Keep only the format while SAF is open. The data is rendered at save time,
            // so rotating Android cannot silently discard a prepared JSON payload.
            pendingExport=true;
            pendingExportCsv=csv;
            startActivityForResult(save,REQUEST_EXPORT);
        }catch(Exception e){
            pendingExport=false;
            message("Не удалось открыть выбор файла: "+e.getMessage());
        }
    }
    private String makeCsv() {
        StringBuilder out=new StringBuilder("\uFEFFКлиент;Телефон;Дата;Тип;Сумма (руб);Срок;Комментарий;ID;Отменяет ID\r\n");
        Map<String,LedgerStore.Client> people=new HashMap<>();
        for(LedgerStore.Client c:ledger.clients())people.put(c.id,c);
        for(LedgerStore.Entry e:ledger.entries()){
            LedgerStore.Client c=people.get(e.clientId);
            for(String cell:new String[]{c==null?"":c.name,c==null?"":c.phone,e.date,e.type,
                    String.format(Locale.ROOT,"%.2f",e.amount/100d).replace('.',','),e.due,e.note,e.id,e.reverses})
                out.append(csvCell(cell)).append(';');
            out.setLength(out.length()-1);out.append("\r\n");
        }
        return out.toString();
    }
    private static String csvCell(String s) {
        if(!s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==REQUEST_EXPORT){
            boolean canExport=pendingExport;
            boolean csv=pendingExportCsv;
            pendingExport=false;
            if(result!=RESULT_OK||data==null||data.getData()==null||!canExport)return;
            try {
                String body=csv?makeCsv():ledger.exportJson();
                try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){
                    if(out==null)throw new IOException("Нет доступа к файлу.");
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if(!csv){
                    getPreferences(MODE_PRIVATE).edit()
                            .putLong("last_json_export",System.currentTimeMillis()).apply();
                }
                message("Резервная копия сохранена.");
                if(tab.equals("Настройки")||tab.equals("Главная"))render();
            }catch(Exception ex){message("Ошибка сохранения: "+ex.getMessage());}
        }else if(request==REQUEST_IMPORT && result==RESULT_OK && data!=null && data.getData()!=null){
            try(InputStream in=getContentResolver().openInputStream(data.getData())){
                if(in==null)throw new IOException("Не удалось прочитать файл.");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){
                    if(bytes.size()+n>MAX_IMPORT)throw new IOException("JSON-копия больше 5 МБ.");
                    bytes.write(buf,0,n);
                }
                String backup=bytes.toString("UTF-8");
                LinearLayout form=stack(12);
                form.addView(label("Текущие клиенты и операции будут полностью заменены содержимым JSON-файла. Сохраните свою копию перед продолжением.",15,ALERT,false));
                showDialog("Восстановить книгу?",form,()->{
                    int[] count=ledger.importJson(backup);
                    // A backup of the previous book is not a backup of this restored book.
                    getPreferences(MODE_PRIVATE).edit().remove("last_json_export").apply();
                    selectedClient=null;tab="Клиенты";
                    message("Восстановлено: "+count[0]+" клиентов, "+count[1]+" операций.");
                    return true;
                });
            }catch(Exception ex){message("Не удалось прочитать JSON: "+ex.getMessage());}
        }
    }
    @Override public void onBackPressed() {
        if(selectedClient!=null){selectedClient=null;tab="Клиенты";render();}
        else if(!tab.equals("Главная")){tab="Главная";render();}
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {if(ledger!=null)ledger.close();super.onDestroy();}
    private void message(String m) {Toast.makeText(this,m,Toast.LENGTH_LONG).show();}

    private LinearLayout screen(String title,String subtitle) {
        LinearLayout result=stack(0);
        result.setPadding(dp(pad),dp(15),dp(pad),dp(36));
        result.addView(label(title,largeText?31:27,TEXT,true));
        result.addView(label(subtitle,14,MUTED,false),margin(-1,-2,5));
        return result;
    }
    private ScrollView scroll(View content) {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout center=stack(0);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams constraint=new LinearLayout.LayoutParams(-1,-2);
        if(getResources().getConfiguration().screenWidthDp>960){
            constraint.width=dp(800);
        }
        center.addView(content,constraint);
        scroll.addView(center);
        return scroll;
    }
    private LinearLayout formGroup(String title,View child) {
        LinearLayout group=stack(7);
        group.addView(label(title,13,MUTED,true));
        group.addView(child,new LinearLayout.LayoutParams(-1,dp(54)));
        return group;
    }
    private EditText field(String hint,String value) {
        EditText edit=new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(largeText?17:15);
        edit.setTextColor(TEXT);edit.setHintTextColor(MUTED);
        edit.setHint(hint);if(value!=null)edit.setText(value);
        edit.setPadding(dp(15),0,dp(15),0);
        edit.setBackground(shape(ELEVATED,13,BORDER));
        edit.setSelectAllOnFocus(false);
        return edit;
    }
    private void section(LinearLayout into,String title,String note) {
        into.addView(label(title,20,TEXT,true),margin(-1,-2,14));
        into.addView(label(note,12,MUTED,false),margin(-1,-2,5));
    }
    private void actionRow(LinearLayout into,String icon,String title,String subtitle,Runnable action) {
        LinearLayout block=row();block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(dp(15),dp(16),dp(15),dp(16));
        block.setBackground(shape(PANEL,17,BORDER));
        TextView emblem=label(icon,23,ACCENT,true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(shape(ELEVATED,13,0));
        block.addView(emblem,new LinearLayout.LayoutParams(dp(45),dp(45)));
        LinearLayout details=stack(5);
        details.addView(label(title,15,TEXT,true));
        details.addView(label(subtitle,12,MUTED,false));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(13);
        block.addView(details,p);
        block.addView(label("›",24,MUTED,false));
        block.setOnClickListener(v->action.run());
        block.setContentDescription(title);
        into.addView(block,margin(-1,-2,8));
    }
    private TextView eyebrow(String text) {
        TextView v=label(text,11,MUTED,true);
        v.setLetterSpacing(0.12f);
        return v;
    }
    private void addButton(LinearLayout parent,String text,boolean filled,Runnable action,int weight) {
        TextView button=label(text,14,filled?INK:TEXT,true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6),dp(9),dp(6),dp(9));
        button.setBackground(shape(filled?ACCENT:ELEVATED,14,filled?0:BORDER));
        button.setOnClickListener(v->action.run());
        boolean horizontal=parent.getOrientation()==LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams param=horizontal
                ?new LinearLayout.LayoutParams(0,-1,weight)
                :new LinearLayout.LayoutParams(-1,dp(54));
        if(horizontal&&parent.getChildCount()>0)param.leftMargin=dp(8);
        parent.addView(button,param);
    }
    private LinearLayout row() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }
    private LinearLayout stack(int gapDp) {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if(gapDp>0) layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        if(gapDp>0){
            GradientDrawable transparent=new GradientDrawable();
            transparent.setSize(1,dp(gapDp));transparent.setColor(Color.TRANSPARENT);
            layout.setDividerDrawable(transparent);
        }
        return layout;
    }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView textView=new TextView(this);
        textView.setText(text);textView.setTextColor(color);textView.setTextSize(size);
        textView.setFontFeatureSettings("kern");
        if(bold)textView.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }
    private GradientDrawable shape(int color,int radius,int outline) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(radius));
        if(outline!=0)shape.setStroke(dp(1),outline);
        return shape;
    }
    private LinearLayout card() {
        LinearLayout block=stack(5);
        block.setPadding(dp(18),dp(19),dp(18),dp(19));
        block.setBackground(shape(PANEL,19,BORDER));
        return block;
    }
    private LinearLayout.LayoutParams margin(int width,int height,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);
        p.bottomMargin=dp(bottom);
        return p;
    }
    private int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private String money(long cents) {
        return String.format(new Locale("ru","RU"),"%,.2f ₽",cents/100d).replace('\u00a0',' ');
    }
    private String formatDate(String raw) {
        try{return dayFormat.format(Instant.parse(raw).atZone(ZoneId.systemDefault()));}
        catch(Exception ignored){return raw;}
    }
    private TextWatcher watcher(java.util.function.Consumer<String> callback) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){callback.accept(s.toString());}
            @Override public void afterTextChanged(Editable s){}
        };
    }
}
