package dev.ventry.raschet.cloud

import android.content.Context
import dev.ventry.raschet.BuildConfig
import dev.ventry.raschet.license.LicenseManager
import dev.ventry.raschet.license.hex
import dev.ventry.raschet.license.uuid
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal data class CloudSlot(val slot:Int,val version:String,val size:Int,val sha256:String,val savedAt:OffsetDateTime)
internal object CloudBackupClient {
    private const val ORIGIN="https://license.ventrylab.top"
    private const val META_MAX=8192
    private data class Reply(val bytes:ByteArray,val hash:String?)
    private fun call(context:Context,path:String,body:ByteArray=byteArrayOf(),headers:Map<String,String> = emptyMap(),limit:Int=META_MAX):Reply{
        require(BuildConfig.LICENSE_PUBLIC_PROFILE && BuildConfig.LICENSE_ORIGIN==ORIGIN){"Облачные копии доступны только в лицензируемой версии."}
        val credential=LicenseManager.cloudCredential(context)
        val connection=URL(ORIGIN+"/api/v1/backups/"+path).openConnection() as HttpsURLConnection
        try{
            connection.instanceFollowRedirects=false
            connection.connectTimeout=8000
            connection.readTimeout=25000
            connection.useCaches=false
            connection.requestMethod="POST"
            connection.doOutput=true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Authorization","Bearer "+credential.refresh)
            connection.setRequestProperty("X-Ventry-Installation",credential.installation)
            connection.setRequestProperty("Accept-Encoding","identity")
            for((k,v) in headers)connection.setRequestProperty(k,v)
            connection.outputStream.use{it.write(body)}
            val status=connection.responseCode
            if(status!=200)throw IllegalArgumentException(when(status){
                403->"Срок подписки закончился. Скачивание прежних копий доступно."
                409->"Копия изменилась. Обновите список перед повтором."
                413->"Копия превышает 16 МиБ."
                404->"Облачная копия или доступ пока недоступны. Проверьте лицензию и обновите список."
                429,503->"Облако занято. Повторите позже."
                else->"Облачное хранилище временно недоступно."
            })
            val type=connection.contentType?.substringBefore(';')?.trim()
            require(type==if(limit==CloudCipher.MAX)"application/octet-stream" else "application/json"){"Неверный ответ облачного сервера."}
            val length=connection.contentLengthLong
            require(length in -1L..limit.toLong()){"Слишком большой ответ облака."}
            val bytes=connection.inputStream.use{stream->
                val out=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true){
                    val n=stream.read(buffer)
                    if(n<0)break
                    require(out.size()+n<=limit){"Слишком большой ответ облака."}
                    out.write(buffer,0,n)
                }
                out.toByteArray()
            }
            if(length>=0)require(bytes.size.toLong()==length){"Ответ облака неполный."}
            return Reply(bytes,connection.getHeaderField("X-Ventry-Backup-SHA256"))
        }finally{connection.disconnect()}
    }
    private fun parse(j:JSONObject):CloudSlot{
        val slot=j.getInt("slot")
        val version=j.getString("version").uuid()
        val size=j.getInt("size")
        val hash=j.getString("sha256")
        require(slot in 1..3 && size in 49..CloudCipher.MAX && hash.matches(Regex("[0-9a-f]{64}")))
        return CloudSlot(slot,version,size,hash,OffsetDateTime.parse(j.getString("saved_at")))
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    fun list(context:Context):List<CloudSlot>{
        val j=JSONArray(call(context,"list").bytes.toString(Charsets.UTF_8))
        require(j.length()<=3)
        val items=(0 until j.length()).map{parse(j.getJSONObject(it))}
        require(items.map{it.slot}.distinct().size==items.size)
        return items.sortedBy{it.slot}
    }
    fun put(context:Context,slot:Int,expected:String?,data:ByteArray):CloudSlot{
        require(slot in 1..3 && data.size in 49..CloudCipher.MAX)
        val version=UUID.randomUUID().toString()
        val reply=call(context,"$slot/put",data,mapOf("Content-Type" to "application/octet-stream",
            "X-Ventry-Backup-Version" to version,"X-Ventry-Backup-Expected" to (expected?.uuid()?:"empty")))
        val saved=parse(JSONObject(reply.bytes.toString(Charsets.UTF_8)))
        require(saved.slot==slot && saved.version==version && saved.size==data.size && saved.sha256==hash(data)){"Ответ записи копии не совпадает. Обновите список."}
        return saved
    }
    fun read(context:Context,slot:Int,version:String,size:Int,sha256:String):ByteArray{
        require(slot in 1..3 && size in 49..CloudCipher.MAX)
        val reply=call(context,"$slot/read",headers=mapOf("X-Ventry-Backup-Version" to version.uuid()),limit=CloudCipher.MAX)
        require(reply.bytes.size==size && reply.hash==sha256 && hash(reply.bytes)==sha256){"Целостность облачной копии не подтверждена."}
        return reply.bytes
    }
}
