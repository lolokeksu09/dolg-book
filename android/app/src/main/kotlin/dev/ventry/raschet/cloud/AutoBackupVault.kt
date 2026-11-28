package dev.ventry.raschet.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only wrapping of the recovery passphrase; never exported or sent to Ventry. */
internal class AutoBackupVault(context:Context){
    private val file=AtomicFile(File(context.noBackupFilesDir,"cloud-auto-v1.bin"))
    private val alias="raschet.cloud.auto.v1"
    private val aad=alias.toByteArray(Charsets.UTF_8)
    class Config(val binding:String,val version:String,val hash:String,val password:CharArray){
        override fun toString()="AutoBackupConfig(redacted)"
        fun clear(){password.fill('\u0000')}
    }
    private fun key(create:Boolean):SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        require(create){"Ключ автокопирования недоступен."}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun exists()=file.baseFile.exists()
    fun read():Config?{
        if(!exists())return null
        require(file.baseFile.length() in 29..4096)
        val data=file.readFully()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,data.copyOfRange(0,12)))
        cipher.updateAAD(aad)
        val plain=cipher.doFinal(data,12,data.size-12)
        try{
            val j=JSONObject(plain.toString(Charsets.UTF_8))
            return Config(j.getString("binding"),j.getString("version"),j.getString("hash"),j.getString("password").toCharArray())
        }finally{plain.fill(0)}
    }
    fun write(config:Config){
        val plain=JSONObject().put("binding",config.binding).put("version",config.version)
            .put("hash",config.hash).put("password",String(config.password)).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        val data=try{
            cipher.init(Cipher.ENCRYPT_MODE,key(true));require(cipher.iv.size==12)
            cipher.updateAAD(aad);cipher.iv+cipher.doFinal(plain)
        }finally{plain.fill(0)}
        val out=file.startWrite()
        try{out.write(data);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun clear(){
        // Remove the key as well as ciphertext; do not touch the user's remote slot.
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        file.delete()
    }
}
