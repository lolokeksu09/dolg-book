package dev.ventry.raschet.license

import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal const val PRODUCT="raschet"
internal const val PACKAGE="dev.ventry.raschet"
internal const val MAX_LEASE=72L*3600
internal fun ByteArray.hex()=joinToString(""){ "%02x".format(it.toInt() and 255) }
internal fun String.unhex(max:Int):ByteArray {
    require(length in 2..max*2 && length%2==0 && all{it in '0'..'9' || it in 'a'..'f'})
    return ByteArray(length/2){i->substring(i*2,i*2+2).toInt(16).toByte()}
}
internal fun String.token()=length==64 && all{it in '0'..'9' || it in 'a'..'f'}
internal fun String.uuid():String=UUID.fromString(this).toString().also{require(it==this && it!="00000000-0000-0000-0000-000000000000")}
internal data class Claims(val subscription:String,val installation:String,val revision:Long,val issued:Long,
    val validUntil:Long?,val leaseUntil:Long,val allowed:Boolean)
internal data class Anchor(val boot:Int,val elapsedMs:Long,val wallSec:Long)
internal object LicensePolicy {
    fun permits(c:Claims,a:Anchor,now:Anchor):Boolean {
        if(!c.allowed || c.validUntil==null || a.boot<0 || now.boot!=a.boot || now.elapsedMs<a.elapsedMs ||
            now.wallSec<a.wallSec-120) return false
        // Monotonic time prevents a wall-clock rollback from extending the lease during this boot.
        val elapsed=(now.elapsedMs-a.elapsedMs)/1000
        val wallAdvance=(now.wallSec-a.wallSec).coerceAtLeast(0)
        val effective=maxOf(now.wallSec,c.issued+maxOf(elapsed,wallAdvance))
        return effective<c.leaseUntil && effective<c.validUntil
    }
}
internal class EntitlementVerifier(private val publicKey:ByteArray){
    init { require(publicKey.size==32) }
    private val keyId=MessageDigest.getInstance("SHA-256").digest(publicKey).hex()
    fun verify(raw:String,installation:String):Claims {
        require(raw.length<=16_384)
        val env=JSONObject(raw)
        require(env.keys().asSequence().toSet()==setOf("key_id","payload_hex","signature_hex"))
        require(env.get("key_id")==keyId)
        val payload=(env.get("payload_hex") as String).unhex(4096)
        val sig=(env.get("signature_hex") as String).unhex(64)
        require(sig.size==64 && Ed25519.verify(sig,0,publicKey,0,payload,0,payload.size))
        val j=JSONObject(payload.toString(Charsets.UTF_8))
        require(j.keys().asSequence().toSet()==setOf("v","issuer","product","package_name","subscription_id","installation_id","revision","issued_at","valid_until","lease_until","may_create_debt"))
        fun number(key:String):Long {
            val value=j.get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
        require(number("v")==1L && j.get("issuer")=="ventry-delivery" && j.get("product")==PRODUCT && j.get("package_name")==PACKAGE)
        val sub=(j.get("subscription_id") as String).uuid()
        val device=(j.get("installation_id") as String).uuid()
        require(device==installation)
        val revision=number("revision")
        val issued=number("issued_at")
        val valid=if(j.isNull("valid_until"))null else number("valid_until")
        val lease=number("lease_until")
        val allowed=j.get("may_create_debt") as Boolean
        require(revision>=0 && issued in 1..253402300799L && (valid==null || valid in 1..253402300799L))
        require(lease in issued..(issued+MAX_LEASE))
        require(if(allowed) valid!=null && valid>=lease else lease==issued)
        return Claims(sub,device,revision,issued,valid,lease,allowed)
    }
}
