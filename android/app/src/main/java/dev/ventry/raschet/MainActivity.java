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
import android.widget.CheckBox;
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
    private dev.ventry.raschet.data.LedgerRepository repository;
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
    private String historyQuery="";
    private String collectionQuery="";
    private boolean collectionUrgent;
    private int collectionLimit=60;
    private boolean returnToCollections;
    private String historyClientId=null;
    private String clientFilter=CollectionsInsights.ALL;
    private boolean clientsAmountFirst,clientsRecentFirst;
    private String shopName="Мой магазин";
    private boolean pendingExport, pendingExportCsv;
    private String pendingPdfClientId;
    private LocalDate reportFrom=LocalDate.now().withDayOfMonth(1),reportTo=LocalDate.now();
    private LocalDate historyFrom,historyTo,pendingReportFrom,pendingReportTo;
    private int reportDaysLimit=14;
    private boolean tablet=false, largeText=false, saving=false;
    private int historyVisibleLimit=100, detailVisibleLimit=80, bookDayLimit=30;
    private String detailFilter=ClientStatement.ALL, detailFilterClient;
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
        repository=new dev.ventry.raschet.data.SqliteLedgerRepository(ledger);
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
            clientsRecentFirst=state.getBoolean("clients_recent_first",false);
            historyVisibleLimit=state.getInt("history_visible_limit",100);
            collectionQuery=state.getString("collection_query","");collectionUrgent=state.getBoolean("collection_urgent",false);
            collectionLimit=state.getInt("collection_limit",60);returnToCollections=state.getBoolean("return_to_collections",false);
            historyQuery=state.getString("history_query","");historyClientId=state.getString("history_client_id");
            detailVisibleLimit=state.getInt("detail_visible_limit",80);
            detailFilter=state.getString("detail_filter",ClientStatement.ALL);detailFilterClient=state.getString("detail_filter_client");
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
        out.putBoolean("clients_recent_first",clientsRecentFirst);
        out.putInt("history_visible_limit",historyVisibleLimit);
        out.putString("collection_query",collectionQuery);out.putBoolean("collection_urgent",collectionUrgent);
        out.putInt("collection_limit",collectionLimit);out.putBoolean("return_to_collections",returnToCollections);
        out.putString("history_query",historyQuery);out.putString("history_client_id",historyClientId);
        out.putInt("detail_visible_limit",detailVisibleLimit);
        out.putString("detail_filter",detailFilter);out.putString("detail_filter_client",detailFilterClient);
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
            boolean active=destination.equals(tab)||(destination.equals("Главная")&&(tab.equals("Возврат")||tab.equals("Отчёт")));
            LinearLayout item=tablet?row():stack(2);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(tablet?10:2),dp(tablet?6:2),dp(tablet?6:2),dp(tablet?6:2));
            item.setBackground(ripple(shape(active?LedgerTheme.SELECTED:PANEL,8,0)));
            android.widget.ImageView icon=new android.widget.ImageView(this);
            icon.setImageResource(ICONS[i]);
            icon.setColorFilter(active?ACCENT:MUTED);
            icon.setContentDescription(null);
            item.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView name=label(caption,tablet?14:11,active?ACCENT:MUTED,active);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setIncludeFontPadding(false);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);
            if(tablet)np.leftMargin=dp(12);
            item.addView(name,np);
            item.setContentDescription(caption);
            item.setSelected(active);
            item.setFocusable(true);
            item.setOnClickListener(v->{tab=destination;selectedClient=null;returnToCollections=false;settingsExpanded="";render();});
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
                    tab.equals("Возврат")?collectionView():
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
        dev.ventry.raschet.data.LedgerSnapshot snapshot=repository.snapshot();
        List<LedgerStore.Client> customers=snapshot.getClients();
        List<LedgerStore.Entry> entries=snapshot.getEntries();
        List<CollectionsInsights.CustomerStatus> statuses=
                CollectionsInsights.analyze(customers,entries,LocalDate.now());
        long debt=0;
        int debtors=0,urgentCount=0;
        LocalDate today=LocalDate.now();
        Map<String,PaymentPromises.Progress> promises=PaymentPromises.project(statuses,directory,entries,today,ZoneId.systemDefault());
        for(CollectionsInsights.CustomerStatus status:statuses){
            debt+=status.balance;
            if(status.balance>0){
                debtors++;
                if(CollectionWorklist.priority(status,directory.followUp(status.client.id),directory.debtLimit(status.client.id),today,promises.get(status.client.id))<=CollectionWorklist.LIMIT)urgentCount++;
            }
        }
        LinearLayout hero=balancePanel("Общая задолженность",debt);
        boolean stacked=getResources().getConfiguration().screenWidthDp<365
                ||getResources().getConfiguration().fontScale>1.2f||largeText;
        LinearLayout quick=stacked?stack(8):row();
        addButton(quick,"+ Записать долг",true,this::showQuickDebt,1);
        addButton(quick,"Принять оплату",false,()->chooseClient("payment"),1);
        view.addView(hero,margin(-1,-2,6));
        view.addView(quick,margin(-1,dp(stacked?2*controlHeight()+8:controlHeight()),8));
        QuickDebtDrafts.Draft pending=new QuickDebtDrafts(this).get();
        if(pending!=null&&!ledger.hasEntryId(pending.id)&&!ledger.hasClientId(pending.id))
            actionRow(view,"↶","Незавершённая запись",pending.name.isEmpty()?"Продолжить черновик":pending.name,this::showQuickDebt);
        addListDivider(view);
        metricRow(view,"Возврат долгов",urgentCount>0?"Требуют внимания: "+urgentCount:"Должников: "+debtors,urgentCount>0?ALERT:MUTED,()->openCollections());
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
        // Collection management is on a dedicated screen, not below the journal.
        actionRow(view,"↗","Финансовый отчёт","Долги и оплаты за выбранные даты",()->{tab="Отчёт";selectedClient=null;render();});
        if(!entries.isEmpty()&&getPreferences(MODE_PRIVATE).getLong("last_json_export",0L)==0L){
            actionRow(view,"↓","Создать JSON-копию","Сохраните книгу перед сменой устройства",()->exportBook(false));
        }
        return scroll(view);
    }

    private void openCollections(){
        collectionQuery="";collectionUrgent=false;collectionLimit=60;returnToCollections=false;selectedClient=null;tab="Возврат";render();
    }
    private View collectionView(){
        LinearLayout view=screen("Возврат долгов","План работы с непогашенными долгами");
        dev.ventry.raschet.data.LedgerSnapshot snapshot=repository.snapshot();
        List<LedgerStore.Entry> entries=snapshot.getEntries();
        LocalDate today=LocalDate.now();
        List<CollectionsInsights.CustomerStatus> statuses=CollectionsInsights.analyze(snapshot.getClients(),entries,today);
        Map<String,PaymentPromises.Progress> promises=PaymentPromises.project(statuses,directory,entries,today,ZoneId.systemDefault());
        List<CollectionWorklist.Item> work=CollectionWorklist.build(statuses,directory,today,promises);
        long overdue=0,upcoming=0,balance=0;
        for(CollectionWorklist.Item item:work){overdue+=item.status.overdue;upcoming+=item.status.upcoming;balance+=item.status.balance;}
        LinearLayout summary=balancePanel("К возврату всего",balance);
        summary.addView(label("Должников: "+work.size(),13,MUTED,false));
        view.addView(summary,margin(-1,-2,6));
        LinearLayout due=stack(0);
        metricRow(due,"Просрочено",money(overdue),overdue>0?RED:MUTED,()->openClientFilter(CollectionsInsights.LATE));
        metricRow(due,"В ближайшие 7 дней",money(upcoming),upcoming>0?ALERT:MUTED,()->openClientFilter(CollectionsInsights.SOON));
        view.addView(due);
        EditText searchBox=field("Имя или телефон",collectionQuery);
        view.addView(searchBox,margin(-1,dp(controlHeight()),6));
        LinearLayout filter=row();
        addButton(filter,"Все должники",!collectionUrgent,()->{collectionUrgent=false;collectionLimit=60;render();},1);
        addButton(filter,"Требуют внимания",collectionUrgent,()->{collectionUrgent=true;collectionLimit=60;render();},1);
        view.addView(filter,margin(-1,dp(controlHeight()),6));
        LinearLayout results=stack(0);view.addView(results);
        Runnable refresh=()->fillCollections(results,work);
        searchBox.addTextChangedListener(watcher(v->{collectionQuery=v;collectionLimit=60;refresh.run();}));
        refresh.run();
        return scroll(view);
    }
    private void fillCollections(LinearLayout into,List<CollectionWorklist.Item> work){
        into.removeAllViews();
        List<CollectionWorklist.Item> visible=CollectionWorklist.select(work,collectionQuery,collectionUrgent);
        int shown=0,last=-1;
        for(CollectionWorklist.Item item:visible){
            if(shown++>=collectionLimit)break;
            if(last!=item.priority){section(into,CollectionWorklist.title(item.priority),"");last=item.priority;}
            CollectionsInsights.CustomerStatus status=item.status;
            LedgerStore.Client person=status.client;
            LinearLayout line=stack(3);line.setPadding(dp(4),dp(6),dp(4),dp(6));
            LinearLayout nameRow=row();nameRow.setGravity(Gravity.CENTER_VERTICAL);
            nameRow.addView(label(person.name,15,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
            nameRow.addView(label(money(status.balance),16,TEXT,true));
            nameRow.setBackground(ripple(shape(BG,6,0)));nameRow.setMinimumHeight(dp(48));nameRow.setFocusable(true);
            nameRow.setOnClickListener(v->{returnToCollections=true;tab="Клиенты";selectedClient=person.id;render();});
            line.addView(nameRow);
            if(status.overdue>0)line.addView(label("Просрочено: "+money(status.overdue),12,RED,false));
            if(status.upcoming>0)line.addView(label("За 7 дней: "+money(status.upcoming)+" · ближайший срок "+status.nextDue,12,ALERT,false));
            if(item.limit>0&&status.balance>item.limit)line.addView(label("Сверх лимита: "+money(status.balance-item.limit),12,ALERT,false));
            if(item.followUp!=null){
                if(item.promise!=null)appendPromise(line,item.followUp,item.promise);
                else line.addView(label("Связаться: "+bookDateFormat.format(item.followUp.date)+(item.followUp.note.isEmpty()?"":" · "+item.followUp.note),12,item.followUp.date.isAfter(LocalDate.now())?MUTED:ALERT,false));
            }
            LinearLayout actions=row();
            addButton(actions,"Принять оплату",true,()->showEntryForm(person.id,"payment"),1);
            addButton(actions,item.followUp==null?"Запланировать":"Изменить план",false,()->showFollowUp(person),1);
            line.addView(actions,margin(-1,dp(controlHeight()),0));
            into.addView(line);addListDivider(into);
        }
        if(visible.size()>collectionLimit)addButton(into,"Показать ещё должников",false,()->{collectionLimit+=60;fillCollections(into,work);},1);
        if(visible.isEmpty())into.addView(label(work.isEmpty()?"Непогашенных долгов нет.":"Нет должников по выбранным условиям.",14,MUTED,false));
    }
    private void appendPromise(LinearLayout into,ClientDirectory.FollowUp plan,PaymentPromises.Progress progress){
        into.addView(label("Обещано "+money(plan.amount)+" до "+bookDateFormat.format(plan.date),13,TEXT,true));
        if(!progress.valid){
            into.addView(label("Нужно обновить обещание: исходная операция не найдена.",12,ALERT,false));
        }else{
            String caption=progress.fulfilled()?(progress.onTime>=plan.amount?"Выполнено":"Выполнено после срока"):
                    progress.missed?"Срок обещания прошёл":"Ожидается оплата";
            into.addView(label(caption+" · получено "+money(Math.min(progress.paid,plan.amount))+" из "+money(plan.amount),12,progress.fulfilled()?ACCENT:progress.missed?ALERT:MUTED,false));
            if(!progress.fulfilled())into.addView(label("Осталось по обещанию: "+money(plan.amount-progress.paid),12,MUTED,false));
        }
        if(!plan.note.isEmpty())into.addView(label(plan.note,12,MUTED,false));
    }
    private void showFollowUp(LedgerStore.Client person){
        ClientDirectory.FollowUp old=directory.followUp(person.id);
        LocalDate[] day={old==null?LocalDate.now():old.date};
        LinearLayout form=stack(6);
        form.addView(label(person.name,16,TEXT,true));
        TextView date=label(bookDateFormat.format(day[0]),15,TEXT,true);
        date.setBackground(ripple(shape(ELEVATED,6,BORDER)));date.setPadding(dp(12),0,dp(12),0);date.setFocusable(true);
        date.setOnClickListener(v->pickReportDate("Дата следующего контакта",day[0],chosen->{day[0]=chosen;date.setText(bookDateFormat.format(chosen));}));
        form.addView(formGroup("Дата контакта / оплаты",date));
        LinearLayout presets=row();
        addButton(presets,"Сегодня",false,()->{day[0]=LocalDate.now();date.setText(bookDateFormat.format(day[0]));},1);
        addButton(presets,"Завтра",false,()->{day[0]=LocalDate.now().plusDays(1);date.setText(bookDateFormat.format(day[0]));},1);
        form.addView(presets,margin(-1,dp(controlHeight()),0));
        EditText promised=field("Без обещанной суммы",old!=null&&old.amount>0?amountInput(old.amount):null);
        promised.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(formGroup("Обещанная сумма, ₽ (необязательно)",promised));
        CheckBox restartPromise=new CheckBox(this);restartPromise.setText("Начать новое обещание (сбросить отсчёт)");restartPromise.setTextColor(TEXT);
        if(old!=null&&old.amount>0)form.addView(restartPromise);
        EditText note=field("О чём договорились",old==null?null:old.note);
        form.addView(formGroup("Комментарий (необязательно)",note));
        form.addView(label("Без суммы это обычный план контакта. Оплаты после создания обещания учитываются автоматически. Изменение суммы начинает новый отсчёт. План не меняет долг или срок возврата и хранится локально.",12,MUTED,false));
        if(old!=null)addButton(form,"Удалить план",false,()->{
            try{directory.setFollowUp(person.id,null,"");if(chooser!=null)chooser.dismiss();render();}catch(Exception e){message(e.getMessage());}
        },1);
        showDialog("План возврата",form,()->{
            List<LedgerStore.Entry> entries=ledger.entries();
            long balance=LedgerStore.balance(entries,person.id);
            String raw=promised.getText().toString().trim();
            long target=raw.isEmpty()?0L:LedgerStore.parseAmount(raw);
            String comment=note.getText().toString().trim();
            if(target==0){
                if(balance<=0)throw new IllegalArgumentException("Долг уже погашен.");
                directory.setFollowUp(person.id,day[0],comment);return true;
            }
            boolean keep=!restartPromise.isChecked()&&old!=null&&old.amount==target&&!old.anchor.isEmpty()&&PaymentPromises.calculate(entries,person.id,old,LocalDate.now(),ZoneId.systemDefault()).valid;
            if(!keep&&(target>balance||day[0].isBefore(LocalDate.now())))
                throw new IllegalArgumentException("Новое обещание: сумма не выше остатка, дата не раньше сегодняшней.");
            String anchor=keep?old.anchor:PaymentPromises.lastEntryId(entries,person.id);
            if(anchor.isEmpty())throw new IllegalArgumentException("Нет исходной операции.");
            directory.setFollowUp(person.id,day[0],comment,target,anchor);return true;
        });
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
            sum.setContentDescription("Запись · "+person.name+" · "+money(e.amount));
            sum.setFocusable(true);sum.setOnClickListener(v->showOperation(e.id));
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
        else addButton(body,"+ Оплата за этот день",false,()->chooseClient("payment",day.date),1);
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
        clientFilter=filter;clientScope=ClientDirectory.ACTIVE;clientVisibleLimit=60;search="";returnToCollections=false;selectedClient=null;tab="Клиенты";render();
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
                (clientsRecentFirst?" · недавние":clientsAmountFirst?" · по остатку":" · по имени"),MUTED,this::showClientFilters);
        LinearLayout results=stack(0);view.addView(results);
        // Build due/balance projections once per render, not on every typed character.
        dev.ventry.raschet.data.LedgerSnapshot snapshot=repository.snapshot();
        List<CollectionsInsights.CustomerStatus> cached=
                CollectionsInsights.analyze(snapshot.getClients(),snapshot.getEntries(),LocalDate.now());
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
        for(String choice:new String[]{CollectionsInsights.ALL,CollectionsInsights.OWING,CollectionsInsights.CLEAR,CollectionsInsights.LATE,CollectionsInsights.SOON})
            filterMenuAction(menu,choice,choice.equals(clientFilter),()->{clientFilter=choice;clientVisibleLimit=60;render();});
        section(menu,"Сортировка","");
        filterMenuAction(menu,"Сортировка: по имени",!clientsAmountFirst&&!clientsRecentFirst,()->{clientsAmountFirst=false;clientsRecentFirst=false;clientVisibleLimit=60;render();});
        filterMenuAction(menu,"Сортировка: по остатку ↓",clientsAmountFirst&&!clientsRecentFirst,()->{clientsAmountFirst=true;clientsRecentFirst=false;clientVisibleLimit=60;render();});
        filterMenuAction(menu,"Сортировка: последние операции",clientsRecentFirst,()->{clientsAmountFirst=false;clientsRecentFirst=true;clientVisibleLimit=60;render();});
        showDialog("Фильтры клиентов",menu,null);
    }
    private void fillClients(LinearLayout rows,List<CollectionsInsights.CustomerStatus> cached) {
        rows.removeAllViews();
        List<CollectionsInsights.CustomerStatus> scoped=directory.select(cached,clientScope);
        List<CollectionsInsights.CustomerStatus> visible=CollectionsInsights.visible(
                scoped,clientFilter,clientsAmountFirst,clientsRecentFirst,search);
        if(!visible.isEmpty()){
            long total=0L;
            for(CollectionsInsights.CustomerStatus item:visible)total+=item.balance;
            LinearLayout summary=row();summary.setGravity(Gravity.CENTER_VERTICAL);
            summary.setPadding(dp(4),dp(4),dp(4),dp(4));
            summary.addView(label("Клиентов: "+visible.size(),12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
            TextView sum=label("Долг: "+money(total),13,TEXT,true);sum.setGravity(Gravity.END);
            summary.addView(sum,new LinearLayout.LayoutParams(-2,-2));rows.addView(summary);addListDivider(rows);
        }
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
            long limit=directory.debtLimit(customer.id);
            if(limit>0&&amount>=limit)labels.addView(label(amount>limit?"Лимит превышен":"Лимит исчерпан",12,ALERT,false));
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
                    +(status.overdue>0?", просрочено "+money(status.overdue):"")
                    +(limit>0&&amount>=limit?(amount>limit?", лимит превышен":", лимит исчерпан"):""));
            rows.addView(card);
            addListDivider(rows);
        }
        if(visible.size()>clientVisibleLimit)addButton(rows,"Показать ещё клиентов",false,()->{
            clientVisibleLimit+=60;fillClients(rows,cached);
        },1);
        if(visible.isEmpty()){
            if(scoped.isEmpty()&&!ClientDirectory.ACTIVE.equals(clientScope)&&search.trim().isEmpty())
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
            else if(clientFilter.equals(CollectionsInsights.CLEAR))rows.addView(emptyState(
                    R.drawable.ic_nav_people,"Нет клиентов без долга",
                    "Здесь будут клиенты с нулевым остатком.","Показать всех",()->{clientFilter=CollectionsInsights.ALL;clientVisibleLimit=60;render();}));
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
        if(!id.equals(detailFilterClient)){detailFilterClient=id;detailFilter=ClientStatement.ALL;detailVisibleLimit=80;}
        List<LedgerStore.Entry> all=ledger.entries();
        ClientStatement statement=ClientStatement.build(all,id,ZoneId.systemDefault());
        long amount=LedgerStore.balance(all,id);
        LinearLayout view=screen(current.name,current.phone.isEmpty()?"Карточка клиента":current.phone);
        TextView back=label(returnToCollections?"←  Возврат долгов":"←  Все клиенты",14,ACCENT,true);
        back.setMinHeight(dp(48));back.setPadding(0,0,0,dp(2));back.setOnClickListener(v->{selectedClient=null;tab=returnToCollections?"Возврат":"Клиенты";returnToCollections=false;render();});
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
        long limit=directory.debtLimit(id);
        if(limit>0)total.addView(label(limitSummary(limit,amount),13,amount>=limit?ALERT:MUTED,false));
        ClientDirectory.FollowUp follow=directory.followUp(id);
        if(follow!=null&&follow.amount>0)appendPromise(total,follow,PaymentPromises.calculate(all,id,follow,LocalDate.now(),ZoneId.systemDefault()));
        else if(amount>0&&follow!=null)total.addView(label("Связаться: "+bookDateFormat.format(follow.date)+(follow.note.isEmpty()?"":" · "+follow.note),13,follow.date.isAfter(LocalDate.now())?MUTED:ALERT,false));
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
        if(!archived){
            LedgerDrafts drafts=new LedgerDrafts(this);
            for(String type:new String[]{"debt","payment"}){
                LedgerDrafts.Draft draft=drafts.get(id,type);
                if(draft==null||statement.ids.contains(draft.id)||("payment".equals(type)&&amount<=0))continue;
                metricRow(view,"Черновик "+("debt".equals(type)?"долга":"оплаты"),draft.amount.trim().isEmpty()?"Продолжить":draft.amount+" ₽",ACCENT,()->showEntryForm(id,type));
            }
        }
        List<LedgerStore.Entry> visible=statement.select(detailFilter);
        metricRow(view,"История клиента",detailFilter+" · "+visible.size(),detailFilter.equals(ClientStatement.ALL)?MUTED:ACCENT,this::showDetailFilters);
        int shown=0;
        LocalDate lastDay=null;
        for(LedgerStore.Entry e:visible){
            if(shown>=detailVisibleLimit)break;
            LocalDate date=PeriodReport.dateOf(e,ZoneId.systemDefault());
            if(!date.equals(lastDay)){
                TextView day=label(bookDateFormat.format(date),12,MUTED,true);
                day.setPadding(dp(6),dp(5),dp(6),dp(5));day.setBackground(shape(PANEL,4,0));
                view.addView(day,margin(-1,-2,2));lastDay=date;
            }
            view.addView(entryCard(e,null,statement.canceled.contains(e.id),false,statement.remaining.get(e.id)));addListDivider(view);shown++;
        }
        if(shown==0)view.addView(label(statement.entries.isEmpty()?"Пока нет операций.":"Нет операций по выбранному фильтру.",14,MUTED,false));
        if(visible.size()>detailVisibleLimit)addButton(view,"Показать ещё операции",false,()->{
            detailVisibleLimit=Math.min(detailVisibleLimit+80,100000);render();
        },1);
        return scroll(view);
    }
    private void showDetailFilters(){
        LinearLayout menu=stack(2);
        for(String choice:ClientStatement.FILTERS)
            filterMenuAction(menu,choice,choice.equals(detailFilter),()->{detailFilter=choice;detailVisibleLimit=80;render();});
        showDialog("История клиента",menu,null);
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
            clientMenuAction(menu,"Лимит задолженности",()->showDebtLimit(client));
            if(amount>0||directory.followUp(id)!=null)clientMenuAction(menu,"План возврата",()->showFollowUp(client));
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
    private String limitSummary(long limit,long balance){
        return "Лимит: "+money(limit)+" · "+(balance>limit?"Превышение "+money(balance-limit):"Доступно "+money(DebtLimits.remaining(limit,balance)));
    }
    private void showDebtLimit(LedgerStore.Client client){
        long limit=directory.debtLimit(client.id);
        LinearLayout form=stack(6);
        form.addView(label(client.name+" · остаток "+money(LedgerStore.balance(ledger.entries(),client.id)),14,TEXT,true));
        EditText amount=field("Без лимита",limit>0?amountInput(limit):null);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(formGroup("Лимит задолженности, ₽",amount));
        addButton(form,"Без лимита",false,()->amount.setText(""),1);
        form.addView(label("Пустое поле отключает лимит. Превышение при новом долге требует подтверждения. Настройка действует на этом устройстве и не переносится с JSON-копией.",13,MUTED,false));
        showDialog("Лимит задолженности",form,()->{
            String raw=amount.getText().toString().trim();
            directory.setDebtLimit(client.id,raw.isEmpty()?0L:LedgerStore.parseAmount(raw));
            return true;
        });
    }
    /** Approval binds to the exact customer, balance, configured limit and new amount. */
    private final class LimitNotice {
        final LinearLayout block=stack(2);
        final TextView text=label("",13,MUTED,false);
        final CheckBox consent=new CheckBox(MainActivity.this);
        String approvedFor="";
        LimitNotice(LinearLayout parent){
            text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            consent.setText("Подтверждаю превышение лимита");consent.setTextColor(TEXT);
            consent.setMinHeight(dp(48));
            block.addView(text);block.addView(consent);block.setVisibility(View.GONE);parent.addView(block);
        }
        void update(String clientId,long balance,long addition){
            long limit=clientId==null?0:directory.debtLimit(clientId);
            String key=clientId+":"+balance+":"+limit+":"+addition;
            if(!key.equals(approvedFor)){consent.setChecked(false);approvedFor=key;}
            block.setVisibility(limit>0?View.VISIBLE:View.GONE);
            if(limit==0)return;
            boolean over=DebtLimits.requiresConfirmation(limit,balance,addition);
            text.setText(limitSummary(limit,balance)+(over?" · После записи превышение "+money(balance+addition-limit):""));
            text.setTextColor(over?RED:balance>=limit?ALERT:MUTED);
            consent.setVisibility(over?View.VISIBLE:View.GONE);
        }
        void verify(String clientId,long addition){
            if(clientId==null)return;
            long balance=LedgerStore.balance(ledger.entries(),clientId);
            update(clientId,balance,addition);
            DebtLimits.requireConsent(directory.debtLimit(clientId),balance,addition,consent.isChecked());
        }
    }
    private long previewAmount(EditText amount){
        try{return LedgerStore.parseAmount(amount.getText().toString());}
        catch(IllegalArgumentException e){return 0L;}
    }
    static LocalDate correctionDueDate(String raw,LocalDate fallback){
        if(raw==null||raw.trim().isEmpty())return fallback;
        try{return LocalDate.parse(raw.trim());}
        catch(java.time.format.DateTimeParseException invalid){return fallback;}
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
            historyFrom=reportFrom;historyTo=reportTo;historyFilter="Все";historyQuery="";historyClientId=null;historyVisibleLimit=100;
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
        Map<String,LedgerStore.Client> people=new HashMap<>();
        for(LedgerStore.Client c:ledger.clients())people.put(c.id,c);
        if(historyClientId!=null){
            LedgerStore.Client chosen=people.get(historyClientId);
            addButton(view,"Клиент: "+(chosen==null?"—":chosen.name)+" · сбросить",false,()->{
                historyClientId=null;historyVisibleLimit=100;render();
            },1);
        }
        EditText lookup=field("Имя, телефон или комментарий",historyQuery);
        lookup.setSingleLine(true);
        LinearLayout results=stack(0);
        view.addView(formGroup("Поиск операций",lookup));
        List<LedgerStore.Entry> all=ledger.entries();
        Runnable refresh=()->fillHistoryResults(results,all,people);
        lookup.addTextChangedListener(watcher(value->{historyQuery=value;historyVisibleLimit=100;refresh.run();}));
        view.addView(results);refresh.run();
        return scroll(view);
    }
    private void fillHistoryResults(LinearLayout view,List<LedgerStore.Entry> all,Map<String,LedgerStore.Client> people){
        view.removeAllViews();
        Set<String> canceled=LedgerStore.canceled(all);
        List<LedgerStore.Entry> visible=HistoryRows.select(all,people,historyClientId,historyQuery,historyFrom,historyTo,historyFilter,ZoneId.systemDefault());
        int shown=0;
        boolean moreHistory=false;
        LocalDate lastHistoryDay=null;
        for(LedgerStore.Entry e:visible){
            if(shown>=historyVisibleLimit){moreHistory=true;break;}
            LocalDate date=PeriodReport.dateOf(e,ZoneId.systemDefault());
            if(!date.equals(lastHistoryDay)){
                TextView dateHeading=label(dayFormat.format(date),12,MUTED,true);
                dateHeading.setPadding(dp(6),dp(7),dp(6),dp(7));
                dateHeading.setBackgroundColor(PANEL);
                view.addView(dateHeading,margin(-1,-2,2));
                lastHistoryDay=date;
            }
            LedgerStore.Client person=people.get(e.clientId);
            view.addView(entryCard(e,person==null?null:person.name,canceled.contains(e.id),false));
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
                    "Показать все", ()->{historyFilter="Все";historyFrom=null;historyTo=null;historyQuery="";historyClientId=null;historyVisibleLimit=100;render();}));
        }
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
        return entryCard(e,clientName,canceled,includeDate,null);
    }
    private LinearLayout entryCard(LedgerStore.Entry e,String clientName,boolean canceled,boolean includeDate,Long remaining) {
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
        if(clientName==null&&includeDate)left.addView(label(formatDate(e.date),12,MUTED,false));
        if(remaining!=null&&!canceled)left.addView(label(remaining==0?"Погашено":"Осталось: "+money(remaining),12,remaining==0?MUTED:ALERT,false));
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
        card.setBackground(ripple(shape(BG,8,0)));card.setFocusable(true);
        card.setOnClickListener(v->showOperation(e.id));
        sum.setContentDescription("Запись · "+money(e.amount));
        sum.setFocusable(true);sum.setOnClickListener(v->showOperation(e.id));
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
            directory.clearAfterRestore();new QuickDebtDrafts(this).clear();new LedgerDrafts(this).clearAll();
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
        QuickDebtDrafts drafts=new QuickDebtDrafts(this);
        QuickDebtDrafts.Draft previous=drafts.get();
        if(previous!=null&&(ledger.hasEntryId(previous.id)||ledger.hasClientId(previous.id))){
            drafts.clear();previous=null;
        }
        if(previous==null){showQuickDebtForm(fromClients,initialDay,null);return;}
        final QuickDebtDrafts.Draft pending=previous;
        LinearLayout content=stack(6);
        content.addView(label(pending.name.isEmpty()?"Без имени":pending.name,16,TEXT,true));
        content.addView(label("Сумма: "+(pending.amount.isEmpty()?"не указана":pending.amount+" ₽")+" · "+pending.day,13,MUTED,false));
        addButton(content,"Продолжить черновик",true,()->{
            if(chooser!=null)chooser.dismiss();
            showQuickDebtForm(pending.fromClients,pending.day,pending);
        },1);
        addButton(content,"Удалить черновик и начать новый",false,()->{
            if(chooser!=null)chooser.dismiss();
            drafts.clear();showQuickDebtForm(fromClients,initialDay,null);
        },1);
        showDialog("Незавершённая запись",content,null);
    }
    private void showQuickDebtForm(boolean fromClients,LocalDate initialDay,QuickDebtDrafts.Draft previous){
        QuickDebtDrafts drafts=new QuickDebtDrafts(this);
        final String draftId=previous==null?java.util.UUID.randomUUID().toString():previous.id;
        LinearLayout form=stack(5);
        if(previous!=null)form.addView(label("Черновик восстановлен. Проверьте клиента перед сохранением.",12,MUTED,false));
        EditText name=field("Имя покупателя",previous==null?null:previous.name);
        EditText phone=field("Телефон (необязательно)",previous==null?null:previous.phone);phone.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText amount=field("0,00",previous==null?null:previous.amount);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(formGroup("Имя покупателя",name));
        form.addView(formGroup("Телефон (необязательно)",phone));
        LinearLayout matches=stack(2);form.addView(matches);
        form.addView(formGroup(fromClients?"Сумма долга, ₽ (необязательно)":"Сумма долга, ₽",amount));
        LimitNotice notice=new LimitNotice(form);
        if(fromClients)form.addView(label("Без суммы сохраняется только клиент.",12,MUTED,false));
        LocalDate[] day={initialDay};
        TextView date=debtDateField(day);
        form.addView(formGroup("Дата записи",date));
        LinearLayout extra=stack(5);
        EditText due=field("Без срока",previous==null?null:previous.due);due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate today=LocalDate.now();
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(LocalDate.of(y,m+1,d).toString()),
                    today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
        });
        extra.addView(formGroup("Вернуть до (необязательно)",due));
        LinearLayout clear=stack(0);addButton(clear,"Без срока",false,()->due.setText(""),1);
        clear.setVisibility(due.length()==0?View.GONE:View.VISIBLE);due.addTextChangedListener(watcher(v->clear.setVisibility(v.isEmpty()?View.GONE:View.VISIBLE)));
        extra.addView(clear);
        EditText note=field("Комментарий",previous==null?null:previous.note);extra.addView(formGroup("Комментарий (необязательно)",note));
        LinearLayout toggle=stack(0);
        addButton(toggle,"Срок и комментарий",false,()->{
            boolean open=extra.getVisibility()!=View.VISIBLE;
            extra.setVisibility(open?View.VISIBLE:View.GONE);
            TextView button=(TextView)toggle.getChildAt(0);
            String caption=open?"Скрыть дополнительные поля":"Срок и комментарий";
            button.setText(caption);button.setContentDescription(caption);
        },1);
        form.addView(toggle);extra.setVisibility(due.length()>0||note.length()>0?View.VISIBLE:View.GONE);form.addView(extra);
        if(extra.getVisibility()==View.VISIBLE){TextView button=(TextView)toggle.getChildAt(0);button.setText("Скрыть дополнительные поля");button.setContentDescription("Скрыть дополнительные поля");}
        List<LedgerStore.Client> customers=ledger.clients();
        List<LedgerStore.Entry> snapshot=ledger.entries();
        Map<String,Long> lastAmounts=RecentDebts.lastAmounts(snapshot);
        List<CollectionsInsights.CustomerStatus> statuses=CollectionsInsights.analyze(customers,snapshot,LocalDate.now());
        Map<String,Long> balances=new HashMap<>();
        for(CollectionsInsights.CustomerStatus status:statuses)balances.put(status.client.id,status.balance);
        String[] selected={null};
        boolean[] updating={false},separate={false};
        // Intentionally do not persist selected IDs or override consent: reselect the client after resume.
        Runnable persist=()->{
            if(updating[0])return;
            QuickDebtDrafts.Draft value=new QuickDebtDrafts.Draft(draftId,fromClients,
                    name.getText().toString(),phone.getText().toString(),amount.getText().toString(),
                    day[0],due.getText().toString(),note.getText().toString());
            if(value.isEmpty())drafts.clear();else drafts.save(value);
        };
        Runnable refreshLimit=()->notice.update(selected[0],selected[0]==null?0L:balances.getOrDefault(selected[0],0L),previewAmount(amount));
        amount.addTextChangedListener(watcher(v->refreshLimit.run()));
        java.util.function.Consumer<LedgerStore.Client> choose=customer->{
            updating[0]=true;selected[0]=customer.id;
            name.setText(customer.name);phone.setText(customer.phone);updating[0]=false;
            matches.removeAllViews();
            matches.addView(label("Выбран существующий клиент · "+money(balances.getOrDefault(customer.id,0L)),12,ACCENT,true));
            addRecentAmount(matches,amount,lastAmounts.getOrDefault(customer.id,0L));
            refreshLimit.run();persist.run();
        };
        Runnable refresh=()->{
            refreshLimit.run();matches.removeAllViews();
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
                if(!(!query.isEmpty()&&n.contains(query))&&!(digits.length()>=3&&ClientSearch.phoneMatches(customer.phone,digits)))continue;
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
        for(TextView field:new TextView[]{name,phone,amount,date,due,note})field.addTextChangedListener(watcher(v->persist.run()));
        refresh.run();
        if(previous!=null)addButton(form,"Удалить черновик",false,()->{drafts.clear();if(chooser!=null)chooser.dismiss();render();},1);
        showDialog(fromClients?"Новый клиент и долг":"Новый долг",form,()->{
            String n=name.getText().toString(),ph=phone.getText().toString();
            if(selected[0]==null&&!separate[0]&&hasExactCustomer(ledger.clients(),n,ph))
                throw new IllegalArgumentException("Выберите существующего клиента или подтвердите создание отдельного.");
            if(selected[0]!=null&&directory.archived(selected[0],LedgerStore.balance(ledger.entries(),selected[0])))
                throw new IllegalArgumentException("Сначала верните клиента из архива.");
            if(fromClients&&amount.getText().toString().trim().isEmpty()){
                if(selected[0]!=null)throw new IllegalArgumentException("Клиент уже существует. Введите сумму долга.");
                if(due.length()>0||!note.getText().toString().trim().isEmpty())throw new IllegalArgumentException("Введите сумму долга или очистите срок и комментарий.");
                ledger.addDraftClient(draftId,n,ph);
                drafts.clear();
                tab="Клиенты";selectedClient=null;clientScope=ClientDirectory.ACTIVE;
                clientFilter=CollectionsInsights.ALL;search="";clientVisibleLimit=60;
                return true;
            }
            // Determine how many dates to reveal before the transaction; no potentially
            // failing database reads after a successful save.
            int rank=1;
            for(BookDays.Day existing:BookDays.group(ledger.entries(),ZoneId.systemDefault()))
                if(existing.date.isAfter(day[0]))rank++;
            long cents=LedgerStore.parseAmount(amount.getText().toString());
            notice.verify(selected[0],cents);
            ledger.recordDatedDebt(selected[0],n,ph,cents,day[0],due.getText().toString(),note.getText().toString().trim(),draftId);
            drafts.clear();
            bookPayments=false;clearBookFilters();
            expandedBookDays.add(day[0].toString());
            bookDayLimit=Math.max(bookDayLimit,rank);
            if(fromClients){
                tab="Клиенты";clientScope=ClientDirectory.ACTIVE;clientFilter=CollectionsInsights.ALL;
                search="";clientVisibleLimit=60;
            }else tab="Главная";
            selectedClient=null;return true;
        });
        if(chooser!=null)chooser.setOnDismissListener(v->{if(!saving&&unlocked)render();});
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
            if(ClientSearch.sameNumber(c.phone,d))return true;
        }
        return false;
    }
    private void chooseClient(String type) {chooseClient(type,LocalDate.now());}
    private void chooseClient(String type,LocalDate initialDay) {
        if(type.equals("debt")){showQuickDebt();return;}
        List<LedgerStore.Client> customers=ledger.clients();
        if(customers.isEmpty()){message("Пока нет клиентов с долгом.");return;}
        LinearLayout content=stack(8);
        List<CollectionsInsights.CustomerStatus> statuses=directory.select(
                CollectionsInsights.analyze(customers,ledger.entries(),LocalDate.now()),ClientDirectory.ACTIVE);
        EditText lookup=field("Имя или номер телефона",null);
        lookup.setSingleLine(true);
        content.addView(lookup);
        LinearLayout results=stack(0);content.addView(results);
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
                    LinearLayout button=row();button.setGravity(Gravity.CENTER_VERTICAL);
                    button.setPadding(dp(4),dp(6),dp(4),dp(6));button.setMinimumHeight(dp(controlHeight()));
                    button.setBackground(ripple(shape(PANEL,6,0)));
                    LinearLayout left=stack(2);left.addView(label(customer.name,15,TEXT,true));
                    if(!customer.phone.isEmpty())left.addView(label(customer.phone,12,MUTED,false));
                    if(status.overdue>0)left.addView(label("Просрочено: "+money(status.overdue),12,RED,false));
                    button.addView(left,new LinearLayout.LayoutParams(0,-2,1));
                    TextView value=label(money(status.balance),16,TEXT,true);value.setGravity(Gravity.END);
                    button.addView(value,new LinearLayout.LayoutParams(-2,-2));
                    button.setContentDescription("Выбрать · "+customer.name);
                    button.setFocusable(true);
                    results.addView(button);addListDivider(results);
                    button.setOnClickListener(v->{if(chooser!=null)chooser.dismiss();showEntryForm(customer.id,type,0L,initialDay);});
                }
                if(found.isEmpty())results.addView(label(lookup.getText().toString().trim().isEmpty()?"Нет клиентов с непогашенным долгом.":"Никого не нашли. Проверьте имя или номер телефона.",14,MUTED,false));
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
                boolean samePhone=ClientSearch.sameNumber(other.phone,digits);
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
        form.addView(summary);
        LimitNotice notice=new LimitNotice(form);
        final long available=LedgerStore.balance(ledger.entries(),clientId);
        addListDivider(form);form.addView(rows);
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
                notice.update(clientId,available,purchase.total);
            }catch(Exception ex){total.setText("Итого к записи: —");details.setText(ex.getMessage());notice.update(clientId,available,0L);}
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
            notice.verify(clientId,purchase.total);
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
        showEntryForm(clientId,type,suggestedCents,LocalDate.now());
    }
    private void showEntryForm(String clientId,String type,long suggestedCents,LocalDate initialDay) {
        LedgerStore.Client c=null;
        for(LedgerStore.Client person:ledger.clients())if(person.id.equals(clientId))c=person;
        if(c==null){message("Клиент не найден.");return;}
        final long available=LedgerStore.balance(ledger.entries(),clientId);
        final boolean fromBook=tab.equals("Главная");
        final boolean fromCollections=tab.equals("Возврат");
        if(directory.archived(clientId,available)){message("Сначала верните клиента из архива.");return;}
        if(type.equals("payment")&&available<=0){message("Нет долга для оплаты.");return;}
        LinearLayout content=stack(7);
        content.addView(label(c.name+" · остаток "+money(available),13,MUTED,false));
        LedgerDrafts drafts=new LedgerDrafts(this);
        LedgerDrafts.Draft previous=drafts.get(clientId,type);
        if(previous!=null&&ledger.hasEntryId(previous.id)){drafts.clear(clientId,type);previous=null;}
        boolean savedDraft=previous!=null;
        final String[] operationId={savedDraft?previous.id:java.util.UUID.randomUUID().toString()};
        String preset=savedDraft?previous.amount:suggestedCents>0&&type.equals("payment")?amountInput(suggestedCents):null;
        if(savedDraft)
            content.addView(label("Незавершённый черновик восстановлен. Проверьте сумму перед записью.",12,ACCENT,false));
        EditText amount=field("0,00",preset);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        content.addView(formGroup("Сумма, ₽",amount));
        final LimitNotice notice=type.equals("debt")?new LimitNotice(content):null;
        if(notice!=null){
            addRecentAmount(content,amount,RecentDebts.lastAmounts(ledger.entries()).getOrDefault(clientId,0L));
            Runnable check=()->notice.update(clientId,available,previewAmount(amount));
            amount.addTextChangedListener(watcher(v->check.run()));check.run();
        }
        if(type.equals("payment")){
            LinearLayout amounts=row();
            addButton(amounts,"Половина",false,()->amount.setText(amountInput(Math.max(1L,available/2))),1);
            addButton(amounts,"Весь остаток",false,()->amount.setText(amountInput(available)),1);
            content.addView(amounts,margin(-1,dp(controlHeight()),0));
            ClientDirectory.FollowUp promise=directory.followUp(clientId);
            if(promise!=null&&promise.amount>0){
                PaymentPromises.Progress progress=PaymentPromises.calculate(ledger.entries(),clientId,promise,LocalDate.now(),ZoneId.systemDefault());
                long outstanding=Math.min(available,Math.max(0L,promise.amount-progress.paid));
                if(progress.valid&&outstanding>0)addButton(content,"По обещанию: "+money(outstanding),false,()->amount.setText(amountInput(outstanding)),1);
            }
        }
        TextView projected=label("Остаток после записи: —",16,ACCENT,true);
        projected.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(projected);
        Runnable updateProjection=()->{
            try{
                long cents=LedgerStore.parseAmount(amount.getText().toString());
                long next=type.equals("payment")?available-cents:available+cents;
                if(next<0){projected.setText("Оплата превышает остаток");projected.setTextColor(RED);}
                else if(next>LedgerStore.MAX_AMOUNT){projected.setText("Превышен максимальный остаток");projected.setTextColor(RED);}
                else{projected.setText("Остаток после записи: "+money(next));projected.setTextColor(ACCENT);}
            }catch(Exception e){projected.setText("Остаток после записи: —");projected.setTextColor(MUTED);}
        };
        amount.addTextChangedListener(watcher(v->updateProjection.run()));
        updateProjection.run();
        LocalDate[] entryDay={savedDraft?previous.day:initialDay};
        TextView entryDate=debtDateField(entryDay);
        content.addView(formGroup(type.equals("debt")?"Дата записи":"Дата оплаты",entryDate));
        if(type.equals("payment")){
            TextView dateLimit=label("",12,MUTED,false);content.addView(dateLimit);
            long[] historicalMaximum={available};
            Runnable colorCheck=()->dateLimit.setTextColor(historicalMaximum[0]==0||previewAmount(amount)>historicalMaximum[0]?RED:MUTED);
            Runnable dateCheck=()->{
                LocalDate today=LocalDate.now();
                if(entryDay[0].equals(today)){dateLimit.setVisibility(View.GONE);return;}
                historicalMaximum[0]=DatedPayments.maximum(ledger.entries(),clientId,entryDay[0],today,ZoneId.systemDefault());
                dateLimit.setVisibility(View.VISIBLE);
                dateLimit.setText("На эту дату можно принять до "+money(historicalMaximum[0])+". Проверьте дату фактической оплаты.");
                colorCheck.run();
            };
            entryDate.addTextChangedListener(watcher(v->dateCheck.run()));
            amount.addTextChangedListener(watcher(v->colorCheck.run()));dateCheck.run();
        }
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
        Runnable persist=()->drafts.save(clientId,type,operationId[0],entryDay[0],
                amount.getText().toString(),due.getText().toString(),note.getText().toString());
        amount.addTextChangedListener(watcher(value->persist.run()));
        due.addTextChangedListener(watcher(value->persist.run()));
        note.addTextChangedListener(watcher(value->persist.run()));
        entryDate.addTextChangedListener(watcher(value->persist.run()));
        if(savedDraft){
            addButton(content,"Очистить черновик",false,()->{
                amount.setText("");due.setText("");note.setText("");
                entryDay[0]=LocalDate.now();entryDate.setText(bookDateFormat.format(entryDay[0]));
                entryDate.setContentDescription("Дата записи: "+bookDateFormat.format(entryDay[0]));
                operationId[0]=java.util.UUID.randomUUID().toString();
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
                notice.verify(clientId,cents);
                ledger.recordDatedDebt(clientId,"","",cents,entryDay[0],due.getText().toString(),note.getText().toString().trim(),operationId[0]);
                bookPayments=false;clearBookFilters();bookDayLimit=Math.max(bookDayLimit,rank);
                expandedBookDays.add(entryDay[0].toString());
            }else{
                int rank=1;
                for(BookDays.Day existing:BookDays.groupPayments(ledger.entries(),ZoneId.systemDefault()))
                    if(existing.date.isAfter(entryDay[0]))rank++;
                ledger.addDraftPayment(clientId,cents,note.getText().toString().trim(),operationId[0],entryDay[0]);
                if(cents==available)try{
                    ClientDirectory.FollowUp plan=directory.followUp(clientId);
                    if(plan!=null&&plan.amount==0)directory.setFollowUp(clientId,null,"");
                }catch(Exception ignored){}
                if(fromBook){
                    bookPayments=true;clearBookFilters();bookDayLimit=Math.max(bookDayLimit,rank);
                    expandedBookDays.clear();expandedBookDays.add(entryDay[0].toString());
                }
            }
            drafts.clear(clientId,type);
            if(type.equals("payment")&&fromBook){
                selectedClient=null;tab="Главная";
            }else if(fromBook){selectedClient=null;tab="Главная";}
            else if(fromCollections){selectedClient=null;tab="Возврат";}
            else{selectedClient=clientId;tab="Клиенты";}
            return true;
        });
        if(!fromBook&&!fromCollections&&chooser!=null)chooser.setOnDismissListener(v->{if(!saving&&unlocked)render();});
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
    private void showOperation(String entryId){
        List<LedgerStore.Entry> entries=ledger.entries();
        LedgerStore.Entry found=null;
        for(LedgerStore.Entry e:entries)if(e.id.equals(entryId)){found=e;break;}
        if(found==null){message("Запись не найдена.");return;}
        final LedgerStore.Entry entry=found;
        LedgerStore.Client person=null;
        for(LedgerStore.Client c:ledger.clients())if(c.id.equals(entry.clientId)){person=c;break;}
        if(person==null){message("Клиент не найден.");return;}
        boolean canceled=LedgerStore.canceled(entries).contains(entry.id);
        long balance=LedgerStore.balance(entries,entry.clientId);
        boolean active=!canceled&&!entry.type.equals("reversal");
        LedgerStore.Entry last=ledger.lastActive(entry.clientId);
        LinearLayout content=stack(6);
        String kind=entry.type.equals("debt")?"Долг":entry.type.equals("payment")?"Оплата":"Отмена";
        content.addView(label(kind+" · "+person.name,16,TEXT,true));
        content.addView(balancePanel("Сумма записи",entry.amount));
        content.addView(label("Дата: "+formatDate(entry.date),14,MUTED,false));
        String operationStatus=canceled?"отменена":entry.type.equals("reversal")?"отмена":"действует";
        if(active&&"debt".equals(entry.type)){
            Long remaining=ClientStatement.build(entries,entry.clientId,ZoneId.systemDefault()).remaining.get(entry.id);
            if(remaining!=null){
                operationStatus=remaining==0?"погашен":remaining<entry.amount?"частично погашен":"не погашен";
                content.addView(label("Осталось по записи: "+money(remaining),16,remaining==0?MUTED:ACCENT,true));
            }
        }
        content.addView(label("Статус: "+operationStatus,13,canceled?ALERT:MUTED,false));
        content.addView(label("Текущий долг клиента: "+money(balance),13,MUTED,false));
        if(!entry.due.isEmpty())content.addView(label("Вернуть до: "+entry.due,14,TEXT,false));
        if(!entry.note.isEmpty())content.addView(label(entry.note,14,TEXT,false));
        if(entry.type.equals("reversal")){
            for(LedgerStore.Entry source:entries)if(source.id.equals(entry.reverses)){
                clientMenuAction(content,"Исходная запись · "+money(source.amount),()->showOperation(source.id));break;
            }
        }else if(canceled){
            for(LedgerStore.Entry reversal:entries)if(entry.id.equals(reversal.reverses)){
                clientMenuAction(content,"Причина отмены",()->showOperation(reversal.id));break;
            }
        }
        if(entry.purchase!=null)clientMenuAction(content,"Состав покупки",()->showPurchaseDetails(entry));
        clientMenuAction(content,"Карточка клиента",()->{selectedClient=entry.clientId;tab="Клиенты";render();});
        clientMenuAction(content,"Все операции клиента",()->{
            historyClientId=entry.clientId;historyQuery="";historyFrom=null;historyTo=null;historyFilter="Все";
            historyVisibleLimit=100;selectedClient=null;tab="История";render();
        });
        if(active&&!directory.archived(entry.clientId,balance)){
            if(last!=null&&last.id.equals(entry.id)){
                clientMenuAction(content,"Исправить эту запись",()->showCorrection(entry.clientId,entry.id));
                clientMenuAction(content,"Отменить эту запись",()->showReversal(entry.clientId,entry.id));
            }else content.addView(label("Для сохранения совместимости копий изменить можно только последнюю действующую операцию клиента.",12,MUTED,false));
        }
        showDialog("Сведения об операции",content,null);
    }
    private void showCorrection(String clientId){showCorrection(clientId,null);}
    private void showCorrection(String clientId,String expectedId) {
        LedgerStore.Entry original=ledger.lastActive(clientId);
        if(original==null){message("Нет действующей записи для исправления.");return;}
        if(expectedId!=null&&!expectedId.equals(original.id)){message("Запись уже не последняя. Откройте актуальную историю.");return;}
        long currentBalance=LedgerStore.balance(ledger.entries(),clientId);
        LinearLayout content=stack(7);
        content.addView(label(("debt".equals(original.type)?"Долг":"Оплата")+" · "+formatDate(original.date),14,TEXT,true));
        content.addView(label("Исходная сумма: "+money(original.amount)+" · текущий долг: "+money(currentBalance),13,MUTED,false));
        content.addView(label("Исправление записывается сегодняшней датой. Исходная запись остаётся в истории.",12,MUTED,false));
        String formatted=String.format(Locale.ROOT,"%d,%02d",
                original.amount/100,original.amount%100);
        EditText amount=field("Сумма",formatted);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(original.purchase!=null){
            amount.setEnabled(false);
            content.addView(label("Сумма связана с товарами. Для изменения состава отмените последнюю покупку и запишите новую.",13,ALERT,false));
        }
        content.addView(formGroup("Исправленная сумма, ₽",amount));
        TextView projection=label("",15,ACCENT,true);
        projection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(projection);
        Runnable preview=()->{
            try{
                long next=LedgerStore.correctedBalance(currentBalance,original,LedgerStore.parseAmount(amount.getText().toString()));
                projection.setText("Остаток после исправления: "+money(next));projection.setTextColor(ACCENT);
            }catch(IllegalArgumentException error){projection.setText(error.getMessage());projection.setTextColor(RED);}
        };
        amount.addTextChangedListener(watcher(v->preview.run()));preview.run();
        EditText due=field("Без срока",original.due);
        due.setFocusable(false);due.setClickable(true);
        due.setOnClickListener(v->{
            LocalDate initial=correctionDueDate(due.getText().toString(),LocalDate.now());
            new DatePickerDialog(this,(picker,y,m,d)->due.setText(
                    String.format(Locale.ROOT,"%04d-%02d-%02d",y,m+1,d)),
                    initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();
        });
        if(original.type.equals("debt")){
            content.addView(formGroup("Вернуть до",due));
            LinearLayout clearDue=stack(0);
            addButton(clearDue,"Без срока",false,()->due.setText(""),1);
            clearDue.setVisibility(due.getText().length()==0?View.GONE:View.VISIBLE);
            due.addTextChangedListener(watcher(v->clearDue.setVisibility(v.isEmpty()?View.GONE:View.VISIBLE)));
            content.addView(clearDue);
        }
        EditText note=field("Комментарий",original.note);
        content.addView(formGroup("Комментарий",note));
        EditText reason=field("Почему исправлено",null);
        content.addView(formGroup("Причина исправления (обязательно)",reason));
        showDialog("Исправить последнюю запись",content,()->{
            ledger.correctSelected(clientId,original.id,LedgerStore.parseAmount(amount.getText().toString()),
                    original.type.equals("debt")?due.getText().toString():"",
                    note.getText().toString().trim(),reason.getText().toString());
            selectedClient=clientId;tab="Клиенты";return true;
        });
    }
    private void showReversal(String clientId){showReversal(clientId,null);}
    private void showReversal(String clientId,String expectedId) {
        LedgerStore.Entry last=ledger.lastActive(clientId);
        if(last==null){message("Отменять пока нечего.");return;}
        if(expectedId!=null&&!expectedId.equals(last.id)){message("Запись уже не последняя. Откройте актуальную историю.");return;}
        long balance=LedgerStore.balance(ledger.entries(),clientId);
        long after;
        try{after=LedgerStore.correctedBalance(balance,last,0L);}
        catch(IllegalArgumentException invalid){message(invalid.getMessage());return;}
        LinearLayout content=stack(7);
        content.addView(label((last.type.equals("debt")?"Долг":"Оплата")+" · "+formatDate(last.date),14,TEXT,true));
        content.addView(label("Отменяемая сумма: "+money(last.amount),15,TEXT,true));
        content.addView(label("Текущий долг: "+money(balance),13,MUTED,false));
        content.addView(label("Остаток после отмены: "+money(after),16,after>balance?ALERT:ACCENT,true));
        content.addView(label("Отмена записывается сегодняшней датой. Исходная операция остаётся в истории.",12,MUTED,false));
        EditText reason=field("Например, запись внесена ошибочно",null);
        content.addView(formGroup("Причина отмены",reason));
        showDialog("Отменить последнюю запись",content,()->{
            ledger.reverseSelected(clientId,last.id,reason.getText().toString());return true;
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
                    directory.clearAfterRestore();new QuickDebtDrafts(this).clear();new LedgerDrafts(this).clearAll();
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
        if(selectedClient!=null){selectedClient=null;tab=returnToCollections?"Возврат":"Клиенты";returnToCollections=false;render();}
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
