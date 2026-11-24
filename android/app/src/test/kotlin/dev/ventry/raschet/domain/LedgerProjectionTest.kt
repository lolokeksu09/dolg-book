package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerProjectionTest {
    @Test fun sharedFixtures() {
        val text = checkNotNull(javaClass.getResourceAsStream("/projection.tsv")).bufferedReader().use { it.readText() }
        assertEquals(10, verifyProjectionContract(text))
    }

    @Test fun differentialAgainstLegacy() {
        assertEquals(500, verifyProjectionDifferential())
    }
}
