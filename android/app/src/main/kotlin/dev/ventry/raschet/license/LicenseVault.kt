package dev.ventry.raschet.license

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class Record(val installation:String,val refresh:String,val pendingCode:String?=null,
    val envelope:String?=null,val anchor:Anchor?=null,val pendingRefresh:String?=null)
internal class LicenseVault(private val context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"ventry-entitlement-v1.bin"))
    private val alias="ventry-entitlement-v1"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val existing=ks.getKey(alias,null)
        if(existing!=null)return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun read():Record? {
        if(!file.baseFile.exists())return null
        require(file.baseFile.length() in 29..32768)
        val bytes=file.readFully()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD("ventry-entitlement-v1".toByteArray())
        val j=JSONObject(cipher.doFinal(bytes,12,bytes.size-12).toString(Charsets.UTF_8))
        val installation=j.getString("installation").uuid()
        val refresh=j.getString("refresh").also{require(it.token())}
        val pending=if(j.isNull("pending"))null else j.getString("pending").also{require(it.token())}
        val env=if(j.isNull("envelope"))null else j.getString("envelope")
        val a=if(j.isNull("anchor"))null else j.getJSONObject("anchor").let{Anchor(it.getInt("boot"),it.getLong("elapsed"),it.getLong("wall"))}
        require((env==null)==(a==null))
        // Legacy v1 pending record stored the same secret in refresh. Preserve it.
        val next=if(j.isNull("pending_refresh"))pending?.let{refresh} else j.getString("pending_refresh").also{require(it.token())}
        require((pending==null)==(next==null))
        return Record(installation,refresh,pending,env,a,next)
    }
    fun write(r:Record) {
        val j=JSONObject().apply{
            put("installation",r.installation);put("refresh",r.refresh);put("pending",r.pendingCode?:JSONObject.NULL)
            put("pending_refresh",r.pendingRefresh?:JSONObject.NULL)
            put("envelope",r.envelope?:JSONObject.NULL)
            put("anchor",r.anchor?.let{a->JSONObject().put("boot",a.boot).put("elapsed",a.elapsedMs).put("wall",a.wallSec)}?:JSONObject.NULL)
        }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());require(cipher.iv.size==12)
        cipher.updateAAD("ventry-entitlement-v1".toByteArray())
        val bytes=cipher.iv+cipher.doFinal(j.toString().toByteArray(Charsets.UTF_8))
        val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun delete(){file.delete()}
    companion object{
        fun now(context:Context)=Anchor(Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1),
            SystemClock.elapsedRealtime(),System.currentTimeMillis()/1000)
        fun fresh(installation:String?=null,code:String):Record {
            val secret=ByteArray(32).also{SecureRandom().nextBytes(it)}.hex()
            return Record(installation?:UUID.randomUUID().toString(),secret,code,pendingRefresh=secret)
        }
        fun replacement(old:Record?,code:String):Record {
            val candidate=fresh(old?.installation,code)
            return old?.copy(pendingCode=code,pendingRefresh=candidate.refresh)?:candidate
        }
    }
}
