package dev.ventry.raschet;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android debt ledger, with platform views and SQLite persistence. */
public final class MainActivity extends Activity {
    // Shared visual vocabulary; domain calculations remain in the ledger classes.
    private static final int BG=LedgerTheme.BG, PANEL=LedgerTheme.PANEL, ELEVATED=LedgerTheme.ELEVATED;
    private static final int BORDER=LedgerTheme.BORDER, TEXT=LedgerTheme.TEXT, MUTED=LedgerTheme.MUTED;
    private static final int ACCENT=LedgerTheme.ACCENT, INK=LedgerTheme.INK, ALERT=LedgerTheme.ALERT, RED=LedgerTheme.RED;
    private static final int REQUEST_IMPORT=101, REQUEST_EXPORT=102, REQUEST_PDF=103, REQUEST_REPORT=105, MAX_IMPORT=5*1024*1024;
    private static final String RECOVERY_PREFS="raschet_local_recovery";
    private static final int NOTIFICATION_PERMISSION_REQUEST=104;
    private static final ExecutorService RECOVERY_WORK=Executors.newSingleThreadExecutor();
    public static final String EXTRA_FIRST_START = "dev.ventry.raschet.FIRST_START";
    private static final String[] TABS={"Главная","Клиенты","История","Настройки"};
    private static final int[] ICONS={R.drawable.ic_nav_home,R.drawable.ic_nav_people,R.drawable.ic_nav_history,R.drawable.ic_nav_settings};
    private final DateTimeFormatter dayFormat=DateTimeFormatter.ofPattern("d MMM yyyy",new Locale("ru","RU"));
    private LedgerStore ledger;
    private RecoveryVault vault;
    private ClientDirectory directory;
    private BookPrivacy privacy;
    private boolean unlocked,authInFlight;
    private long stoppedAt=-1;
    private String clientScope=ClientDirectory.ACTIVE;
    private int clientVisibleLimit=60;
    private LinearLayout root, mainPanel, navigation;
    private TextView shopCaption;
    private FrameLayout page;
    private String tab="Главная", selectedClient=null, search="", historyFilter="Все";
    private String clientFilter=CollectionsInsights.ALL;
    private boolean clientsAmountFirst;
    private String shopName="Мой магазин";
    private boolean pendingExport, pendingExportCsv;
    private String pendingPdfClientId;
    private LocalDate reportFrom=LocalDate.now().withDayOfMonth(1),reportTo=LocalDate.now();
    private LocalDate historyFrom,historyTo,pendingReportFrom,pendingReportTo;
    private int reportDaysLimit=14;
    private boolean tablet=false, largeText=false, saving=false;
    private int historyVisibleLimit=100, detailVisibleLimit=80, bookDayLimit=30;
    private final Set<String> expandedBookDays=new java.util.HashSet<>();
    private LocalDate bookFrom,bookTo;
    private String bookQuery="";
    private boolean bookOnlyUnpaid,bookAutoExpand,bookPayments;
    private final DateTimeFormatter bookDateFormat=DateTimeFormatter.ofPattern("dd.MM.yy",new Locale("ru","RU"));
    private final int pad=14;
    private String settingsExpanded="";
    private int controlHeight(){return largeText?54:48;}
    private int navHeight(){return largeText?68:56;}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ledger=new LedgerStore(this);
        vault=new RecoveryVault(this);
        directory=new ClientDirectory(this);
        privacy=new BookPrivacy(this);
        unlocked=!privacy.enabled();
        applyPrivacyFlag();
        shopName=getPreferences(MODE_PRIVATE).getString("shop","Мой магазин");
        largeText=getPreferences(MODE_PRIVATE).getBoolean("large",false);
        if(state!=null){
            tab=state.getString("tab","Главная");selectedClient=state.getString("client");
            search=state.getString("search","");
            clientFilter=state.getString("client_filter",CollectionsInsights.ALL);
            clientScope=state.getString("client_scope",ClientDirectory.ACTIVE);
            clientVisibleLimit=state.getInt("client_visible_limit",60);
            authInFlight=state.getBoolean("auth_in_flight",false);
            clientsAmountFirst=state.getBoolean("clients_amount_first",false);
            historyVisibleLimit=state.getInt("history_visible_limit",100);
            detailVisibleLimit=state.getInt("detail_visible_limit",80);
            bookDayLimit=state.getInt("book_day_limit",30);
            bookFrom=readDate(state,"book_from");bookTo=readDate(state,"book_to");
            bookQuery=state.getString("book_query","");bookOnlyUnpaid=state.getBoolean("book_unpaid",false);
            bookPayments=state.getBoolean("book_payments",false);
            ArrayList<String> expanded=state.getStringArrayList("expanded_book_days");
            if(expanded!=null)expandedBookDays.addAll(expanded);
            pendingExport=state.getBoolean("pending_export");
            pendingExportCsv=state.getBoolean("pending_export_csv");
            pendingPdfClientId=state.getString("pending_pdf_client");
            reportFrom=LocalDate.parse(state.getString("report_from",reportFrom.toString()));
            reportTo=LocalDate.parse(state.getString("report_to",reportTo.toString()));
            historyFrom=readDate(state,"history_from");historyTo=readDate(state,"history_to");
            pendingReportFrom=readDate(state,"pending_report_from");pendingReportTo=readDate(state,"pending_report_to");
            reportDaysLimit=state.getInt("report_days_limit",14);
            settingsExpanded=state.getString("settings_expanded","");
        }
        else if(getIntent().getBooleanExtra(EXTRA_FIRST_START,false)&&ledger.clients().isEmpty())tab="Клиенты";
        tablet=getResources().getConfiguration().screenWidthDp>=720;
        View shell=createShell();
        if(!unlocked)shell.setVisibility(View.INVISIBLE);
        setContentView(shell);
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab",tab);out.putString("client",selectedClient);
        out.putString("search",search);
        out.putString("client_filter",clientFilter);
        out.putString("client_scope",clientScope);
        out.putInt("client_visible_limit",clientVisibleLimit);
        out.putBoolean("auth_in_flight",authInFlight);
        out.putBoolean("clients_amount_first",clientsAmountFirst);
        out.putInt("history_visible_limit",historyVisibleLimit);
        out.putInt("detail_visible_limit",detailVisibleLimit);
        out.putInt("book_day_limit",bookDayLimit);
        saveDate(out,"book_from",bookFrom);saveDate(out,"book_to",bookTo);
        out.putString("book_query",bookQuery);out.putBoolean("book_unpaid",bookOnlyUnpaid);
        out.putBoolean("book_payments",bookPayments);
        out.putStringArrayList("expanded_book_days",new ArrayList<>(expandedBookDays));
        out.putBoolean("pending_export",pendingExport);
        out.putBoolean("pending_export_csv",pendingExportCsv);
        out.putString("pending_pdf_client",pendingPdfClientId);
        out.putString("report_from",reportFrom.toString());out.putString("report_to",reportTo.toString());
        saveDate(out,"history_from",historyFrom);saveDate(out,"history_to",historyTo);
        saveDate(out,"pending_report_from",pendingReportFrom);saveDate(out,"pending_report_to",pendingReportTo);
        out.putInt("report_days_limit",reportDaysLimit);
        out.putString("settings_expanded",settingsExpanded);
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
        header.setPadding(dp(14),dp(4),dp(14),dp(4));
        android.widget.ImageView insignia=new android.widget.ImageView(this);
        insignia.setImageResource(R.drawable.ic_ventry);
        insignia.setContentDescription("Ventry");
        insignia.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        header.addView(insignia,new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("Расчёт",16,TEXT,true));
        shopCaption=label(shopName,12,MUTED,false);
        shopCaption.setSingleLine(true);
        shopCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        shopCaption.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shopParams=new LinearLayout.LayoutParams(0,-2,1);
        shopParams.leftMargin=dp(12);heading.addView(shopCaption,shopParams);
        LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(0,-2,1);
        headingParams.leftMargin=dp(8);header.addView(heading,headingParams);
        header.setMinimumHeight(dp(44));root.addView(header);
        addListDivider(root);

