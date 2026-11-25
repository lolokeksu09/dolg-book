package dev.ventry.raschet;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class BackupSizeTest {
    @Test public void sharedLimitAcceptsPreviouslyRejectedRecoverySize() throws Exception {
        assertTrue(BackupSize.isSupported(5L*1024*1024+1));
        BackupSize.requireSupported(BackupSize.MAX_BYTES);
        assertFalse(BackupSize.isSupported(BackupSize.MAX_BYTES+1L));
        assertFalse(BackupSize.isSupported(-1));
        try { BackupSize.requireSupported(BackupSize.MAX_BYTES+1L); fail("Oversized recovery must fail"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("10")); }
    }
}
