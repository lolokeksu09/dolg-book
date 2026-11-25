package dev.ventry.raschet;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared serialized recovery queue; never retains an Activity. */
public final class RecoveryScheduler {
    private static final String PREFS="raschet_local_recovery";
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();

    private RecoveryScheduler(){}

    public static void enqueue(Context context){
        Context app=context.getApplicationContext();
        if(!app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("enabled",false))return;
        WORK.execute(()->{
            try(LedgerStore snapshotBook=new LedgerStore(app)){
                new RecoveryVault(app).create(snapshotBook);
                app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                        .putLong("last_success",System.currentTimeMillis()).remove("last_error").apply();
            }catch(Exception e){
                app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                        .putString("last_error","Не удалось создать локальную копию").apply();
            }
        });
    }
}