        LinearLayout body=row();
        if(tablet) {
            navigation=stack(8);
            navigation.setPadding(dp(8),dp(12),dp(8),dp(8));
            navigation.setBackgroundColor(PANEL);
            body.addView(navigation,new LinearLayout.LayoutParams(dp(largeText?160:132),-1));
        }
        page=new FrameLayout(this);
        body.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(!tablet) {
            navigation=row();
            navigation.setGravity(Gravity.CENTER_VERTICAL);
            navigation.setPadding(dp(6),dp(3),dp(6),dp(3));
            navigation.setBackgroundColor(PANEL);
            addListDivider(root);
            root.addView(navigation,new LinearLayout.LayoutParams(-1,dp(navHeight())));
        }
        return root;
    }
    private void render() {
        if(!unlocked){root.setVisibility(View.INVISIBLE);return;}
        root.setVisibility(View.VISIBLE);
        if(!tablet){navigation.getLayoutParams().height=dp(navHeight());navigation.requestLayout();}
        navigation.removeAllViews();
        for(int i=0;i<TABS.length;i++){
            final String destination=TABS[i];
            String caption=destination.equals("Главная")?"Книга":destination;
            LinearLayout item=tablet?row():stack(2);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(tablet?10:2),dp(tablet?6:2),dp(tablet?6:2),dp(tablet?6:2));
            item.setBackground(ripple(shape(destination.equals(tab)?LedgerTheme.SELECTED:PANEL,8,0)));
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setColorFilter(destination.equals(tab)?ACCENT:MUTED);
            icon.setContentDescription(null);
            item.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView name=label(caption,tablet?14:11,destination.equals(tab)?ACCENT:MUTED,destination.equals(tab));
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setIncludeFontPadding(false);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);
            if(tablet)np.leftMargin=dp(12);
            item.addView(name,np);
            item.setContentDescription(caption);
            item.setSelected(destination.equals(tab));
            item.setFocusable(true);
            item.setOnClickListener(v->{tab=destination;selectedClient=null;settingsExpanded="";render();});
            LinearLayout.LayoutParams ip=tablet?new LinearLayout.LayoutParams(-1,dp(controlHeight())):
                    new LinearLayout.LayoutParams(0,-1,1);
            if(tablet)ip.bottomMargin=dp(6);else {ip.leftMargin=dp(2);ip.rightMargin=dp(2);}
            navigation.addView(item,ip);
        }
        page.removeAllViews();
        try {
            View contents=tablet
                    &&getResources().getConfiguration().screenWidthDp>=1100
                    &&tab.equals("Клиенты")?tabletClientsView():
                    selectedClient!=null?detailView(selectedClient):
                    tab.equals("Главная")?homeView():
                    tab.equals("Клиенты")?clientsView():
                    tab.equals("История")?historyView():
                    tab.equals("Отчёт")?reportView():settingsView();
            page.addView(contents,new FrameLayout.LayoutParams(-1,-1));
        }catch(Exception e){
            LinearLayout error=screen("Ошибка чтения книги","Данные не были изменены.");
            error.addView(label(e.getMessage()==null?"Не удалось открыть базу":e.getMessage(),15,RED,false));
            page.addView(scroll(error));
        }
    }
    private View homeView() {
        LinearLayout view=screen("Долговая книга","Сегодня · "+dayFormat.format(LocalDate.now()));
        List<LedgerStore.Client> customers=ledger.clients();
        List<LedgerStore.Entry> entries=ledger.entries();
        List<CollectionsInsights.CustomerStatus> statuses=
                CollectionsInsights.analyze(customers,entries,LocalDate.now());
        long debt=0,overdue=0,upcoming=0;
        int debtors=0,lateClients=0,soonClients=0;
        for(CollectionsInsights.CustomerStatus status:statuses){
            debt+=status.balance;overdue+=status.overdue;upcoming+=status.upcoming;
            if(status.balance>0)debtors++;
            if(status.overdue>0)lateClients++;
            if(status.upcoming>0)soonClients++;
        }
        LinearLayout hero=balancePanel("Общая задолженность",debt);
        boolean stacked=getResources().getConfiguration().screenWidthDp<365
                ||getResources().getConfiguration().fontScale>1.2f||largeText;
        LinearLayout quick=stacked?stack(8):row();
        addButton(quick,"+ Записать долг",true,this::showQuickDebt,1);
        addButton(quick,"Принять оплату",false,()->chooseClient("payment"),1);
        view.addView(hero,margin(-1,-2,6));
        view.addView(quick,margin(-1,dp(stacked?2*controlHeight()+8:controlHeight()),8));
        addListDivider(view);

        LinearLayout modes=row();
        bookModeButton(modes,"Долги по датам",false);
        bookModeButton(modes,"Оплаты по датам",true);
        view.addView(modes,margin(-1,dp(controlHeight()),8));
        section(view,bookPayments?"Полученные оплаты":"Записи по датам",
                bookPayments?"Суммы без отменённых оплат":"Записано и осталось по каждой дате");
        Map<String,LedgerStore.Client> people=new HashMap<>();
        for(LedgerStore.Client customer:customers)people.put(customer.id,customer);
        List<BookDays.Day> allDays=bookPayments?BookDays.groupPayments(entries,ZoneId.systemDefault()):BookDays.group(entries,ZoneId.systemDefault());
        List<BookDays.Day> days=BookDays.select(allDays,people,bookFrom,bookTo,bookQuery,bookOnlyUnpaid);
        if(!allDays.isEmpty()){
            metricRow(view,"Фильтры книги",bookFilterSummary(),hasBookFilter()?ACCENT:MUTED,this::showBookFilters);
            if(hasBookFilter()){
                long recorded=0,remaining=0;
                for(BookDays.Day day:days){recorded+=day.recorded;remaining+=day.remaining;}
                view.addView(label(bookPayments?"Получено по фильтру: "+money(recorded):"По фильтру: "+money(recorded)+" · осталось "+money(remaining),12,MUTED,false),margin(-1,-2,6));
            }
        }
        if(bookAutoExpand&&!days.isEmpty())expandedBookDays.add(days.get(0).date.toString());
        bookAutoExpand=false;
        LinearLayout dayList=stack(0);view.addView(dayList);
        if(allDays.isEmpty())view.addView(label(bookPayments?"Пока нет оплат. Нажмите «Принять оплату».":"Пока нет долгов. Нажмите «Записать долг», чтобы начать.",14,MUTED,false));
        else if(days.isEmpty()){
            view.addView(label("Нет записей по выбранным условиям.",14,MUTED,false));
            addButton(view,"Сбросить фильтры книги",false,()->{clearBookFilters();render();},1);
        }else fillBookDays(dayList,days,people);
        LinearLayout due=stack(0);
        section(due,"Контроль задолженности","");
        metricRow(due,"Просрочено",money(overdue),overdue>0?RED:MUTED,
                ()->openClientFilter(CollectionsInsights.LATE));
        metricRow(due,"В ближайшие 7 дней",money(upcoming),upcoming>0?ALERT:MUTED,
                ()->openClientFilter(CollectionsInsights.SOON));
        metricRow(due,"Должников",debtors+" / "+customers.size(),TEXT,
                ()->openClientFilter(CollectionsInsights.OWING));
        view.addView(due,margin(-1,-2,8));
        actionRow(view,"↗","Финансовый отчёт","Долги и оплаты за выбранные даты",()->{tab="Отчёт";selectedClient=null;render();});
        if(!entries.isEmpty()&&getPreferences(MODE_PRIVATE).getLong("last_json_export",0L)==0L){
            actionRow(view,"↓","Создать JSON-копию","Сохраните книгу перед сменой устройства",()->exportBook(false));
        }
        return scroll(view);
    }

    private void bookModeButton(LinearLayout parent,String title,boolean payments){
        boolean selected=bookPayments==payments;
        TextView button=label(title,13,selected?ACCENT:MUTED,true);
        button.setGravity(Gravity.CENTER);button.setPadding(dp(4),0,dp(4),0);
        button.setMinHeight(dp(48));button.setSelected(selected);button.setFocusable(true);
        button.setContentDescription(title);button.setBackground(ripple(shape(selected?LedgerTheme.SELECTED:BG,6,selected?LedgerTheme.SELECTED:BORDER)));
        button.setOnClickListener(v->{if(bookPayments!=payments){bookPayments=payments;clearBookFilters();expandedBookDays.clear();bookAutoExpand=true;render();}});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);
        if(parent.getChildCount()>0)lp.leftMargin=dp(6);parent.addView(button,lp);
    }
    private boolean hasBookFilter(){return bookFrom!=null||!bookQuery.trim().isEmpty()||bookOnlyUnpaid;}
    private void clearBookFilters(){bookFrom=null;bookTo=null;bookQuery="";bookOnlyUnpaid=false;bookDayLimit=30;bookAutoExpand=false;}
    private String bookFilterSummary(){
        String period=bookFrom==null?"Все даты":bookDateFormat.format(bookFrom)+(bookFrom.equals(bookTo)?"":" — "+bookDateFormat.format(bookTo));
        return period+(bookOnlyUnpaid?" · непогашенные":"")+(bookQuery.trim().isEmpty()?"":" · "+bookQuery.trim());
    }
    private void showBookFilters(){
        LinearLayout form=stack(6);
        EditText query=field("Имя или телефон",bookQuery);
        form.addView(formGroup("Поиск в книге",query));
        LocalDate[] period={bookFrom,bookTo};
        TextView summary=label("",14,TEXT,true);
        Runnable update=()->summary.setText(period[0]==null?"Все даты":bookDateFormat.format(period[0])+
                (period[0].equals(period[1])?"":" — "+bookDateFormat.format(period[1])));
        update.run();form.addView(summary);
        LocalDate today=LocalDate.now();
        LinearLayout presets=stack(4);
        addButton(presets,"Все даты",false,()->{period[0]=null;period[1]=null;update.run();},1);
        addButton(presets,"Сегодня",false,()->{period[0]=today;period[1]=today;update.run();},1);
        addButton(presets,"Последние 7 дней",false,()->{period[0]=today.minusDays(6);period[1]=today;update.run();},1);
        addButton(presets,"Этот месяц",false,()->{period[0]=today.withDayOfMonth(1);period[1]=today;update.run();},1);
        addButton(presets,"Выбрать период",false,()->pickReportDate("Начало периода",period[0]==null?today:period[0],from->{
            LocalDate end=period[1]==null||period[1].isBefore(from)?from:period[1];
            pickReportDate("Конец периода",end,to->{
                if(to.isBefore(from)||to.isAfter(LocalDate.now())){message("Выберите корректный прошедший период.");return;}
                period[0]=from;period[1]=to;update.run();
            });
        }),1);
        form.addView(presets);
        Switch unpaid=new Switch(this);unpaid.setText("Только непогашенные записи");unpaid.setTextColor(TEXT);
        unpaid.setChecked(!bookPayments&&bookOnlyUnpaid);unpaid.setMinHeight(dp(48));
        if(!bookPayments)form.addView(unpaid);
        addButton(form,"Сбросить все условия",false,()->{
            query.setText("");period[0]=null;period[1]=null;unpaid.setChecked(false);update.run();
        },1);
        showDialog("Фильтры книги",form,()->{
            bookQuery=query.getText().toString().trim();bookFrom=period[0];bookTo=period[1];bookOnlyUnpaid=!bookPayments&&unpaid.isChecked();
            bookDayLimit=30;bookAutoExpand=hasBookFilter();expandedBookDays.clear();return true;
        });
    }
    private void fillBookDays(LinearLayout into,List<BookDays.Day> days,Map<String,LedgerStore.Client> people){
        into.removeAllViews();
        int count=0;
        for(BookDays.Day day:days){
            if(count++>=bookDayLimit)break;
            String key=day.date.toString();
            LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(dp(controlHeight()));header.setPadding(dp(5),dp(6),dp(5),dp(6));
            header.setBackground(ripple(shape(BG,6,0)));
            LinearLayout left=stack(2);
            left.addView(label(bookDateFormat.format(day.date),16,TEXT,true));
            left.addView(label("Записей: "+day.entries.size(),12,MUTED,false));
            header.addView(left,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout totals=stack(1);totals.setGravity(Gravity.END);
            TextView amount=label(money(day.recorded),15,TEXT,true);amount.setGravity(Gravity.END);
            totals.addView(amount);
            TextView remaining=label(bookPayments?"Получено":"Осталось "+money(day.remaining),12,bookPayments?ACCENT:day.remaining>0?MUTED:ACCENT,false);
            remaining.setGravity(Gravity.END);totals.addView(remaining);
            header.addView(totals,new LinearLayout.LayoutParams(-2,-2));
            TextView arrow=label("⌄",20,MUTED,true);
            arrow.setPadding(dp(10),0,dp(3),0);header.addView(arrow);
            LinearLayout body=stack(0);
            Runnable sync=()->{
                boolean open=expandedBookDays.contains(key);
                arrow.setText(open?"⌃":"⌄");header.setSelected(open);
                header.setContentDescription(bookDateFormat.format(day.date)+", записей "+day.entries.size()+(bookPayments?", получено "+money(day.recorded):", записано "+money(day.recorded)+", осталось "+money(day.remaining))+(open?", свернуть":", раскрыть"));
                body.setVisibility(open?View.VISIBLE:View.GONE);
                if(open&&body.getChildCount()==0)fillBookDayRows(body,day,people,60);
            };
            header.setFocusable(true);header.setOnClickListener(v->{
                if(!expandedBookDays.add(key))expandedBookDays.remove(key);
                sync.run();
            });
            into.addView(header);into.addView(body);addListDivider(into);sync.run();
        }
        if(days.size()>bookDayLimit)addButton(into,"Показать более ранние даты",false,()->{bookDayLimit+=30;fillBookDays(into,days,people);},1);
    }
    private void fillBookDayRows(LinearLayout body,BookDays.Day day,Map<String,LedgerStore.Client> people,int limit){
        body.removeAllViews();
        int shown=0;
        for(LedgerStore.Entry e:day.entries){
            if(shown++>=limit)break;
            LedgerStore.Client person=people.get(e.clientId);
            if(person==null)continue;
            boolean canceled=day.canceled.contains(e.id);
            long unpaid=day.unpaid.getOrDefault(e.id,0L);
            LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dp(48));item.setPadding(dp(12),dp(6),dp(4),dp(6));
            item.setBackground(ripple(shape(BG,6,0)));
            LinearLayout description=stack(1);
            description.addView(label(person.name,15,TEXT,true));
            if(canceled)description.addView(label("Отменено",12,MUTED,false));
            else{
                description.addView(label(bookPayments?"Оплата":unpaid==0?"Погашено":unpaid<e.amount?"Осталось "+money(unpaid):"Не погашено",12,bookPayments||unpaid==0?ACCENT:MUTED,false));
                if(!e.note.isEmpty())description.addView(label(e.note,12,MUTED,false));
            }
            item.addView(description,new LinearLayout.LayoutParams(0,-2,1));
            TextView sum=label(money(e.amount),15,canceled?MUTED:bookPayments?ACCENT:TEXT,true);
            if(canceled)sum.setPaintFlags(sum.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            item.addView(sum);
            item.setFocusable(true);item.setOnClickListener(v->{selectedClient=e.clientId;tab="Клиенты";render();});
            if(!bookPayments&&!canceled&&unpaid>0){
                TextView pay=label("Оплата",12,ACCENT,true);pay.setGravity(Gravity.CENTER);
                pay.setMinHeight(dp(48));pay.setPadding(dp(8),0,dp(2),0);pay.setFocusable(true);
                pay.setContentDescription("Оплата · "+person.name);
                pay.setOnClickListener(v->showEntryForm(person.id,"payment"));
                item.addView(pay,new LinearLayout.LayoutParams(-2,dp(controlHeight())));
            }
            if(!bookPayments&&!canceled){
                TextView repeat=label("+",22,ACCENT,true);
                repeat.setGravity(Gravity.CENTER);repeat.setFocusable(true);
                repeat.setContentDescription("Новый долг · "+person.name);
                repeat.setBackground(ripple(shape(BG,6,0)));
                repeat.setOnClickListener(v->showEntryForm(person.id,"debt"));
                item.addView(repeat,new LinearLayout.LayoutParams(dp(48),dp(controlHeight())));
            }
            body.addView(item);addListDivider(body);
        }
        if(day.entries.size()>limit)addButton(body,"Показать ещё записи",false,()->fillBookDayRows(body,day,people,limit+60),1);
        if(!bookPayments)addButton(body,"+ Долг за этот день",false,()->showQuickDebt(false,day.date),1);
    }
    private View tabletClientsView() {
        LinearLayout columns=row();
        columns.setBackgroundColor(BG);
        View list=clientsView();
        columns.addView(list,new LinearLayout.LayoutParams(0,-1,38f));
        View divider=new View(this);
        divider.setBackgroundColor(BORDER);
        columns.addView(divider,new LinearLayout.LayoutParams(dp(1),-1));
        View detail=selectedClient==null?
                scroll(emptyState(R.drawable.ic_nav_people,
                        "Выберите клиента",
                        "Слева список клиентов и фильтры. Здесь появятся остаток, операции и быстрые действия.",
                        null,null)):detailView(selectedClient);
        columns.addView(detail,new LinearLayout.LayoutParams(0,-1,62f));
        return columns;
    }
    private void openClientFilter(String filter) {
        clientFilter=filter;clientScope=ClientDirectory.ACTIVE;clientVisibleLimit=60;search="";selectedClient=null;tab="Клиенты";render();
    }
    private View clientsView() {
        LinearLayout view=screen("Клиенты","");
        if(ledger.clients().isEmpty()){
            view.addView(emptyState(R.drawable.ic_nav_people,"Пока нет клиентов",
                    "Добавьте клиента, чтобы записывать долги и оплаты.",
                    "Добавить первого клиента",()->showClientForm(null)));
            return scroll(view);
        }
        EditText searchInput=field("Поиск по имени или телефону",search);
        searchInput.setSingleLine(true);
        boolean compact=!largeText&&getResources().getConfiguration().fontScale<=1.2f
                &&getResources().getConfiguration().screenWidthDp>=340;
        if(compact){
            LinearLayout tools=row();tools.setGravity(Gravity.CENTER_VERTICAL);
            tools.addView(searchInput,new LinearLayout.LayoutParams(0,dp(controlHeight()),1));
            TextView add=label("+",24,INK,true);
            add.setGravity(Gravity.CENTER);
            add.setBackground(ripple(shape(ACCENT,10,0)));
            add.setContentDescription("+ Добавить клиента");
            add.setFocusable(true);add.setOnClickListener(v->showClientForm(null));
            LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(48),dp(48));
            ap.leftMargin=dp(8);tools.addView(add,ap);
            view.addView(tools,margin(-1,-2,6));
        }else{
            addButton(view,"+ Добавить клиента",true,()->showClientForm(null),1);
            view.addView(searchInput,margin(-1,dp(controlHeight()),6));
        }
        metricRow(view,"Фильтры клиентов",clientScope+" · "+clientFilter+
                (clientsAmountFirst?" · по остатку":" · по имени"),MUTED,this::showClientFilters);
        LinearLayout results=stack(0);view.addView(results);
        // Build due/balance projections once per render, not on every typed character.
        List<CollectionsInsights.CustomerStatus> cached=
                CollectionsInsights.analyze(ledger.clients(),ledger.entries(),LocalDate.now());
        Runnable fill=()->fillClients(results,cached);
        searchInput.addTextChangedListener(watcher(value->{search=value;clientVisibleLimit=60;fill.run();}));
        fill.run();
        return scroll(view);
    }
    private void showClientFilters(){
        LinearLayout menu=stack(2);
        section(menu,"Список","");
        for(String scope:new String[]{ClientDirectory.ACTIVE,ClientDirectory.FAVORITES,ClientDirectory.ARCHIVE})
            filterMenuAction(menu,scope,scope.equals(clientScope),()->{
                clientScope=scope;clientFilter=CollectionsInsights.ALL;clientVisibleLimit=60;render();
            });
        section(menu,"Долги","");
        for(String choice:new String[]{CollectionsInsights.ALL,CollectionsInsights.OWING,CollectionsInsights.LATE,CollectionsInsights.SOON})
            filterMenuAction(menu,choice,choice.equals(clientFilter),()->{clientFilter=choice;clientVisibleLimit=60;render();});
        section(menu,"Сортировка","");
        filterMenuAction(menu,"Сортировка: по имени",!clientsAmountFirst,()->{clientsAmountFirst=false;render();});
        filterMenuAction(menu,"Сортировка: по остатку ↓",clientsAmountFirst,()->{clientsAmountFirst=true;render();});
        showDialog("Фильтры клиентов",menu,null);
    }
    private void fillClients(LinearLayout rows,List<CollectionsInsights.CustomerStatus> cached) {
        rows.removeAllViews();
        List<CollectionsInsights.CustomerStatus> visible=CollectionsInsights.visible(
                directory.select(cached,clientScope),clientFilter,clientsAmountFirst,search);
        int shown=0;
        for(CollectionsInsights.CustomerStatus status:visible){
            if(shown++>=clientVisibleLimit)break;
            LedgerStore.Client customer=status.client;
            long amount=status.balance;
            LinearLayout card=row();
            card.setPadding(dp(4),dp(7),dp(4),dp(7));
            card.setMinimumHeight(dp(48));
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(ripple(shape(customer.id.equals(selectedClient)?LedgerTheme.SELECTED:BG,6,0)));
            card.setSelected(customer.id.equals(selectedClient));
            LinearLayout labels=stack(1);
            labels.addView(label(customer.name,15,TEXT,true));
            if(!customer.phone.isEmpty())labels.addView(label(customer.phone,13,MUTED,false));
            String statusText=status.overdue>0?"Просрочено: "+money(status.overdue):
                    status.upcoming>0?"Ближайший срок: "+status.nextDue+" · за 7 дней: "+money(status.upcoming):"";
            if(!statusText.isEmpty())
                labels.addView(label(statusText,12,status.overdue>0?RED:ALERT,false));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            lp.leftMargin=dp(8);lp.rightMargin=dp(8);card.addView(labels,lp);
            LinearLayout right=stack(1);right.setGravity(Gravity.END);
            right.addView(label(money(amount),16,TEXT,true));
            if(amount==0)right.addView(label("Нет долга",11,MUTED,false));
            card.addView(right);
            TextView chevron=label("›",19,MUTED,false);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=dp(8);
            card.addView(chevron,cp);
            card.setClickable(true);card.setFocusable(true);
            card.setOnClickListener(v->{selectedClient=customer.id;render();});
            card.setContentDescription(customer.name+", остаток "+money(amount)
                    +(status.overdue>0?", просрочено "+money(status.overdue):""));
            rows.addView(card);
            addListDivider(rows);
        }
        if(visible.size()>clientVisibleLimit)addButton(rows,"Показать ещё клиентов",false,()->{
            clientVisibleLimit+=60;fillClients(rows,cached);
        },1);
        if(visible.isEmpty()){
            if(!ClientDirectory.ACTIVE.equals(clientScope)&&search.trim().isEmpty())
                rows.addView(emptyState(R.drawable.ic_nav_people,
                    ClientDirectory.ARCHIVE.equals(clientScope)?"Архив пуст":"Нет избранных клиентов",
                    "Управляйте списками из карточки клиента.","Рабочие клиенты",
                    ()->{clientScope=ClientDirectory.ACTIVE;clientFilter=CollectionsInsights.ALL;render();}));
            else if(cached.isEmpty())rows.addView(emptyState(R.drawable.ic_nav_people,
                    "Пока нет клиентов","Добавьте первого клиента, затем записывайте долги и оплаты.",
                    "Добавить первого клиента",()->showClientForm(null)));
            else if(!search.trim().isEmpty())rows.addView(emptyState(R.drawable.ic_nav_people,
                    "Никого не нашли","Попробуйте другое имя или номер телефона.",
                    "Очистить поиск",()->{search="";render();}));
            else if(clientFilter.equals(CollectionsInsights.LATE))rows.addView(emptyState(
                    R.drawable.ic_nav_history,"Просроченных долгов нет",
                    "Просрочка появится здесь, если срок возврата прошёл и долг не погашен.",
                    "Показать всех",()->openClientFilter(CollectionsInsights.ALL)));
            else if(clientFilter.equals(CollectionsInsights.SOON))rows.addView(emptyState(
                    R.drawable.ic_nav_history,"Нет ближайших сроков",
                    "Нет непогашенных долгов со сроком возврата в следующие 7 дней.",
                    "Показать всех",()->openClientFilter(CollectionsInsights.ALL)));
            else rows.addView(emptyState(R.drawable.ic_nav_people,
                    "Нет клиентов с долгом","Здесь будут клиенты с непогашенным остатком.",
                    "Показать всех",()->openClientFilter(CollectionsInsights.ALL)));
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
        back.setMinHeight(dp(48));back.setPadding(0,0,0,dp(2));back.setOnClickListener(v->{selectedClient=null;tab="Клиенты";render();});
        LinearLayout context=row();context.setGravity(Gravity.CENTER_VERTICAL);
        context.addView(back,new LinearLayout.LayoutParams(0,dp(controlHeight()),1));
        view.addView(context,0);
        LinearLayout total=balancePanel("Остаток долга",amount);
        long overdue=LedgerStore.overdue(all,id,LocalDate.now());
        CollectionsInsights.CustomerStatus status=null;
        for(CollectionsInsights.CustomerStatus item:CollectionsInsights.analyze(
                ledger.clients(),all,LocalDate.now())){
            if(item.client.id.equals(id)){status=item;break;}
        }
        if(overdue>0)total.addView(label("Просрочено: "+money(overdue),13,RED,true));
        else total.addView(label(amount==0?"Долга нет":"Нет просрочки",13,MUTED,false));
        if(status!=null&&status.upcoming>0)
            total.addView(label("Ближайший срок: "+status.nextDue+" · за 7 дней: "+money(status.upcoming),
                    13,MUTED,false));
        view.addView(total,margin(-1,-2,12));
        boolean archived=directory.archived(id,amount);
        if(archived)view.addView(label("Клиент в архиве. История сохранена.",14,MUTED,true));
        if(!archived){
            boolean stackedActions=largeText||getResources().getConfiguration().fontScale>1.2f;
            LinearLayout actions=stackedActions?stack(6):row();
            addButton(actions,"+ Долг",true,()->showEntryForm(id,"debt"),1);
            addButton(actions,"Оплата",true,()->showEntryForm(id,"payment"),1);
            view.addView(actions,margin(-1,dp(stackedActions?2*controlHeight()+6:controlHeight()),5));
        }else{
            addButton(view,"Вернуть из архива",true,()->changeClientArchive(id,true),1);
        }
        TextView moreActions=label("Ещё действия",14,ACCENT,true);
        moreActions.setGravity(Gravity.CENTER);
        moreActions.setMinHeight(dp(48));moreActions.setPadding(dp(8),0,dp(8),0);
        moreActions.setBackground(ripple(shape(BG,8,0)));
        moreActions.setContentDescription("Ещё действия");
        moreActions.setFocusable(true);
        moreActions.setOnClickListener(v->showClientActions(current,amount,overdue,archived));
        context.addView(moreActions,new LinearLayout.LayoutParams(-2,dp(controlHeight())));
        section(view,"История клиента","");
        Set<String> canceled=LedgerStore.canceled(all);
        int shown=0;
        boolean more=false;
        for(int i=all.size()-1;i>=0;i--){
            LedgerStore.Entry e=all.get(i);
            if(!e.clientId.equals(id))continue;
            if(shown>=detailVisibleLimit){more=true;break;}
            view.addView(entryCard(e,null,canceled.contains(e.id)));addListDivider(view);shown++;
        }
        if(shown==0)view.addView(label("Пока нет операций.",14,MUTED,false));
        if(more)addButton(view,"Показать ещё операции",false,()->{
            detailVisibleLimit=Math.min(detailVisibleLimit+80,100000);render();
        },1);
        return scroll(view);
    }
    private void changeClientArchive(String id,boolean archived){
        try{
            directory.archive(id,LedgerStore.balance(ledger.entries(),id),!archived);
            selectedClient=null;clientScope=archived?ClientDirectory.ACTIVE:ClientDirectory.ARCHIVE;
            clientFilter=CollectionsInsights.ALL;search="";render();
        }catch(Exception e){message(e.getMessage());}
    }
    private void addListDivider(LinearLayout parent){
        View divider=new View(this);
        divider.setBackgroundColor(BORDER);
        parent.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void clientMenuAction(LinearLayout menu,String title,Runnable action){
        filterMenuAction(menu,title,false,action);
    }
    private void filterMenuAction(LinearLayout menu,String title,boolean selected,Runnable action){
        LinearLayout item=row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(dp(controlHeight()));
        item.setPadding(dp(10),dp(5),dp(10),dp(5));
        item.setBackground(ripple(shape(selected?ELEVATED:PANEL,8,0)));
        item.addView(label(title,14,selected?ACCENT:TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        if(selected)item.addView(label("Выбрано",11,ACCENT,false));
        item.setSelected(selected);
        item.setContentDescription(title);
        item.setFocusable(true);item.setClickable(true);
        item.setOnClickListener(v->{if(chooser!=null)chooser.dismiss();action.run();});
        menu.addView(item);
    }
    private void showClientActions(LedgerStore.Client client,long amount,long overdue,boolean archived){
        String id=client.id;
        LinearLayout menu=stack(7);
        if(!archived){
            if(amount>0)clientMenuAction(menu,"Погасить весь остаток",()->showEntryForm(id,"payment",amount));
            clientMenuAction(menu,"Долг по товарам",()->showPurchaseForm(id));
            clientMenuAction(menu,"Изменить клиента",()->showClientForm(client));
            if(ledger.lastActive(id)!=null){
                clientMenuAction(menu,"Исправить последнюю запись",()->showCorrection(id));
                clientMenuAction(menu,"Отменить запись",()->showReversal(id));
            }
        }
        clientMenuAction(menu,"Поделиться сводкой",()->shareClientSummary(client,amount,overdue));
        clientMenuAction(menu,"Сохранить выписку PDF",()->exportCustomerPdf(id));
        clientMenuAction(menu,directory.favorite(id)?"Убрать из избранного":"В избранное",()->{
            try{directory.favorite(id,!directory.favorite(id));render();}catch(Exception e){message(e.getMessage());}
        });
        if(!archived&&amount==0)clientMenuAction(menu,"В архив",()->changeClientArchive(id,false));
        showDialog("Действия с клиентом",menu,null);
    }
    private static LocalDate readDate(Bundle b,String key){
        String value=b.getString(key);return value==null?null:LocalDate.parse(value);
    }
    private static void saveDate(Bundle b,String key,LocalDate date){
        if(date!=null)b.putString(key,date.toString());
    }
    private void setPeriod(boolean report,LocalDate from,LocalDate to){
        if(from.isAfter(to)||to.isAfter(from.plusDays(365))){
            message("Выберите период от 1 до 366 дней.");return;
        }
        if(report){reportFrom=from;reportTo=to;reportDaysLimit=14;}
        else{historyFrom=from;historyTo=to;historyVisibleLimit=100;}
        render();
    }
    private void choosePeriod(boolean report){
        LocalDate today=LocalDate.now();
        LinearLayout form=stack(8);
        String[] titles={"Сегодня","Последние 7 дней","Этот месяц","Произвольный период"};
        Dialog dialog=new Dialog(this);
        LinearLayout body=card();
        body.addView(label("Выберите период",20,TEXT,true));
        for(int i=0;i<titles.length;i++){
            final int which=i;
            addButton(form,titles[i],false,()->{
                dialog.dismiss();
                if(which==0)setPeriod(report,today,today);
                else if(which==1)setPeriod(report,today.minusDays(6),today);
                else if(which==2)setPeriod(report,today.withDayOfMonth(1),today);
                else{
                    LocalDate start=report?reportFrom:historyFrom==null?today:historyFrom;
                    pickReportDate("Начало периода",start,from->{
                        LocalDate end=report?reportTo:historyTo==null?today:historyTo;
                        if(end.isBefore(from))end=from;
                        pickReportDate("Конец периода",end,to->setPeriod(report,from,to));
                    });
                }
            },1);
        }
        addButton(form,"Отмена",false,dialog::dismiss,1);
        body.addView(form);dialog.setContentView(scroll(body));
        if(dialog.getWindow()!=null){
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(460)),-2);
        }
        dialog.show();
    }
    private void pickReportDate(String title,LocalDate date,java.util.function.Consumer<LocalDate> callback){
        DatePickerDialog picker=new DatePickerDialog(this,(v,y,m,d)->callback.accept(LocalDate.of(y,m+1,d)),
                date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());
        picker.setTitle(title);picker.show();
    }
    private void reportLine(LinearLayout parent,String name,long amount,int color){
        LinearLayout line=row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0,dp(9),0,dp(9));
        line.addView(label(name,14,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        line.addView(label(money(amount),17,color,true));
        parent.addView(line);
    }
    private View reportView(){
        PeriodReport report=PeriodReport.build(ledger.entries(),reportFrom,reportTo,ZoneId.systemDefault());
        LinearLayout view=screen("Финансовый отчёт","Движение задолженности, не выручка и не прибыль");
        actionRow(view,"▤","Период отчёта",dayFormat.format(reportFrom)+" — "+dayFormat.format(reportTo),
                ()->choosePeriod(true));
        LinearLayout summary=balancePanel("Остаток на конец",report.closing);
        reportLine(summary,"Остаток на начало",report.opening,TEXT);
        reportLine(summary,"Изменение задолженности",report.totals.net(),TEXT);
        summary.addView(label("Операций: "+report.totals.count+" · клиентов: "+report.activeCustomers,13,MUTED,false));
        view.addView(summary,margin(-1,-2,10));
        LinearLayout actions=stack(8);
        addButton(actions,"Операции периода",true,()->{
            historyFrom=reportFrom;historyTo=reportTo;historyFilter="Все";historyVisibleLimit=100;
            tab="История";render();
        },1);
        addButton(actions,"Экспорт отчёта CSV",false,this::exportReport,1);
        view.addView(actions,margin(-1,-2,12));
        LinearLayout flow=stack(4);
        flow.addView(label("Движение за период",18,TEXT,true));
        reportLine(flow,"Записано долгов",report.totals.debts,TEXT);
        reportLine(flow,"Получено оплат",report.totals.payments,ACCENT);
        reportLine(flow,"Отменено долгов",report.totals.debtReversals,ALERT);
        reportLine(flow,"Отменено оплат",report.totals.paymentReversals,ALERT);
        flow.addView(label("Отмены учитываются по дате отмены. Суммы долгов и оплат показаны до вычета отмен.",13,MUTED,false));
        view.addView(flow,margin(-1,-2,10));
        section(view,"По дням","Дни, в которые были операции");
        if(report.daily.isEmpty())view.addView(label("За выбранный период операций нет.",15,MUTED,false));
        int shown=0;
        for(Map.Entry<LocalDate,PeriodReport.Totals> day:report.daily.entrySet()){
            if(shown++>=reportDaysLimit)break;
            PeriodReport.Totals t=day.getValue();
            LinearLayout item=stack(3);
            item.addView(label(dayFormat.format(day.getKey()),15,TEXT,true));
            item.addView(label("Долги "+money(t.debts)+" · оплаты "+money(t.payments),13,MUTED,false));
            item.addView(label("Отмены долгов "+money(t.debtReversals)+" · отмены оплат "+money(t.paymentReversals),13,MUTED,false));
            item.addView(label("Изменение: "+money(t.net()),16,ACCENT,true));
            view.addView(item,margin(-1,-2,7));addListDivider(view);
        }
        if(report.daily.size()>reportDaysLimit)addButton(view,"Показать ещё дни",false,()->{reportDaysLimit+=14;render();},1);
        return scroll(view);
    }
    private void exportReport(){
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("text/csv");
        save.putExtra(Intent.EXTRA_TITLE,"raschet-report-"+reportFrom+"-"+reportTo+".csv");
        pendingReportFrom=reportFrom;pendingReportTo=reportTo;
        try{startActivityForResult(save,REQUEST_REPORT);}
        catch(ActivityNotFoundException ex){pendingReportFrom=null;pendingReportTo=null;message("Выбор файла недоступен.");}
    }
    private View historyView() {
        LinearLayout view=screen("История","");
        if(ledger.entries().isEmpty()){
            view.addView(emptyState(R.drawable.ic_nav_history,"История пока пустая",
                    "Здесь будут долги и оплаты.",
                    ledger.clients().isEmpty()?"Добавить первого клиента":"Записать долг",
                    ()->{if(ledger.clients().isEmpty())showClientForm(null);else chooseClient("debt");}));
            return scroll(view);
        }
        LinearLayout filters=row();filters.setGravity(Gravity.CENTER_VERTICAL);
        for(String choice:new String[]{"Все","Долги","Оплаты","Отмены"}){
            TextView chip=label(choice,12,choice.equals(historyFilter)?INK:MUTED,true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4),dp(5),dp(4),dp(5));chip.setMinHeight(dp(48));
            chip.setTextColor(choice.equals(historyFilter)?ACCENT:MUTED);
            chip.setBackground(ripple(shape(choice.equals(historyFilter)?LedgerTheme.SELECTED:BG,6,choice.equals(historyFilter)?LedgerTheme.SELECTED:BORDER)));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(5);
            filters.addView(chip,p);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setSelected(choice.equals(historyFilter));
            chip.setContentDescription("Фильтр: "+choice);
            chip.setOnClickListener(v->{historyFilter=choice;historyVisibleLimit=100;render();});
        }
        view.addView(filters,margin(-1,-2,5));
        actionRow(view,"▤","Период истории",historyFrom==null?"Все даты":dayFormat.format(historyFrom)+" — "+dayFormat.format(historyTo),
                ()->choosePeriod(false));
        if(historyFrom!=null)addButton(view,"Сбросить даты",false,()->{historyFrom=null;historyTo=null;historyVisibleLimit=100;render();},1);
        Map<String,String> names=new HashMap<>();
        for(LedgerStore.Client c:ledger.clients())names.put(c.id,c.name);
        List<LedgerStore.Entry> all=ledger.entries();
        Set<String> canceled=LedgerStore.canceled(all);
        int shown=0;
        boolean moreHistory=false;
        LocalDate lastHistoryDay=null;
        for(int i=all.size()-1;i>=0;i--){
            LedgerStore.Entry e=all.get(i);
            if(historyFrom!=null){
                LocalDate date=PeriodReport.dateOf(e,ZoneId.systemDefault());
                if(date.isBefore(historyFrom)||date.isAfter(historyTo))continue;
            }
            if(!historyFilter.equals("Все") && !(historyFilter.equals("Долги")&&e.type.equals("debt"))
                    &&!(historyFilter.equals("Оплаты")&&e.type.equals("payment"))
                    &&!(historyFilter.equals("Отмены")&&e.type.equals("reversal")))continue;
            if(shown>=historyVisibleLimit){moreHistory=true;break;}
            LocalDate date=PeriodReport.dateOf(e,ZoneId.systemDefault());
            if(!date.equals(lastHistoryDay)){
                TextView dateHeading=label(dayFormat.format(date),12,MUTED,true);
                dateHeading.setPadding(dp(6),dp(7),dp(6),dp(7));
                dateHeading.setBackgroundColor(PANEL);
                view.addView(dateHeading,margin(-1,-2,2));
                lastHistoryDay=date;
            }
            view.addView(entryCard(e,names.get(e.clientId),canceled.contains(e.id),false));
            addListDivider(view);shown++;
        }
        if(moreHistory)addButton(view,"Показать ещё операции",false,()->{
            historyVisibleLimit=Math.min(historyVisibleLimit+100,100000);render();
        },1);
        if(shown==0){
            if(all.isEmpty()) view.addView(emptyState(R.drawable.ic_nav_history,
                    "История пока пустая", "Когда вы запишете долг или оплату, они появятся здесь.",
                    ledger.clients().isEmpty()?"Добавить первого клиента":"Записать долг",
                    ()->{if(ledger.clients().isEmpty())showClientForm(null);else chooseClient("debt");}));
            else view.addView(emptyState(R.drawable.ic_nav_history,
                    "Нет операций в фильтре", "Выберите другой тип операции или посмотрите всю историю.",
                    "Показать все", ()->{historyFilter="Все";historyFrom=null;historyTo=null;historyVisibleLimit=100;render();}));
        }
        return scroll(view);
    }
    private View settingsView() {
        LinearLayout view=screen("Настройки","");
        section(view,"Общие","");
        actionRow(view,"✎",shopName,"Изменить название",()->showShopName());
        view.addView(textSizeSetting(),margin(-1,-2,8));
        section(view,"Напоминания о долгах","");
        view.addView(reminderSetting(),margin(-1,-2,14));
        actionRow(view,"⌑","Защита входа",privacy.enabled()?"Включена":"Выключена",()->toggleSettings("privacy"));
        if(settingsExpanded.equals("privacy")){
        LinearLayout privacyCard=card();
        privacyCard.addView(label(privacy.enabled()?"Вход защищён кодом устройства":"Защита входа выключена",16,TEXT,true));
        privacyCard.addView(label("Подтверждение кодом Android при открытии и после минуты вне приложения.",13,MUTED,false));
        addButton(privacyCard,privacy.enabled()?"Отключить защиту входа":"Защитить вход",false,
                ()->authenticate(privacy.enabled()?BookPrivacy.DISABLE:BookPrivacy.ENABLE),1);
        if(privacy.enabled())addButton(privacyCard,"Заблокировать книгу",false,()->{
            unlocked=false;render();authenticate(BookPrivacy.OPEN);
        },1);
        else addButton(privacyCard,privacy.hideScreens()?"Разрешить снимки экрана":"Скрывать снимки и недавние",false,()->{
            privacy.setHideScreens(!privacy.hideScreens());applyPrivacyFlag();render();
        },1);
        privacyCard.addView(label("При защите входа снимки блокируются. База отдельно не шифруется.",12,MUTED,false));
        view.addView(privacyCard,margin(-1,-2,6));
        }
        actionRow(view,"↓","Резервные копии",getPreferences(MODE_PRIVATE).getLong("last_json_export",0L)==0L?"Нет подтверждённой внешней копии":"Экспорт и восстановление",()->toggleSettings("backup"));
        if(settingsExpanded.equals("backup")){
        long lastExport=getPreferences(MODE_PRIVATE).getLong("last_json_export",0L);
        String lastExportText=lastExport==0L
                ? "JSON-копия ещё не сохранена через это приложение."
                : "Последний подтверждённый экспорт: "+dayFormat.format(
                    Instant.ofEpochMilli(lastExport).atZone(ZoneId.systemDefault()).toLocalDate());
        view.addView(label(lastExportText,13,lastExport==0L?ALERT:MUTED,false),margin(-1,-2,12));
        LinearLayout vaultCard=card();
        vaultCard.addView(label("Защищённые точки восстановления",16,TEXT,true));
        int count=vault.count();
        String status=count==0
                ? "Пока нет точек. Создайте первую, прежде чем менять книгу."
                : "Сохранено точек: "+count+" · последняя: "+dayFormat.format(
                    Instant.ofEpochMilli(vault.latestTime())
                            .atZone(ZoneId.systemDefault()).toLocalDate());
        vaultCard.addView(label(status,13,MUTED,false));
        if(recoveryPrefs().contains("last_error"))
            vaultCard.addView(label("Автозащита не смогла создать последнюю копию. Сохраните JSON вручную.",13,RED,false));
        vaultCard.addView(label("Зашифрованы на этом устройстве. После удаления приложения или сброса устройства восстановить их нельзя.",12,ALERT,false));
        addButton(vaultCard,"Создать защищённую точку",false,
                this::createRecoveryPoint,1);
        if(vault.hasPoints())
            addButton(vaultCard,"Восстановить защищённую точку",false,
                    this::restoreRecoveryPoint,1);
        view.addView(vaultCard,margin(-1,-2,10));
        view.addView(recoveryAutoSetting(),margin(-1,-2,12));
        actionRow(view,"↓","Скачать JSON-копию","Для переноса на другое устройство",()->exportBook(false));
        actionRow(view,"≡","Экспортировать CSV","Таблица операций",()->exportBook(true));
        actionRow(view,"↑","Восстановить из JSON","Полная замена данных после подтверждения",()->pickImport());
        LinearLayout warning=card();
        warning.addView(label("Важно о резервных копиях",15,ALERT,true));
        warning.addView(label("При удалении приложения локальная книга будет потеряна. JSON-копия содержит имена, телефоны и суммы без шифрования.",13,MUTED,false),margin(-1,-2,7));
        view.addView(warning,margin(-1,-2,6));
        }
        return scroll(view);
    }
    private void toggleSettings(String section){
        settingsExpanded=settingsExpanded.equals(section)?"":section;render();
    }
    private LinearLayout entryCard(LedgerStore.Entry e,String clientName,boolean canceled) {
        return entryCard(e,clientName,canceled,true);
    }
    private LinearLayout entryCard(LedgerStore.Entry e,String clientName,boolean canceled,boolean includeDate) {
        LinearLayout card=row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(BG,8,0));
        card.setPadding(dp(4),dp(7),dp(4),dp(7));card.setMinimumHeight(dp(48));
        int color=e.type.equals("payment")?ACCENT:e.type.equals("reversal")?ALERT:TEXT;
        TextView marker=label(e.type.equals("debt")?"+":e.type.equals("payment")?"−":"↶",18,canceled?MUTED:color,true);
        marker.setGravity(Gravity.CENTER);marker.setBackground(shape(PANEL,6,0));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(26),dp(26));mp.rightMargin=dp(8);card.addView(marker,mp);
        LinearLayout left=stack(2);
        String kind=e.type.equals("debt")?"Долг":e.type.equals("payment")?"Оплата":"Отмена";
        if(clientName!=null)left.addView(label(clientName,15,TEXT,true));
        left.addView(label(kind+(canceled?" · отменено":"")+(clientName==null||!includeDate?"":" · "+formatDate(e.date)),clientName==null?15:12,canceled?MUTED:color,clientName==null));
        if(clientName==null)left.addView(label(formatDate(e.date),12,MUTED,false));
        if(!e.note.isEmpty())left.addView(label(e.note,12,MUTED,false));
        if(!e.due.isEmpty())left.addView(label("Вернуть до "+e.due,11,ALERT,false));
        if(e.purchase!=null){
            TextView details=label("Состав покупки · "+e.purchase.items.size()+" поз.",13,ACCENT,true);
            details.setMinHeight(dp(48));details.setGravity(Gravity.CENTER_VERTICAL);
            details.setFocusable(true);details.setClickable(true);
            details.setOnClickListener(v->showPurchaseDetails(e));left.addView(details);
        }
        LinearLayout.LayoutParams leftParams=new LinearLayout.LayoutParams(0,-2,1);
        leftParams.rightMargin=dp(8);card.addView(left,leftParams);
        TextView sum=label((e.type.equals("payment")?"−":e.type.equals("debt")?"+":"↶ ")+money(e.amount),16,canceled?MUTED:color,true);
        if(canceled)sum.setPaintFlags(sum.getPaintFlags()|android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        card.addView(sum);
        return card;
    }
    private LinearLayout emptyState(int iconRes,String title,String subtitle,
                                    String actionLabel,Runnable action) {
        LinearLayout box=stack(5);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6),dp(20),dp(6),dp(12));
        box.setBackground(shape(BG,8,0));
        android.widget.ImageView image=new android.widget.ImageView(this);
        image.setImageResource(iconRes);
        image.setColorFilter(ACCENT);
        image.setPadding(dp(7),dp(7),dp(7),dp(7));
        image.setBackground(shape(PANEL,8,0));
        image.setContentDescription(null);
        box.addView(image,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView heading=label(title,16,TEXT,true);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading,margin(-1,-2,3));
        TextView details=label(subtitle,13,MUTED,false);
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
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(controlHeight()));
            params.topMargin=dp(5);
            box.addView(button,params);
        }
        return box;
    }

    private LinearLayout reminderSetting() {
        LinearLayout row=row();
        row.setPadding(dp(10),dp(6),dp(10),dp(6));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(shape(BG,8,0));
        LinearLayout details=stack(5);
        details.addView(label("Ежедневно проверять сроки",15,TEXT,true));
        details.addView(label("Локально, без SMS. Время выбирает Android.",12,MUTED,false));
        row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);
        toggle.setContentDescription("Ежедневные напоминания");
        toggle.setChecked(DebtReminderService.enabled(this));
        toggle.setOnCheckedChangeListener((button,checked)->{
            if(checked&&!DebtReminderService.hasPermission(this)){
                toggle.setChecked(false);
                if(Build.VERSION.SDK_INT>=33)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            NOTIFICATION_PERMISSION_REQUEST);
                return;
            }
            if(!DebtReminderService.setEnabled(this,checked)){
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(!checked);
                message("Не удалось изменить расписание напоминаний.");
            }else {
                message(checked?"Ежедневная проверка сроков включена.":"Напоминания отключены.");
            }
        });
        row.addView(toggle,new LinearLayout.LayoutParams(-2,dp(52)));
        return row;
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(request,permissions,result);
        if(request!=NOTIFICATION_PERMISSION_REQUEST)return;
        if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED){
            if(DebtReminderService.setEnabled(this,true))
                message("Напоминания включены. Android выберет время проверки.");
            else message("Не удалось запланировать напоминания.");
        }else message("Без разрешения уведомления остаются выключенными.");
        if(tab.equals("Настройки"))render();
    }
    private android.content.SharedPreferences recoveryPrefs() {
        return getSharedPreferences(RECOVERY_PREFS,MODE_PRIVATE);
    }
    private void createRecoveryPoint() {
        try{
            vault.create(ledger);
            message("Защищённая точка создана.");
            render();
        }catch(Exception e){message("Точка не создана: "+e.getMessage());}
    }
    private void restoreRecoveryPoint() {
        final String snapshot;
        try{snapshot=vault.readLatest();}
        catch(Exception ex){message("Не удалось прочитать точку: "+ex.getMessage());return;}
        LinearLayout form=stack(10);
        form.addView(label("Текущая книга будет заменена. Сначала автоматически создадим защищённую копию текущих записей. Для переноса между устройствами используйте внешний JSON.",14,ALERT,false));
        showDialog("Восстановить точку?",form,()->{
            vault.create(ledger); // Fail closed: do not replace if recovery point cannot be created.
            ledger.importJson(snapshot);
            directory.clearAfterRestore();
            getPreferences(MODE_PRIVATE).edit().remove("last_json_export").apply();
            selectedClient=null;tab="Клиенты";
            return true;
        });
    }
    private LinearLayout recoveryAutoSetting() {
        LinearLayout row=row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(6),dp(10),dp(6));
        row.setBackground(shape(BG,8,0));
        LinearLayout detail=stack(4);
        detail.addView(label("Автозащита после изменений",15,TEXT,true));
        detail.addView(label("Создаёт локальную зашифрованную точку после сохранения операции. Сохраняются последние четыре.",12,MUTED,false));
        row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);
        toggle.setChecked(recoveryPrefs().getBoolean("enabled",false));
        toggle.setContentDescription("Автозащита книги");
        toggle.setOnCheckedChangeListener((button,checked)->{
            recoveryPrefs().edit().putBoolean("enabled",checked).apply();
            if(checked)enqueueRecovery();
            message(checked?"Локальная автозащита включена.":"Локальная автозащита отключена.");
        });
        row.addView(toggle,new LinearLayout.LayoutParams(-2,dp(52)));
        return row;
    }
    private void enqueueRecovery() {
        if(!recoveryPrefs().getBoolean("enabled",false))return;
        android.content.Context app=getApplicationContext();
        RECOVERY_WORK.execute(()->{
            try(LedgerStore snapshotBook=new LedgerStore(app)){
                new RecoveryVault(app).create(snapshotBook);
                app.getSharedPreferences(RECOVERY_PREFS,MODE_PRIVATE).edit()
                    .putLong("last_success",System.currentTimeMillis())
                    .remove("last_error").apply();
            }catch(Exception e){
                app.getSharedPreferences(RECOVERY_PREFS,MODE_PRIVATE).edit()
                    .putString("last_error","Не удалось создать локальную копию").apply();
            }
        });
    }
    private LinearLayout textSizeSetting() {
        LinearLayout row=row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(6),dp(10),dp(6));
        row.setBackground(shape(BG,8,0));
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

    private void applyPrivacyFlag(){
        if(privacy.hideScreens())getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void authenticate(int request){
        if(authInFlight)return;
        Intent credential=privacy.credential();
        if(credential==null){
            message("Настройте код блокировки устройства в Android.");
            if(request==BookPrivacy.OPEN)finishAndRemoveTask();
            return;
        }
        try{
            authInFlight=true;
            startActivityForResult(credential,request);
        }catch(ActivityNotFoundException e){
            authInFlight=false;message("Системное подтверждение недоступно.");
            if(request==BookPrivacy.OPEN)finishAndRemoveTask();
        }
    }
    @Override protected void onStart(){
        super.onStart();
        if(privacy!=null&&BookPrivacy.shouldRelock(privacy.enabled(),stoppedAt,android.os.SystemClock.elapsedRealtime())){
            unlocked=false;
            if(chooser!=null)chooser.dismiss();
            if(root!=null)root.setVisibility(View.INVISIBLE);
        }
        stoppedAt=-1;
    }
    @Override protected void onResume(){
        super.onResume();
        if(privacy!=null&&!unlocked&&!authInFlight&&!isFinishing()){
            if(chooser!=null)chooser.dismiss();
            authenticate(BookPrivacy.OPEN);
        }
    }
    @Override protected void onStop(){
        if(privacy!=null&&!authInFlight)stoppedAt=android.os.SystemClock.elapsedRealtime();
        super.onStop();
    }
    private void showQuickDebt(){showQuickDebt(false);}
    private void showQuickDebt(boolean fromClients){showQuickDebt(fromClients,LocalDate.now());}
    private void showQuickDebt(boolean fromClients,LocalDate initialDay){
        LinearLayout form=stack(5);
        EditText name=field("Имя покупателя",null);
        EditText phone=field("Телефон (необязательно)",null);phone.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText amount=field("0,00",null);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(formGroup("Имя покупателя",name));
        form.addView(formGroup("Телефон (необязательно)",phone));
        LinearLayout matches=stack(2);form.addView(matches);
        form.addView(formGroup(fromClients?"Сумма долга, ₽ (необязательно)":"Сумма долга, ₽",amount));
        if(fromClients)form.addView(label("Без суммы сохраняется только клиент.",12,MUTED,false));
        LocalDate[] day={initialDay};
        TextView date=debtDateField(day);
        form.addView(formGroup("Дата записи",date));
        LinearLayout extra=stack(5);
        EditText due=field("Без срока",null);due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(LocalDate.of(y,m+1,d).toString()),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        extra.addView(formGroup("Вернуть до (необязательно)",due));
        LinearLayout clear=stack(0);addButton(clear,"Без срока",false,()->due.setText(""),1);
        clear.setVisibility(View.GONE);due.addTextChangedListener(watcher(v->clear.setVisibility(v.isEmpty()?View.GONE:View.VISIBLE)));
        extra.addView(clear);
        EditText note=field("Комментарий",null);extra.addView(formGroup("Комментарий (необязательно)",note));
        LinearLayout toggle=stack(0);
        addButton(toggle,"Срок и комментарий",false,()->{
            boolean open=extra.getVisibility()!=View.VISIBLE;
            extra.setVisibility(open?View.VISIBLE:View.GONE);
            TextView button=(TextView)toggle.getChildAt(0);
            String caption=open?"Скрыть дополнительные поля":"Срок и комментарий";
            button.setText(caption);button.setContentDescription(caption);
        },1);
        form.addView(toggle);extra.setVisibility(View.GONE);form.addView(extra);
        List<LedgerStore.Client> customers=ledger.clients();
        List<LedgerStore.Entry> snapshot=ledger.entries();
        Map<String,Long> lastAmounts=RecentDebts.lastAmounts(snapshot);
        List<CollectionsInsights.CustomerStatus> statuses=CollectionsInsights.analyze(customers,snapshot,LocalDate.now());
        Map<String,Long> balances=new HashMap<>();
        for(CollectionsInsights.CustomerStatus status:statuses)balances.put(status.client.id,status.balance);
        String[] selected={null};
        boolean[] updating={false},separate={false};
        java.util.function.Consumer<LedgerStore.Client> choose=customer->{
            updating[0]=true;selected[0]=customer.id;
            name.setText(customer.name);phone.setText(customer.phone);updating[0]=false;
            matches.removeAllViews();
            matches.addView(label("Выбран существующий клиент · "+money(balances.getOrDefault(customer.id,0L)),12,ACCENT,true));
            addRecentAmount(matches,amount,lastAmounts.getOrDefault(customer.id,0L));
        };
        Runnable refresh=()->{
            matches.removeAllViews();
            if(selected[0]!=null){
                matches.addView(label("Выбран существующий клиент",12,ACCENT,true));
                addRecentAmount(matches,amount,lastAmounts.getOrDefault(selected[0],0L));return;
            }
            String query=name.getText().toString().trim().toLowerCase(new Locale("ru","RU"));
            String digits=phone.getText().toString().replaceAll("[^0-9]","");
            if(query.isEmpty()&&digits.length()<3){
                if(!digits.isEmpty())return;
                List<LedgerStore.Client> recent=new ArrayList<>();
                Map<String,LedgerStore.Client> byId=new HashMap<>();
                for(LedgerStore.Client customer:customers)byId.put(customer.id,customer);
                for(String id:lastAmounts.keySet()){
                    LedgerStore.Client customer=byId.get(id);
                    if(customer!=null&&!directory.archived(id,balances.getOrDefault(id,0L)))recent.add(customer);
                    if(recent.size()>=3)break;
                }
                if(!recent.isEmpty())addButton(matches,"Недавние клиенты",false,()->{
                    matches.removeAllViews();
                    for(LedgerStore.Client customer:recent)
                        actionRow(matches,"›",customer.name,money(balances.getOrDefault(customer.id,0L)),()->choose.accept(customer));
                },1);
                return;
            }
            int count=0;
            for(LedgerStore.Client customer:customers){
                String n=customer.name.toLowerCase(new Locale("ru","RU"));
                String d=customer.phone.replaceAll("[^0-9]","");
                if(!(!query.isEmpty()&&n.contains(query))&&!(digits.length()>=3&&d.contains(digits)))continue;
                if(count++>=5)break;
                boolean archived=directory.archived(customer.id,balances.getOrDefault(customer.id,0L));
                String subtitle=(customer.phone.isEmpty()?"Без телефона":customer.phone)+" · "+money(balances.getOrDefault(customer.id,0L));
                if(archived){matches.addView(label(customer.name+" · в архиве",13,ALERT,false));continue;}
                actionRow(matches,"›",customer.name,subtitle,()->choose.accept(customer));
            }
            if(hasExactCustomer(customers,name.getText().toString(),phone.getText().toString())){
                TextView warning=label(separate[0]?"Будет создан отдельный клиент":"Есть совпадение. Выберите клиента или подтвердите создание отдельного.",12,ALERT,false);
                matches.addView(warning);
                if(!separate[0])addButton(matches,"Создать отдельного клиента",false,()->{
                    separate[0]=true;warning.setText("Будет создан отдельный клиент");
                },1);
            }
        };
        name.addTextChangedListener(watcher(v->{if(!updating[0]){selected[0]=null;separate[0]=false;refresh.run();}}));
        phone.addTextChangedListener(watcher(v->{if(!updating[0]){selected[0]=null;separate[0]=false;refresh.run();}}));
        refresh.run();
        showDialog(fromClients?"Новый клиент и долг":"Новый долг",form,()->{
            String n=name.getText().toString(),ph=phone.getText().toString();
            if(selected[0]==null&&!separate[0]&&hasExactCustomer(ledger.clients(),n,ph))
                throw new IllegalArgumentException("Выберите существующего клиента или подтвердите создание отдельного.");
            if(selected[0]!=null&&directory.archived(selected[0],LedgerStore.balance(ledger.entries(),selected[0])))
                throw new IllegalArgumentException("Сначала верните клиента из архива.");
            if(fromClients&&amount.getText().toString().trim().isEmpty()){
                if(selected[0]!=null)throw new IllegalArgumentException("Клиент уже существует. Введите сумму долга.");
                if(due.length()>0||!note.getText().toString().trim().isEmpty())throw new IllegalArgumentException("Введите сумму долга или очистите срок и комментарий.");
                ledger.addClient(n,ph);
                tab="Клиенты";selectedClient=null;clientScope=ClientDirectory.ACTIVE;
                clientFilter=CollectionsInsights.ALL;search="";clientVisibleLimit=60;
                return true;
            }
            // Determine how many dates to reveal before the transaction; no potentially
            // failing database reads after a successful save.
            int rank=1;
            for(BookDays.Day existing:BookDays.group(ledger.entries(),ZoneId.systemDefault()))
                if(existing.date.isAfter(day[0]))rank++;
            ledger.recordDatedDebt(selected[0],n,ph,LedgerStore.parseAmount(amount.getText().toString()),day[0],due.getText().toString(),note.getText().toString().trim());
            bookPayments=false;clearBookFilters();
            expandedBookDays.add(day[0].toString());
            bookDayLimit=Math.max(bookDayLimit,rank);
            if(fromClients){
                tab="Клиенты";clientScope=ClientDirectory.ACTIVE;clientFilter=CollectionsInsights.ALL;
                search="";clientVisibleLimit=60;
            }else tab="Главная";
            selectedClient=null;return true;
        });
    }
    private void addRecentAmount(LinearLayout into,EditText amount,long cents){
        if(cents>0)addButton(into,"Повторить сумму: "+money(cents),false,()->amount.setText(amountInput(cents)),1);
    }
    private TextView debtDateField(LocalDate[] day){
        TextView date=label(bookDateFormat.format(day[0]),15,TEXT,true);
        date.setPadding(dp(11),0,dp(11),0);date.setGravity(Gravity.CENTER_VERTICAL);
        date.setContentDescription("Дата записи: "+bookDateFormat.format(day[0]));
        date.setBackground(ripple(shape(ELEVATED,6,BORDER)));date.setFocusable(true);
        date.setOnClickListener(v->{
            DatePickerDialog picker=new DatePickerDialog(this,(widget,y,m,d)->{
                day[0]=LocalDate.of(y,m+1,d);date.setText(bookDateFormat.format(day[0]));
                date.setContentDescription("Дата записи: "+bookDateFormat.format(day[0]));
            },day[0].getYear(),day[0].getMonthValue()-1,day[0].getDayOfMonth());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();
        });
        return date;
    }
    private static boolean hasExactCustomer(List<LedgerStore.Client> people,String name,String phone){
        String n=name.trim(),d=phone.replaceAll("[^0-9]","");
        for(LedgerStore.Client c:people){
            if(!n.isEmpty()&&c.name.trim().equalsIgnoreCase(n))return true;
            if(d.length()>=7&&d.equals(c.phone.replaceAll("[^0-9]","")))return true;
        }
        return false;
    }
    private void chooseClient(String type) {
        if(type.equals("debt")){showQuickDebt();return;}
        List<LedgerStore.Client> customers=ledger.clients();
        if(customers.isEmpty()){message("Пока нет клиентов с долгом.");return;}
        LinearLayout content=stack(8);
        List<CollectionsInsights.CustomerStatus> statuses=directory.select(
                CollectionsInsights.analyze(customers,ledger.entries(),LocalDate.now()),ClientDirectory.ACTIVE);
        EditText lookup=field("Имя или номер телефона",null);
        lookup.setSingleLine(true);
        content.addView(lookup);
        LinearLayout results=stack(6);content.addView(results);
        int[] limit={60};
        Runnable fill=new Runnable(){
            @Override public void run(){
                results.removeAllViews();
                List<CollectionsInsights.CustomerStatus> found=CollectionsInsights.visible(statuses,
                        type.equals("payment")?CollectionsInsights.OWING:CollectionsInsights.ALL,false,lookup.getText().toString());
                int count=0;
                for(CollectionsInsights.CustomerStatus status:found){
                    if(count++>=limit[0])break;
                    LedgerStore.Client customer=status.client;
                    TextView button=label(customer.name+"     "+money(status.balance),16,TEXT,false);
                    button.setPadding(dp(14),dp(16),dp(14),dp(16));
                    button.setBackground(ripple(shape(ELEVATED,6,BORDER)));
                    button.setContentDescription("Выбрать · "+customer.name);
                    button.setFocusable(true);
                    results.addView(button);
                    button.setOnClickListener(v->{if(chooser!=null)chooser.dismiss();showEntryForm(customer.id,type);});
                }
                if(found.isEmpty())results.addView(label("Нет подходящих клиентов.",14,MUTED,false));
                if(found.size()>limit[0])addButton(results,"Показать ещё клиентов",false,()->{limit[0]+=60;run();},1);
            }
        };
        lookup.addTextChangedListener(watcher(value->{limit[0]=60;fill.run();}));
        fill.run();
        showDialog("Выберите клиента",content,null);
    }
    private Dialog chooser;
    private void showClientForm(LedgerStore.Client client) {
        if(client==null){showQuickDebt(true);return;}
        LinearLayout content=stack(12);
        EditText name=field("Имя клиента",client==null?"":client.name);
        EditText phone=field("Телефон (необязательно)",client==null?"":client.phone);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        content.addView(formGroup("Имя",name));content.addView(formGroup("Телефон",phone));
        TextView duplicateWarning=label("",13,ALERT,false);
        duplicateWarning.setVisibility(View.GONE);
        content.addView(duplicateWarning);
        List<LedgerStore.Client> existing=ledger.clients();
        Runnable detectDuplicates=()->{
            String enteredName=name.getText().toString().trim();
            String digits=phone.getText().toString().replaceAll("[^0-9]","");
            String possible=null;
            for(LedgerStore.Client other:existing){
                if(client!=null&&other.id.equals(client.id))continue;
                String phoneDigits=other.phone.replaceAll("[^0-9]","");
                boolean samePhone=digits.length()>=7 && digits.equals(phoneDigits);
                boolean sameName=!enteredName.isEmpty() && other.name.trim().equalsIgnoreCase(enteredName);
                if(samePhone||sameName){possible=other.name;break;}
            }
            if(possible==null)duplicateWarning.setVisibility(View.GONE);
            else{
                duplicateWarning.setText("Похожий клиент уже есть: "+possible+
                        ". Проверьте, не создаёте ли вы дубль.");
                duplicateWarning.setVisibility(View.VISIBLE);
            }
        };
        name.addTextChangedListener(watcher(value->detectDuplicates.run()));
        phone.addTextChangedListener(watcher(value->detectDuplicates.run()));
        detectDuplicates.run();
        showDialog("Изменить клиента",content,()->{
            ledger.editClient(client.id,name.getText().toString(),phone.getText().toString());
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
    private final class PurchaseRow {
        final EditText name,quantity,price;
        final TextView subtotal;
        View removeAction;
        final LinearLayout block;
        PurchaseRow(){
            block=stack(4);
            name=field("Название товара",null);
            quantity=field("1","1");
            price=field("0,00",null);
            name.setSingleLine(true);
            quantity.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            price.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
            block.addView(formGroup("Товар",name));
            LinearLayout numbers=row();
            numbers.addView(formGroup("Количество",quantity),new LinearLayout.LayoutParams(0,-2,1));
            numbers.addView(formGroup("Цена, ₽",price),new LinearLayout.LayoutParams(0,-2,1));
            block.addView(numbers);
            subtotal=label("Сумма позиции: —",14,ACCENT,true);
            block.addView(subtotal);
        }
        Purchase.Item item(){
            return new Purchase.Item(name.getText().toString(),Purchase.quantity(quantity.getText().toString()),
                    LedgerStore.parseAmount(price.getText().toString()));
        }
    }
    private void showPurchaseForm(String clientId){
        LedgerStore.Client client=null;
        for(LedgerStore.Client c:ledger.clients())if(c.id.equals(clientId)){client=c;break;}
        if(client==null){message("Клиент не найден.");return;}
        if(directory.archived(clientId,LedgerStore.balance(ledger.entries(),clientId))){
            message("Сначала верните клиента из архива.");return;
        }
        LinearLayout form=stack(7);
        form.addView(label(client.name+" · товары в долг",14,TEXT,true));
        LinearLayout rows=stack(6);
        List<PurchaseRow> items=new ArrayList<>();
        EditText discount=field("0,00",null);
        discount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout summary=stack(2);
        TextView total=label("Итого к записи: —",20,ACCENT,true);
        TextView details=label("Заполните товары и цены.",13,MUTED,false);
        summary.addView(total);summary.addView(details);
        // Keep the current total visible at the top of the scrollable form.
        form.addView(summary);addListDivider(form);form.addView(rows);
        Runnable update=()->{
            List<Purchase.Item> valid=new ArrayList<>();
            boolean complete=!items.isEmpty();
            for(PurchaseRow r:items){
                if(r.removeAction!=null)r.removeAction.setVisibility(items.size()>1?View.VISIBLE:View.GONE);
                try{Purchase.Item item=r.item();valid.add(item);r.subtotal.setText("Сумма позиции: "+money(item.total));}
                catch(Exception ex){complete=false;r.subtotal.setText("Сумма позиции: —");}
            }
            try{
                if(!complete)throw new IllegalArgumentException("Заполните все позиции.");
                Purchase purchase=new Purchase(valid,Purchase.discount(discount.getText().toString()));
                total.setText("Итого к записи: "+money(purchase.total));
                details.setText("Товары "+money(purchase.subtotal)+" · скидка "+money(purchase.discount));
            }catch(Exception ex){total.setText("Итого к записи: —");details.setText(ex.getMessage());}
        };
        Runnable add=()->{
            if(items.size()>=Purchase.MAX_ITEMS){message("Максимум 8 позиций в одной покупке.");return;}
            PurchaseRow r=new PurchaseRow();items.add(r);rows.addView(r.block,margin(-1,-2,8));
            addButton(r.block,"Удалить позицию",false,()->{
                if(items.size()==1){message("Оставьте хотя бы одну позицию.");return;}
                items.remove(r);rows.removeView(r.block);update.run();
            },1);
            r.removeAction=r.block.getChildAt(r.block.getChildCount()-1);
            r.name.addTextChangedListener(watcher(v->update.run()));
            r.quantity.addTextChangedListener(watcher(v->update.run()));
            r.price.addTextChangedListener(watcher(v->update.run()));
            update.run();
        };
        addButton(form,"+ Добавить товар",false,add,1);
        form.addView(formGroup("Скидка на покупку, ₽",discount));
        discount.addTextChangedListener(watcher(v->update.run()));
        EditText due=field("Без срока",null);
        due.setFocusable(false);due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(LocalDate.of(y,m+1,d).toString()),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        form.addView(formGroup("Вернуть до (необязательно)",due));
        LinearLayout clearPurchaseDue=stack(0);
        addButton(clearPurchaseDue,"Без срока",false,()->due.setText(""),1);
        clearPurchaseDue.setVisibility(View.GONE);
        due.addTextChangedListener(watcher(v->clearPurchaseDue.setVisibility(v.isEmpty()?View.GONE:View.VISIBLE)));
        form.addView(clearPurchaseDue);
        EditText note=field("Комментарий",null);
        form.addView(formGroup("Комментарий (необязательно)",note));
        form.addView(label("При закрытии незаписанный состав покупки не сохраняется.",12,MUTED,false));
        add.run();
        showDialog("Покупка в долг",form,()->{
            List<Purchase.Item> valid=new ArrayList<>();
            for(PurchaseRow r:items)valid.add(r.item());
            Purchase purchase=new Purchase(valid,Purchase.discount(discount.getText().toString()));
            if(directory.archived(clientId,LedgerStore.balance(ledger.entries(),clientId)))
                throw new IllegalArgumentException("Сначала верните клиента из архива.");
            ledger.addPurchase(clientId,purchase,due.getText().toString(),note.getText().toString().trim());
            selectedClient=clientId;tab="Клиенты";return true;
        });
    }
    private void showPurchaseDetails(LedgerStore.Entry entry){
        Purchase p=entry.purchase;
        if(p==null)return;
        LinearLayout form=stack(10);
        for(Purchase.Item item:p.items){
            LinearLayout row=card();
            row.addView(label(item.name,16,TEXT,true));
            row.addView(label(item.quantity()+(item.unit.isEmpty()?"":" "+item.unit)+" × "+money(item.unitPrice),14,MUTED,false));
            row.addView(label(money(item.total),18,ACCENT,true));
            form.addView(row);
        }
        form.addView(label("Товары: "+money(p.subtotal),15,TEXT,false));
        form.addView(label("Скидка: "+money(p.discount),15,MUTED,false));
        form.addView(label("Итого: "+money(p.total),24,ACCENT,true));
        showDialog("Состав покупки",form,null);
    }
    private void showEntryForm(String clientId,String type) {
        showEntryForm(clientId,type,0L);
    }
    private void showEntryForm(String clientId,String type,long suggestedCents) {
        LedgerStore.Client c=null;
        for(LedgerStore.Client person:ledger.clients())if(person.id.equals(clientId))c=person;
        if(c==null){message("Клиент не найден.");return;}
        final long available=LedgerStore.balance(ledger.entries(),clientId);
        final boolean fromBook=tab.equals("Главная");
        if(directory.archived(clientId,available)){message("Сначала верните клиента из архива.");return;}
        if(type.equals("payment")&&available<=0){message("Нет долга для оплаты.");return;}
        LinearLayout content=stack(7);
        content.addView(label(c.name+" · остаток "+money(available),13,MUTED,false));
        LedgerDrafts drafts=new LedgerDrafts(this);
        LedgerDrafts.Draft previous=drafts.get(clientId,type);
        boolean savedDraft=drafts.has(clientId,type);
        String preset=suggestedCents>0&&type.equals("payment")
                ? String.format(Locale.ROOT,"%d,%02d",suggestedCents/100,suggestedCents%100)
                : savedDraft?previous.amount:null;
        if(savedDraft&&suggestedCents==0)
            content.addView(label("Незавершённый черновик восстановлен.",12,ACCENT,false));
        EditText amount=field("0,00",preset);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        content.addView(formGroup("Сумма, ₽",amount));
        if(type.equals("debt"))addRecentAmount(content,amount,RecentDebts.lastAmounts(ledger.entries()).getOrDefault(clientId,0L));
        if(type.equals("payment")){
            LinearLayout amounts=row();
            addButton(amounts,"Половина",false,()->amount.setText(amountInput(Math.max(1L,available/2))),1);
            addButton(amounts,"Весь остаток",false,()->amount.setText(amountInput(available)),1);
            content.addView(amounts,margin(-1,dp(controlHeight()),0));
        }
        TextView projected=label("Остаток после записи: —",16,ACCENT,true);
        projected.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(projected);
        Runnable updateProjection=()->{
            try{
                long cents=LedgerStore.parseAmount(amount.getText().toString());
                long next=type.equals("payment")?available-cents:available+cents;
                if(next<0){projected.setText("Оплата превышает остаток");projected.setTextColor(RED);}
                else{projected.setText("Остаток после записи: "+money(next));projected.setTextColor(ACCENT);}
            }catch(Exception e){projected.setText("Остаток после записи: —");projected.setTextColor(MUTED);}
        };
        amount.addTextChangedListener(watcher(v->updateProjection.run()));
        updateProjection.run();
        LocalDate[] entryDay={LocalDate.now()};
        if(type.equals("debt"))content.addView(formGroup("Дата записи",debtDateField(entryDay)));
        EditText due=field("Без срока",savedDraft?previous.due:null);
        due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(String.format(Locale.ROOT,"%04d-%02d-%02d",y,m+1,d)),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        if(type.equals("debt")){
            content.addView(formGroup("Вернуть до (необязательно)",due));
            LinearLayout clearDue=stack(0);
            addButton(clearDue,"Без срока",false,()->due.setText(""),1);
            clearDue.setVisibility(due.getText().length()==0?View.GONE:View.VISIBLE);
            due.addTextChangedListener(watcher(v->clearDue.setVisibility(v.isEmpty()?View.GONE:View.VISIBLE)));
            content.addView(clearDue);
        }
        EditText note=field("Комментарий",savedDraft?previous.note:null);
        content.addView(formGroup("Комментарий (необязательно)",note));
        Runnable persist=()->drafts.save(clientId,type,
                amount.getText().toString(),due.getText().toString(),note.getText().toString());
        amount.addTextChangedListener(watcher(value->persist.run()));
        due.addTextChangedListener(watcher(value->persist.run()));
        note.addTextChangedListener(watcher(value->persist.run()));
        if(savedDraft){
            addButton(content,"Очистить черновик",false,()->{
                amount.setText("");due.setText("");note.setText("");
                drafts.clear(clientId,type);
                message("Черновик очищен.");
            },1);
        }
        showDialog(type.equals("debt")?"Новый долг":"Принять оплату",content,()->{
            long cents=LedgerStore.parseAmount(amount.getText().toString());
            if(type.equals("debt")){
                if(directory.archived(clientId,LedgerStore.balance(ledger.entries(),clientId)))
                    throw new IllegalArgumentException("Сначала верните клиента из архива.");
                int rank=1;
                for(BookDays.Day existing:BookDays.group(ledger.entries(),ZoneId.systemDefault()))
                    if(existing.date.isAfter(entryDay[0]))rank++;
                ledger.recordDatedDebt(clientId,"","",cents,entryDay[0],due.getText().toString(),note.getText().toString().trim());
                bookPayments=false;clearBookFilters();bookDayLimit=Math.max(bookDayLimit,rank);
                expandedBookDays.add(entryDay[0].toString());
            }else ledger.addEntry(clientId,type,cents,"",note.getText().toString().trim());
            drafts.clear(clientId,type);
            if(type.equals("payment")&&fromBook){
                bookPayments=true;clearBookFilters();expandedBookDays.clear();
                expandedBookDays.add(LocalDate.now().toString());selectedClient=null;tab="Главная";
            }else if(fromBook){selectedClient=null;tab="Главная";}
            else{selectedClient=clientId;tab="Клиенты";}
            return true;
        });
    }
    private static String amountInput(long cents){return String.format(Locale.ROOT,"%d,%02d",cents/100,cents%100);}
    private void exportCustomerPdf(String clientId) {
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/pdf");
        save.putExtra(Intent.EXTRA_TITLE,"raschet-statement-"+LocalDate.now()+".pdf");
        try{
            pendingPdfClientId=clientId;
            startActivityForResult(save,REQUEST_PDF);
        }catch(Exception ex){pendingPdfClientId=null;message("Нет выбора файла PDF: "+ex.getMessage());}
    }
    private void shareClientSummary(LedgerStore.Client client,long amount,long overdue) {
        // Explicit user action: Sharesheet does not auto-send or access contacts.
        // The phone number is intentionally excluded from the generated text.
        StringBuilder body=new StringBuilder("Расчёт · ").append(shopName)
                .append("\nКлиент: ").append(client.name)
                .append("\nОстаток долга: ").append(money(amount));
        if(overdue>0)body.append("\nПросрочено: ").append(money(overdue));
        body.append("\nСводка на ").append(LocalDate.now())
                .append("\nПроверьте данные и получателя перед отправкой.");
        Intent share=new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,body.toString());
        try{startActivity(Intent.createChooser(share,"Поделиться сводкой клиента"));}
        catch(ActivityNotFoundException e){message("Нет приложения для отправки сводки.");}
    }
    private void showCorrection(String clientId) {
        LedgerStore.Entry original=ledger.lastActive(clientId);
        if(original==null){message("Нет действующей записи для исправления.");return;}
        LinearLayout content=stack(10);
        content.addView(label("Можно исправить только последнюю действующую запись. Старая сумма и причина останутся в истории.",13,MUTED,false));
        String formatted=String.format(Locale.ROOT,"%d,%02d",
                original.amount/100,original.amount%100);
        EditText amount=field("Сумма",formatted);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(original.purchase!=null){
            amount.setEnabled(false);
            content.addView(label("Сумма связана с товарами. Для изменения состава отмените последнюю покупку и запишите новую.",13,ALERT,false));
        }
        content.addView(formGroup("Исправленная сумма, ₽",amount));
        EditText due=field("Без срока",original.due);
        due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(
                    String.format(Locale.ROOT,"%04d-%02d-%02d",y,m+1,d)),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        if(original.type.equals("debt"))content.addView(formGroup("Вернуть до",due));
        EditText note=field("Комментарий",original.note);
        content.addView(formGroup("Комментарий",note));
        EditText reason=field("Почему исправлено",null);
        content.addView(formGroup("Причина исправления (обязательно)",reason));
        showDialog("Исправить последнюю запись",content,()->{
            ledger.correctLast(clientId,LedgerStore.parseAmount(amount.getText().toString()),
                    original.type.equals("debt")?due.getText().toString():"",
                    note.getText().toString().trim(),reason.getText().toString());
            selectedClient=clientId;tab="Клиенты";return true;
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
        LinearLayout box=stack(7);
        box.setPadding(dp(14),dp(14),dp(14),dp(14));
        box.setBackground(shape(PANEL,12,BORDER));
        box.addView(label(title,20,TEXT,true));
        addListDivider(box);
        ScrollView scroll=new ScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                android.graphics.Rect visible=new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(visible);
                int viewport=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;
                int limit=Math.max(dp(120),viewport-dp(180));
                if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)
                    limit=Math.min(limit,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.addView(form);
        box.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        TextView error=label("",12,RED,false);
        error.setVisibility(View.GONE);
        box.addView(error);
        LinearLayout buttons=row();
        addButton(buttons,"Закрыть",false,dialog::dismiss,1);
        if(save!=null)addButton(buttons,"Сохранить",true,()->{
            if(saving)return;
            saving=true;
            try{if(save.save()){dialog.dismiss();render();enqueueRecovery();message("Сохранено.");}}
            catch(Exception ex){error.setText(ex.getMessage()==null?"Не удалось сохранить запись.":ex.getMessage());error.setVisibility(View.VISIBLE);}
            finally{saving=false;}
        },1);
        addListDivider(box);
        box.addView(buttons,new LinearLayout.LayoutParams(-1,dp(controlHeight())));
        dialog.setContentView(box);
        Window window=dialog.getWindow();
        if(window!=null){
            if(privacy.hideScreens())window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(510)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        chooser=dialog;
        dialog.show();
        if(window!=null)window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(510)),
                WindowManager.LayoutParams.WRAP_CONTENT);
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
        StringBuilder out=new StringBuilder("\uFEFFКлиент;Телефон;Дата;Тип;Сумма (руб);Срок;Комментарий;ID;Отменяет ID;Состав покупки\r\n");
        Map<String,LedgerStore.Client> people=new HashMap<>();
        for(LedgerStore.Client c:ledger.clients())people.put(c.id,c);
        for(LedgerStore.Entry e:ledger.entries()){
            LedgerStore.Client c=people.get(e.clientId);
            for(String cell:new String[]{c==null?"":c.name,c==null?"":c.phone,e.date,e.type,
                    String.format(Locale.ROOT,"%.2f",e.amount/100d).replace('.',','),e.due,e.note,e.id,e.reverses,e.purchase==null?"":e.purchase.summary()})
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
        if(request==BookPrivacy.OPEN||request==BookPrivacy.ENABLE||request==BookPrivacy.DISABLE){
            authInFlight=false;
            if(result!=RESULT_OK){
                if(request==BookPrivacy.OPEN)finishAndRemoveTask();
                return;
            }
            if(request==BookPrivacy.ENABLE)privacy.setEnabled(true);
            if(request==BookPrivacy.DISABLE)privacy.setEnabled(false);
            unlocked=true;stoppedAt=-1;applyPrivacyFlag();render();return;
        }
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
        }else if(request==REQUEST_REPORT){
            LocalDate from=pendingReportFrom,to=pendingReportTo;
            pendingReportFrom=null;pendingReportTo=null;
            if(from==null||to==null||result!=RESULT_OK||data==null||data.getData()==null)return;
            try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){
                if(out==null)throw new IOException("Нет доступа к файлу.");
                out.write(PeriodReport.build(ledger.entries(),from,to,ZoneId.systemDefault()).csv().getBytes(StandardCharsets.UTF_8));
                out.flush();message("Отчёт CSV сохранён.");
            }catch(Exception ex){message("Ошибка отчёта: "+ex.getMessage());}
        }else if(request==REQUEST_PDF){
            String id=pendingPdfClientId;
            pendingPdfClientId=null;
            if(id==null||result!=RESULT_OK||data==null||data.getData()==null)return;
            LedgerStore.Client client=null;
            for(LedgerStore.Client item:ledger.clients())if(item.id.equals(id)){client=item;break;}
            if(client==null){message("Клиент не найден.");return;}
            try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){
                if(out==null)throw new IOException("Не удалось открыть PDF-файл.");
                CustomerStatementPdf.write(out,shopName,client,ledger.entries());
                out.flush();
                message("Выписка PDF сохранена.");
            }catch(Exception ex){message("Ошибка PDF: "+ex.getMessage());}
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
                    // Import is destructive. Keep an authenticated encrypted rollback point first.
                    // If creating this point fails, abort before touching SQLite.
                    vault.create(ledger);
                    int[] count=ledger.importJson(backup);
                    directory.clearAfterRestore();
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
        if(!unlocked){finishAndRemoveTask();return;}
        if(selectedClient!=null){selectedClient=null;tab="Клиенты";render();}
        else if(!tab.equals("Главная")){tab="Главная";render();}
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {if(ledger!=null)ledger.close();super.onDestroy();}
    private void message(String m) {Toast.makeText(this,m,Toast.LENGTH_LONG).show();}

    private LinearLayout screen(String title,String subtitle) {
        LinearLayout result=stack(0);
        result.setPadding(dp(pad),dp(8),dp(pad),dp(12));
        result.addView(label(title,largeText?24:20,TEXT,true),margin(-1,-2,subtitle.isEmpty()?8:2));
        if(!subtitle.isEmpty())result.addView(label(subtitle,12,MUTED,false),margin(-1,-2,6));
        return result;
    }
    private ScrollView scroll(View content) {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        // Constrain to the actual viewport, including tablet panes and dialogs.
        // The full display width is not the available width of an individual pane.
        LinearLayout center=new LinearLayout(this){
            @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
                if(getChildCount()>0){
                    View child=getChildAt(0);
                    LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)child.getLayoutParams();
                    int width=View.MeasureSpec.getSize(widthMeasureSpec);
                    params.width=View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED
                            ? -1 : Math.min(width,dp(800));
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec);
            }
        };
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.addView(content,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(center);
        return scroll;
    }
    private LinearLayout formGroup(String title,View child) {
        LinearLayout group=stack(4);
        group.addView(label(title,13,MUTED,true));
        group.addView(child,new LinearLayout.LayoutParams(-1,dp(controlHeight())));
        return group;
    }
    private EditText field(String hint,String value) {
        EditText edit=new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(largeText?17:15);
        edit.setTextColor(TEXT);edit.setHintTextColor(MUTED);
        edit.setHint(hint);if(value!=null)edit.setText(value);
        edit.setPadding(dp(11),0,dp(11),0);edit.setMinimumHeight(dp(48));
        android.graphics.drawable.StateListDrawable focusBackground=new android.graphics.drawable.StateListDrawable();
        focusBackground.addState(new int[]{android.R.attr.state_focused},shape(ELEVATED,6,ACCENT));
        focusBackground.addState(new int[]{},shape(ELEVATED,6,BORDER));
        edit.setBackground(focusBackground);edit.setPadding(dp(11),0,dp(11),0);
        edit.setSelectAllOnFocus(false);
        return edit;
    }
    private LinearLayout balancePanel(String caption,long amount){
        LinearLayout panel=stack(3);
        panel.setPadding(dp(10),dp(8),dp(10),dp(8));
        panel.setBackground(shape(PANEL,8,0));
        panel.addView(label(caption,13,MUTED,false));
        TextView value=label(money(amount),largeText?34:32,TEXT,true);
        value.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        panel.addView(value);
        return panel;
    }
    private void metricRow(LinearLayout into,String title,String value,int color,Runnable action){
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(controlHeight()));line.setPadding(dp(4),dp(5),dp(4),dp(5));
        line.setBackground(ripple(shape(BG,6,0)));
        TextView caption=label(title,14,TEXT,true);
        line.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        TextView amount=label(value,14,color,true);amount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(0,-2,1);vp.leftMargin=dp(6);
        line.addView(amount,vp);
        TextView arrow=label("›",20,MUTED,false);arrow.setPadding(dp(8),0,0,0);line.addView(arrow);
        line.setContentDescription(title);line.setFocusable(true);line.setOnClickListener(v->action.run());
        into.addView(line);addListDivider(into);
    }
    private void sectionLink(LinearLayout into,String title,String actionLabel,Runnable action){
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(label(title,16,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView link=label(actionLabel,13,ACCENT,true);link.setGravity(Gravity.CENTER);
        link.setMinHeight(dp(48));link.setPadding(dp(8),0,dp(4),0);
        link.setBackground(ripple(shape(BG,6,0)));link.setFocusable(true);
        link.setOnClickListener(v->action.run());line.addView(link,new LinearLayout.LayoutParams(-2,dp(controlHeight())));
        into.addView(line);
    }
    private void section(LinearLayout into,String title,String note) {
        into.addView(label(title,17,TEXT,true),margin(-1,-2,6));
        if(!note.isEmpty())into.addView(label(note,12,MUTED,false),margin(-1,-2,4));
    }
    private void actionRow(LinearLayout into,String icon,String title,String subtitle,Runnable action) {
        LinearLayout block=row();block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(dp(10),dp(6),dp(10),dp(6));block.setMinimumHeight(dp(48));
        block.setBackground(ripple(shape(BG,8,0)));
        TextView emblem=label(icon,18,ACCENT,true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(shape(BG,0,0));
        block.addView(emblem,new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout details=stack(2);
        details.addView(label(title,14,TEXT,true));
        details.addView(label(subtitle,12,MUTED,false));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(8);p.rightMargin=dp(8);
        block.addView(details,p);
        block.addView(label("›",24,MUTED,false));
        block.setClickable(true);
        block.setFocusable(true);
        block.setOnClickListener(v->action.run());
        block.setContentDescription(title);
        into.addView(block);addListDivider(into);
    }
    private TextView eyebrow(String text) {
        TextView v=label(text,11,MUTED,true);
        v.setLetterSpacing(0.02f);
        return v;
    }
    private void addButton(LinearLayout parent,String text,boolean filled,Runnable action,int weight) {
        TextView button=label(text,14,filled?INK:TEXT,true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5),dp(5),dp(5),dp(5));button.setMinHeight(dp(48));
        button.setBackground(ripple(shape(filled?ACCENT:BG,8,filled?0:BORDER)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(text);
        button.setOnClickListener(v->action.run());
        boolean horizontal=parent.getOrientation()==LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams param=horizontal
                ?new LinearLayout.LayoutParams(0,-1,weight)
                :new LinearLayout.LayoutParams(-1,dp(controlHeight()));
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
        textView.setText(text);
        textView.setTextColor(color);
        // Large text applies to everyday labels and actions, not only totals.
        int actualSize=largeText && size<26 ? size+(size<=12?1:2) : size;
        textView.setTextSize(actualSize);
        textView.setFontFeatureSettings("kern, tnum");
        if(bold)textView.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        textView.setGravity(Gravity.CENTER_VERTICAL);
        return textView;
    }
    private RippleDrawable ripple(GradientDrawable background) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),
                background,null);
    }
    private GradientDrawable shape(int color,int radius,int outline) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(radius));
        if(outline!=0)shape.setStroke(dp(1),outline);
        return shape;
    }
    private LinearLayout card() {
        LinearLayout block=stack(5);
        block.setPadding(dp(12),dp(11),dp(12),dp(11));
        block.setBackground(shape(PANEL,8,0));
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
