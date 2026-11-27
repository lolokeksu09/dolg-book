package dev.ventry.raschet.license
import org.junit.Assert.*
import org.junit.Test

class LicensePolicyTest {
    private val anchor=Anchor(8,1000000,10000)
    private val claims=Claims("sub","device",1,10000,10000+7*86400,10000+MAX_LEASE,true)
    @Test fun allowsOnlyBoundedSameBootLease(){
        assertTrue(LicensePolicy.permits(claims,anchor,anchor.copy(elapsedMs=anchor.elapsedMs+1000,wallSec=10001)))
        assertFalse(LicensePolicy.permits(claims,anchor,anchor.copy(elapsedMs=anchor.elapsedMs+MAX_LEASE*1000)))
        assertFalse(LicensePolicy.permits(claims,anchor,anchor.copy(wallSec=claims.leaseUntil)))
        assertFalse(LicensePolicy.permits(claims,anchor,anchor.copy(boot=9)))
        assertFalse(LicensePolicy.permits(claims,anchor,anchor.copy(wallSec=9800)))
        assertFalse(LicensePolicy.permits(claims.copy(allowed=false),anchor,anchor))
        assertFalse(LicensePolicy.permits(claims.copy(validUntil=10000),anchor,anchor))
        assertFalse(LicensePolicy.permits(claims,anchor,anchor.copy(elapsedMs=anchor.elapsedMs-1)))
        assertFalse(LicensePolicy.permits(claims,anchor.copy(boot=-1),anchor.copy(boot=-1)))
    }
    @Test fun rawHexContract(){
        assertEquals("00ff",byteArrayOf(0,-1).hex())
        assertArrayEquals(byteArrayOf(0,-1),"00ff".unhex(2))
        for (invalid in listOf("FF","0","00zz","000000")) {
            try{invalid.unhex(2);fail("Malformed hex accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
