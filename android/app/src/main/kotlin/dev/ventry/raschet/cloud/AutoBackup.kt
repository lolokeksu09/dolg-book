package dev.ventry.raschet.cloud

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.LedgerStore
import dev.ventry.raschet.license.LicenseManager
import dev.ventry.raschet.license.hex
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicit opt-in; serialized with enable/disable, never writes slots 1 or 2. */
internal object AutoBackup {
    const val JOB=9433
    private val lock=Any()
    private const val PREFS="raschet_cloud_auto"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    data class Status(val enabled:Boolean,val detail:String,val last:Long)
    fun status(c:Context):Status=Status(AutoBackupVault(c).exists(),
        prefs(c).getString("detail","Не настроено").orEmpty(),prefs(c).getLong("last",0))
    private fun detail(c:Context,text:String,success:Boolean=false){
        val edit=prefs(c).edit().putString("detail",text)
        if(success)edit.putLong("last",System.currentTimeMillis())
        edit.apply()
    }
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).hex()
    private fun binding(c:LicenseManager.CloudCredential)=hash((c.installation+":"+c.refresh).toByteArray(Charsets.UTF_8))
    private fun scheduler(c:Context)=checkNotNull(c.getSystemService(JobScheduler::class.java))
    private fun schedule(c:Context):Boolean=scheduler(c).schedule(JobInfo.Builder(JOB,ComponentName(c,CloudBackupJob::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setPersisted(true)
        .setPeriodic(TimeUnit.HOURS.toMillis(24))
        .build())==JobScheduler.RESULT_SUCCESS
    private fun stop(c:Context,message:String){
        scheduler(c).cancel(JOB)
        AutoBackupVault(c).clear()
        detail(c,message)
    }
    fun disable(c:Context)=synchronized(lock){stop(c,"Автокопирование выключено. Копия в облаке сохранена.")}
    fun enable(app:Application,ledger:LedgerStore,expected:String?,password:CharArray)=synchronized(lock){
        require(BuildConfig.LICENSE_PUBLIC_PROFILE)
        require(!AutoBackupVault(app).exists()){"Сначала выключите прежнее автокопирование."}
        val credential=LicenseManager.cloudCredential(app)
        val plain=CloudBookService.snapshot(ledger)
        try{
            val digest=hash(plain)
            val encrypted=CloudCipher.encrypt(plain,password)
            val slot=try{CloudBackupClient.put(app,AutoBackupPolicy.SLOT,expected,encrypted,credential)}finally{encrypted.fill(0)}
            val vault=AutoBackupVault(app)
            try{
                vault.write(AutoBackupVault.Config(binding(credential),slot.version,digest,password))
                if(!schedule(app))throw IllegalStateException("JobScheduler rejected backup")
            }catch(_:Exception){
                runCatching{stop(app,"Автокопирование не настроено. Первая копия сохранена.")}
                throw IllegalArgumentException("Первая копия сохранена, но не удалось включить автокопирование.")
            }
            detail(app,"Ежедневное автокопирование включено",true)
        }finally{plain.fill(0)}
    }
    fun run(app:Application,stopped:()->Boolean)=synchronized(lock){
        if(!BuildConfig.LICENSE_PUBLIC_PROFILE || stopped())return@synchronized
        val vault=AutoBackupVault(app)
        if(!vault.exists())return@synchronized
        val config=try{vault.read()}catch(_:Exception){
            stop(app,"Ключ автокопирования недоступен. Настройте заново.");return@synchronized
        }?:return@synchronized
        try{
            val credential=LicenseManager.cloudCredential(app)
            if(binding(credential)!=config.binding){
                stop(app,"Привязка лицензии изменилась. Настройте автокопирование заново.");return@synchronized
            }
            val remote=CloudBackupClient.list(app,credential).firstOrNull{it.slot==AutoBackupPolicy.SLOT}
            if(remote?.version!=config.version){
                stop(app,"Слот 3 изменён. Автокопирование остановлено, копии сохранены.");return@synchronized
            }
            if(stopped())return@synchronized
            val plain=LedgerStore(app).use{CloudBookService.snapshot(it)}
            try{
                val digest=hash(plain)
                when(AutoBackupPolicy.decide(config.binding,binding(credential),config.version,remote?.version,config.hash,digest)){
                    AutoBackupPolicy.Action.STOP -> stop(app,"Привязка или слот изменились. Настройте заново.")
                    AutoBackupPolicy.Action.SKIP -> detail(app,"Книга не изменилась; прежняя копия актуальна.")
                    AutoBackupPolicy.Action.UPLOAD -> {
                        if(stopped())return@synchronized
                        val encrypted=CloudCipher.encrypt(plain,config.password)
                        try{
                            if(stopped())return@synchronized
                            // Use the very credential and CAS version used for the list, never a new account.
                            val saved=CloudBackupClient.put(app,AutoBackupPolicy.SLOT,config.version,encrypted,credential)
                            vault.write(AutoBackupVault.Config(config.binding,saved.version,digest,config.password))
                            detail(app,"Автоматическая копия сохранена",true)
                        }finally{encrypted.fill(0)}
                    }
                }
            }finally{plain.fill(0)}
        }catch(_:Exception){
            // A lost response may have committed; next list must still match the old CAS version.
            // Never guess or retry against a newly observed version.
            detail(app,"Не удалось обновить автокопию. Следующая проверка — при доступной сети.")
        }finally{config.clear()}
    }
}
