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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

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
    internal data class Overview(val title:String,val detail:String,val subscription:String?=null,val allowed:Boolean=false)
    private fun localTime(seconds:Long)=Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",Locale("ru")))
    // All information comes from the same verified envelope as the write guard.
    // No unsigned claims, network calls or secrets are exposed by this projection.
    internal fun overview(context:Context):Overview=synchronized(lock){
        try {
            val r=LicenseVault(context).read()?:return@synchronized Overview("Не активирована","Введите код Ventry, чтобы начать записывать долги.")
            val pending=r.pendingCode!=null
            val note=if(pending)"\nЕсть незавершённая активация. Повторите сохранённый запрос." else ""
            val c=claims(r)?:return@synchronized Overview("Требуется проверка","Обновите лицензию."+note)
            val now=LicenseVault.now(context)
            val allowed=r.anchor?.let{LicensePolicy.permits(c,it,now)}==true
            val title=when {
                allowed -> "Доступ активен"
                c.validUntil!=null && now.wallSec>=c.validUntil -> "Срок доступа истёк"
                !c.allowed -> "Новые долги недоступны"
                else -> "Требуется онлайн-проверка"
            }
            val detail=buildString{
                append(if(allowed)"Новые долги доступны" else "Новые долги недоступны")
                append("\nСрок доступа: "+(c.validUntil?.let(::localTime)?:"—"))
                if(allowed)append("\nОфлайн-доступ до: "+localTime(minOf(c.leaseUntil,checkNotNull(c.validUntil))))
                else append("\nОбновите подписку или обратитесь в Ventry.")
                append(note)
            }
            Overview(title,detail,c.subscription,allowed)
        }catch(_:Exception){Overview("Требуется проверка","Лицензия не читается. Данные книги не изменены.")}
    }
    internal fun hasPending(context:Context)=synchronized(lock){runCatching{LicenseVault(context).read()?.pendingCode!=null}.getOrDefault(false)}
    internal class Denied:Exception()
    internal class Unavailable:Exception()
    private val privateTls by lazy {
        val raw=android.util.Base64.decode(BuildConfig.LICENSE_CA_DER,android.util.Base64.NO_WRAP)
        val ca=CertificateFactory.getInstance("X.509").generateCertificate(raw.inputStream()) as X509Certificate
        require(ca.encoded.contentEquals(raw) && ca.basicConstraints>=0)
        ca.checkValidity()
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null,null);setCertificateEntry("ventry-ca",ca)
        }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(trust)}
        SSLContext.getInstance("TLS").apply{init(null,tm.trustManagers,null)}.socketFactory
    }
    private fun post(path:String,token:String,body:JSONObject):String{
        require(BuildConfig.LICENSE_REQUIRED && token.token())
        val connection=URL(BuildConfig.LICENSE_ORIGIN+path).openConnection() as HttpURLConnection
        try{
            if(connection is HttpsURLConnection){
                require(BuildConfig.LICENSE_CA_DER.isNotEmpty())
                connection.sslSocketFactory=privateTls
                // Default hostname verifier remains enabled; IP SAN must match 127.0.0.1.
            }else require(BuildConfig.LICENSE_CA_DER.isEmpty())
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
