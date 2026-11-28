package dev.ventry.raschet.cloud

import org.junit.Assert.*
import org.junit.Test

class AutoBackupPolicyTest{
    @Test fun onlyAcknowledgedVersionCanBeReplaced(){
        assertEquals(3,AutoBackupPolicy.SLOT)
        assertEquals(AutoBackupPolicy.Action.UPLOAD,AutoBackupPolicy.decide("a","a","v1","v1","h1","h2"))
        assertEquals(AutoBackupPolicy.Action.STOP,AutoBackupPolicy.decide("a","a","v1","v2","h1","h2"))
        assertEquals(AutoBackupPolicy.Action.STOP,AutoBackupPolicy.decide("a","a","v1",null,"h1","h2"))
    }
    @Test fun differentBindingNeverUploadsEvenWithMatchingVersion(){
        assertEquals(AutoBackupPolicy.Action.STOP,AutoBackupPolicy.decide("a","b","v1","v1","h1","h2"))
    }
    @Test fun unchangedBookDoesNotReplaceCopy(){
        assertEquals(AutoBackupPolicy.Action.SKIP,AutoBackupPolicy.decide("a","a","v1","v1","h1","h1"))
    }
}
