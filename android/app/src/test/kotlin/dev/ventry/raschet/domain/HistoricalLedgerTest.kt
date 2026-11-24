package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalLedgerTest {
    @Test fun historicalPaymentsAndLocalDates() {
        val input = checkNotNull(javaClass.getResourceAsStream("/dated.tsv")).bufferedReader().use { it.readText() }
        assertEquals(14, verifyDatedContract(input))
    }

    @Test fun collectionProjections() {
        assertEquals(5, verifyCollections())
    }
}
