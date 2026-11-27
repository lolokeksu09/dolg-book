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
            val waiting=if(r.pendingCode!=null)"Есть незавершённая активация. Повторите сохранённый запрос.\n" else ""
            val c=claims(r)?:return@synchronized waiting+"Требуется обновить лицензию."
            val end=c.validUntil?.let{Instant.ofEpochSecond(it).toString()}?:"—"
            waiting+(if(permits(context))"Новые долги доступны" else "Новые долги недоступны; обновите подписку")+"\nПодписка до: "+end+
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
            val json=connection.contentType?.substringBefore(';')?.trim()=="application/json"
            if(code==404 && json){
                // Only the explicit application denial may invalidate an entitlement.
                // A missing route / reverse proxy 404 is a temporary failure.
                val error=connection.errorStream?.use{it.readNBytesCompat(1024)}
                if(error!=null && runCatching{JSONObject(error).optString("error")=="activation_unavailable"}.getOrDefault(false))throw Denied()
            }
            if(code!=200 || !json)throw Unavailable()
            return connection.inputStream.use{stream->
                val out=ByteArrayOutputStream();val b=ByteArray(4096)
                while(true){val n=stream.read(b);if(n<0)break;require(out.size()+n<=16384);out.write(b,0,n)}
                out.toString("UTF-8")
            }
        }finally{connection.disconnect()}
    }
    private fun java.io.InputStream.readNBytesCompat(limit:Int):String {
        val out=ByteArrayOutputStream();val buffer=ByteArray(1024)
        while(true){val n=read(buffer);if(n<0)break;if(out.size()+n>limit)throw Unavailable();out.write(buffer,0,n)}
        return out.toString("UTF-8")
    }
    private fun accept(context:Context,r:Record,raw:String,replacement:Boolean=false){
        val next=verifier().verify(raw,r.installation)
        // A corrupt cached envelope is not a trusted revision baseline; a fresh
        // response must still pass the pinned signature and clock checks.
        val previous=runCatching{claims(r)}.getOrNull()
        require(previous==null || (next.issued>=previous.issued &&
            (if(next.subscription==previous.subscription) next.revision>=previous.revision else replacement)))
        val now=LicenseVault.now(context)
        // Reject delayed/replayed signed responses instead of resetting the offline lease.
        require(kotlin.math.abs(now.wallSec-next.issued)<=300)
        synchronized(lock){
            // A same-second retry cannot reset an old monotonic anchor.
            val anchor=if(previous!=null && next.issued==previous.issued && r.anchor!=null) r.anchor else now
            val secret=if(replacement)checkNotNull(r.pendingRefresh)else r.refresh
            LicenseVault(context).write(r.copy(refresh=secret,pendingCode=null,pendingRefresh=null,envelope=raw,anchor=anchor))
        }
    }
    internal class PendingExists:Exception()
    internal suspend fun activate(context:Context,code:String?)=withContext(Dispatchers.IO){
        network.withLock{
            val vault=LicenseVault(context)
            val r=synchronized(lock){
                val old=vault.read()
                val entered=code?.trim()?.takeIf{it.isNotEmpty()}
                require(entered==null || entered.token())
                if(old?.pendingCode!=null){
                    if(entered!=null && entered!=old.pendingCode)throw PendingExists()
                    old
                }else{
                    require(entered!=null)
                    // Retain the prior verified lease and refresh secret until a signed
                    // replacement is verified. Persist the candidate before network I/O.
                    LicenseVault.replacement(old,entered).also(vault::write)
                }
            }
            val activation=checkNotNull(r.pendingCode)
            val candidate=checkNotNull(r.pendingRefresh)
            val identity=JSONObject().put("installation_id",r.installation).put("product",PRODUCT)
            // A previous activation may already be committed, even after its code expires.
            // Recover it with the independently persisted refresh credential first.
            val raw=try{post("/api/v1/entitlement",candidate,identity)}
                catch(_:Denied){
                    try{post("/api/v1/activate",activation,JSONObject(identity.toString()).put("refresh_secret",candidate))}
                    catch(e:Denied){
                        // No proof of a new activation. Keep the prior entitlement.
                        synchronized(lock){vault.write(r.copy(pendingCode=null,pendingRefresh=null))}
                        throw e
                    }
                }
            accept(context,r,raw,replacement=true)
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
