package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportLedgerValidationTest {
    @Test fun validatedHistoryContract() {
        assertEquals(18, verifyImportContract())
    }
}
