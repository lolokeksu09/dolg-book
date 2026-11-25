package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportProjectionTest {
    @Test fun reportContract() {
        assertEquals(16, verifyReports())
    }
}
