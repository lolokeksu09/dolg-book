package dev.ventry.raschet.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudCipherTest{
    private val password="Пароль для копии 🔐".toCharArray()
    private val plain="""{"version":5}""".toByteArray()
    @Test fun independentPythonVector(){
        val result=CloudCipher.seal(plain,password,ByteArray(16){it.toByte()},ByteArray(12){(it+16).toByte()})
        assertEquals("56524231000102030405060708090a0b0c0d0e0f101112131415161718191a1b29bf72c5f8dc25f9b6b9e0daac302026bcb51670d7513c984e2c571969",
            result.joinToString(""){"%02x".format(it.toInt() and 255)})
        assertArrayEquals(plain,CloudCipher.decrypt(result,password))
    }
    @Test fun randomNonceAndWrongPassword(){
        val a=CloudCipher.encrypt(plain,password)
        val b=CloudCipher.encrypt(plain,password)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain,CloudCipher.decrypt(a,password))
        assertThrows(Exception::class.java){CloudCipher.decrypt(a,"Другой пароль для копии".toCharArray())}
        a[a.lastIndex]=(a.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java){CloudCipher.decrypt(a,password)}
    }
    @Test fun headerAndLengthChecks(){
        assertFalse(CloudCipher.validPassword("short".toCharArray()))
        assertFalse(CloudCipher.validPassword(CharArray(12){' '}))
        assertThrows(IllegalArgumentException::class.java){CloudCipher.encrypt(byteArrayOf(),password)}
        assertThrows(IllegalArgumentException::class.java){CloudCipher.decrypt(ByteArray(48),password)}
        val a=CloudCipher.encrypt(plain,password)
        a[4]=(a[4].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){CloudCipher.decrypt(a,password)}
    }
}
