package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DebtApprovalTest {
    @Test fun approvalBindsToExactInputs() {
        assertEquals(6, verifyDebtApproval())
    }
}
