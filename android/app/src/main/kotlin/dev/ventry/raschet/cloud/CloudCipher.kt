package dev.ventry.raschet.cloud

import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.PBEParametersGenerator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable VRB1. Exact UTF-8 passphrase, no trimming or Unicode normalization. */
internal object CloudCipher {
    const val MAX=16*1024*1024
    const val OVERHEAD=48
    const val MAX_PLAIN=MAX-OVERHEAD
    private val magic=byteArrayOf(86,82,66,49)
    fun validPassword(p:CharArray):Boolean {
        if(p.size !in 12..128 || p.all{it.isWhitespace()})return false
        var i=0
        while(i<p.size){
            if(Character.isHighSurrogate(p[i])){
                if(i+1>=p.size || !Character.isLowSurrogate(p[i+1]))return false
                i+=2
            }else{
                if(Character.isLowSurrogate(p[i]))return false
                i++
            }
        }
        return true
    }
    private fun cipher(mode:Int,password:CharArray,header:ByteArray):Cipher {
        require(validPassword(password)){"Пароль должен содержать от 12 до 128 символов."}
        val secret=PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password)
        val generator=PKCS5S2ParametersGenerator(SHA256Digest())
        val key=try{
            generator.init(secret,header.copyOfRange(4,20),600000)
            (generator.generateDerivedParameters(256) as KeyParameter).key
        }finally{secret.fill(0)}
        try{
            return Cipher.getInstance("AES/GCM/NoPadding").apply{
                init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,header.copyOfRange(20,32)))
                updateAAD(header)
            }
        }finally{key.fill(0)}
    }
    fun encrypt(plain:ByteArray,password:CharArray):ByteArray {
        val random=SecureRandom()
        return seal(plain,password,ByteArray(16).also(random::nextBytes),ByteArray(12).also(random::nextBytes))
    }
    internal fun seal(plain:ByteArray,password:CharArray,salt:ByteArray,nonce:ByteArray):ByteArray{
        require(plain.size in 1..MAX_PLAIN && salt.size==16 && nonce.size==12)
        val header=magic+salt+nonce
        return header+cipher(Cipher.ENCRYPT_MODE,password,header).doFinal(plain)
    }
    fun decrypt(envelope:ByteArray,password:CharArray):ByteArray {
        require(envelope.size in 49..MAX && envelope.copyOfRange(0,4).contentEquals(magic)){"Неподдерживаемая облачная копия."}
        val header=envelope.copyOfRange(0,32)
        // No plaintext reaches the importer until the complete GCM tag has verified.
        return cipher(Cipher.DECRYPT_MODE,password,header).doFinal(envelope,32,envelope.size-32)
    }
}
