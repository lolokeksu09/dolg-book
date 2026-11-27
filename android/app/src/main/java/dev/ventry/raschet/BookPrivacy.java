package dev.ventry.raschet;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Local UI gate. Uses the system credential, not a new application password. */
final class BookPrivacy {
    static final long RELOCK_MS=60_000L;
    private final Context context;
    private final SharedPreferences prefs;
    BookPrivacy(Context context){
        this.context=context;
        prefs=context.getSharedPreferences("raschet_privacy",Context.MODE_PRIVATE);
    }
    boolean enabled(){return prefs.getBoolean("credential_gate",false);}
    boolean hideScreens(){return enabled()||prefs.getBoolean("hide_screens",false);}
    void setEnabled(boolean value){prefs.edit().putBoolean("credential_gate",value).apply();}
    void setHideScreens(boolean value){prefs.edit().putBoolean("hide_screens",value).apply();}
    static boolean shouldRelock(boolean enabled,long stoppedAt,long now){
        return enabled&&stoppedAt>=0&&now>=stoppedAt&&now-stoppedAt>=RELOCK_MS;
    }
    @SuppressWarnings("deprecation")
    Intent credential(){
        KeyguardManager manager=context.getSystemService(KeyguardManager.class);
        if(manager==null||!manager.isDeviceSecure())return null;
        return manager.createConfirmDeviceCredentialIntent("Расчёт","Подтвердите код блокировки устройства");
    }
}
