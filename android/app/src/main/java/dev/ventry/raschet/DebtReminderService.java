package dev.ventry.raschet;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in, inexact daily check: local-only, no contacts, network or SMS.
 * Android may defer JobScheduler jobs (battery saver, doze, vendor restrictions).
 */
public final class DebtReminderService extends JobService {
    private static final int JOB_ID=9431, NOTIFICATION_ID=9432;
    private static final String PREFS="raschet_reminders";
    private static final String CHANNEL_ID="raschet_due_alerts";
    private volatile boolean stopped;

    public static final class Status {
        public final int late, soon;
        Status(int late,int soon){this.late=late;this.soon=soon;}
    }
    public static Status summarize(List<CollectionsInsights.CustomerStatus> statuses){
        int late=0,soon=0;
        for(CollectionsInsights.CustomerStatus row:statuses){
            if(row.overdue>0)late++;
            if(row.upcoming>0)soon++;
        }
        return new Status(late,soon);
    }
    public static boolean enabled(Context context){
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getBoolean("enabled",false);
    }
    public static boolean hasPermission(Context context){
        return Build.VERSION.SDK_INT<33||context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    public static boolean setEnabled(Context context,boolean value){
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler==null)return false;
        if(!value){
            scheduler.cancel(JOB_ID);
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled",false).apply();
            return true;
        }
        if(!hasPermission(context))return false;
        JobInfo info=new JobInfo.Builder(JOB_ID,
                new ComponentName(context,DebtReminderService.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setPersisted(true)
                .build();
        if(scheduler.schedule(info)!=JobScheduler.RESULT_SUCCESS)return false;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putBoolean("enabled",true).apply();
        return true;
    }
    @Override public boolean onStartJob(JobParameters params){
        if(!enabled(this)||!hasPermission(this))return false;
        stopped=false;
        Context context=getApplicationContext();
        Thread worker=new Thread(()->{
            try(LedgerStore book=new LedgerStore(context)){
                Status status=summarize(CollectionsInsights.analyze(
                        book.clients(),book.entries(),LocalDate.now()));
                if(!stopped&&(status.late>0||status.soon>0))
                    display(context,status);
            }catch(Exception e){
                // Daily check is advisory; never alter the book because of a failure.
            }finally{
                jobFinished(params,false);
            }
        },"RaschetLocalReminder");
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped=true;
        return true;
    }
    private void display(Context context,Status status){
        String today=LocalDate.now().toString();
        android.content.SharedPreferences prefs=context.getSharedPreferences(
                PREFS,Context.MODE_PRIVATE);
        if(today.equals(prefs.getString("last_day","")))return;
        if(!hasPermission(context))return;
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null)return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,
                "Сроки возврата в Расчёте",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Ежедневная проверка сроков по локальной долговой книге");
        manager.createNotificationChannel(channel);
        Intent intent=new Intent(context,SplashActivity.class);
        PendingIntent open=PendingIntent.getActivity(context,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title=status.late>0?"Есть просроченные долги":"Скоро срок возврата";
        String body="Просрочка: "+status.late+" клиентов · скоро срок: "+
                status.soon+". Откройте книгу для проверки.";
        Notification notification=new Notification.Builder(context,CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_history)
                .setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        manager.notify(NOTIFICATION_ID,notification);
        prefs.edit().putString("last_day",today).apply();
    }
}
