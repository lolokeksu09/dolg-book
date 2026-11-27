package dev.ventry.raschet.license

import android.content.Context
import dev.ventry.raschet.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.time.Instant

object LicenseManager {
    private val lock=Any()
    private val network=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var lastAttempt=Long.MIN_VALUE
    private fun verifier()=EntitlementVerifier(BuildConfig.LICENSE_PUBLIC_KEY.unhex(32))
    private fun claims(r:Record)=r.envelope?.let{verifier().verify(it,r.installation)}
    @JvmStatic fun requireNewDebt(context:Context) {
        if(!BuildConfig.LICENSE_REQUIRED)return
        if(!permits(context))throw IllegalArgumentException("Для нового долга нужна действующая подписка. Настройки → Лицензия. Просмотр, оплата и резервные копии доступны.")
    }
    internal fun permits(context:Context):Boolean= synchronized(lock){
        try {
            val r=LicenseVault(context).read()?:return@synchronized false
            val c=claims(r)?:return@synchronized false
            val a=r.anchor?:return@synchronized false
            LicensePolicy.permits(c,a,LicenseVault.now(context))
        }catch(_:Exception){false}
    }
    internal fun description(context:Context):String=synchronized(lock){
        try{
            val r=LicenseVault(context).read()?:return@synchronized "Не активирована. Введите код Ventry."
            if(r.pendingCode!=null)return@synchronized "Активация не завершена. Повторите сохранённый запрос."
            val c=claims(r)?:return@synchronized "Требуется обновить лицензию."
            val end=c.validUntil?.let{Instant.ofEpochSecond(it).toString()}?:"—"
            (if(permits(context))"Новые долги доступны" else "Новые долги недоступны; обновите подписку")+"\nПодписка до: "+end+
                "\nОфлайн-разрешение до: "+Instant.ofEpochSecond(c.leaseUntil)
        }catch(_:Exception){"Лицензия не читается. Данные книги не изменены."}
    }
    internal fun hasPending(context:Context)=synchronized(lock){runCatching{LicenseVault(context).read()?.pendingCode!=null}.getOrDefault(false)}
    internal class Denied:Exception()
    internal class Unavailable:Exception()
    private fun post(path:String,token:String,body:JSONObject):String{
        require(BuildConfig.LICENSE_REQUIRED && token.token())
        val connection=URL(BuildConfig.LICENSE_ORIGIN+path).openConnection() as HttpURLConnection
        try{
            connection.instanceFollowRedirects=false;connection.connectTimeout=8000;connection.readTimeout=15000
            connection.useCaches=false;connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer "+token)
            connection.setRequestProperty("Content-Type","application/json")
            val bytes=body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use{it.write(bytes)}
            val code=connection.responseCode
            if(code==404)throw Denied()
            if(code!=200 || connection.contentType?.startsWith("application/json")!=true)throw Unavailable()
            return connection.inputStream.use{stream->
                val out=ByteArrayOutputStream();val b=ByteArray(4096)
                while(true){val n=stream.read(b);if(n<0)break;require(out.size()+n<=16384);out.write(b,0,n)}
                out.toString("UTF-8")
            }
        }finally{connection.disconnect()}
    }
    private fun accept(context:Context,r:Record,raw:String){
        val next=verifier().verify(raw,r.installation)
        val previous=claims(r)
        require(previous==null || (next.subscription==previous.subscription && next.revision>=previous.revision && next.issued>=previous.issued))
        val now=LicenseVault.now(context)
        // Reject delayed/replayed signed responses instead of resetting the offline lease.
        require(kotlin.math.abs(now.wallSec-next.issued)<=300)
        synchronized(lock){LicenseVault(context).write(r.copy(pendingCode=null,envelope=raw,anchor=now))}
    }
    internal suspend fun activate(context:Context,code:String?)=withContext(Dispatchers.IO){
        network.withLock{
            val vault=LicenseVault(context)
            val r=synchronized(lock){
                val old=vault.read()
                val entered=code?.trim()?.takeIf{it.isNotEmpty()}
                require(entered==null || entered.token())
                if(old?.pendingCode!=null && (entered==null || entered==old.pendingCode))old
                else {
                    require(entered!=null)
                    // Explicit new activation replaces local entitlement; never touch the ledger.
                    LicenseVault.fresh(old?.installation,entered).also(vault::write)
                }
            }
            val activation=checkNotNull(r.pendingCode)
            val data=JSONObject().put("installation_id",r.installation).put("product",PRODUCT).put("refresh_secret",r.refresh)
            accept(context,r,post("/api/v1/activate",activation,data))
        }
    }
    internal suspend fun refresh(context:Context)=withContext(Dispatchers.IO){
        network.withLock{
            val r=synchronized(lock){LicenseVault(context).read()}?:throw Unavailable()
            require(r.pendingCode==null)
            val data=JSONObject().put("installation_id",r.installation).put("product",PRODUCT)
            try{accept(context,r,post("/api/v1/entitlement",r.refresh,data))}
            catch(e:Denied){
                synchronized(lock){LicenseVault(context).write(r.copy(envelope=null,anchor=null))}
                throw e
            }
        }
    }
    @JvmStatic fun refreshInBackground(context:Context){
        if(!BuildConfig.LICENSE_REQUIRED)return
        val app=context.applicationContext
        synchronized(lock){
            val now=android.os.SystemClock.elapsedRealtime()
            if(lastAttempt!=Long.MIN_VALUE && now-lastAttempt<15*60*1000)return
            lastAttempt=now
        }
        scope.launch{
            try{
                val r=synchronized(lock){LicenseVault(app).read()}?:return@launch
                if(r.pendingCode==null)refresh(app)
            }catch(_:Exception){/* Offline lease remains bounded; no credentials in logs. */}
        }
    }
}
