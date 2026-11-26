package dev.ventry.raschet;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;
import static dev.ventry.raschet.ActivityRequests.*;

public class ActivityRequestsTest {
    @Test public void fileCredentialAndPermissionRequestsNeverOverlap(){
        int[] files={REQUEST_IMPORT,REQUEST_EXPORT,REQUEST_PDF,REQUEST_REPORT,REQUEST_ANALYTICS};
        int[] credentials={CREDENTIAL_OPEN,CREDENTIAL_ENABLE,CREDENTIAL_DISABLE};
        Set<Integer> ids=new HashSet<>();
        for(int file:files){
            assertTrue(ids.add(file));
            assertTrue(isFile(file));
            assertFalse(isCredential(file));
        }
        for(int credential:credentials){
            assertTrue(ids.add(credential));
            assertTrue(isCredential(credential));
            assertFalse(isFile(credential));
        }
        assertTrue(ids.add(NOTIFICATION_PERMISSION_REQUEST));
        assertFalse(isFile(NOTIFICATION_PERMISSION_REQUEST));
        assertFalse(isCredential(NOTIFICATION_PERMISSION_REQUEST));
        assertFalse(isFile(-1));
        assertFalse(isCredential(-1));
    }
}
