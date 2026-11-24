package dev.ventry.raschet.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalLedgerTest {
    @Test fun historicalPaymentsAndLocalDates() {
        val input = checkNotNull(javaClass.getResourceAsStream("/dated.tsv")).bufferedReader().use { it.readText() }
        assertEquals(14, verifyDatedContract(input))
    }

    @Test fun rustFixturesMatchPlatformLocalDates() {
        fun load(name: String) = checkNotNull(javaClass.getResourceAsStream(name))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        var zone = ZoneId.of("UTC")
        val normalized = load("/dated.tsv").map { line ->
            val f = line.split('|')
            if (f[0] == "case") {
                zone = ZoneId.of(f[5])
                val day = f[3].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).toEpochDay().toString() }.orEmpty()
                listOf(f[0], f[1], f[2], day, LocalDate.parse(f[4]).toEpochDay().toString(), f[6]).joinToString("|")
            } else {
                (f.take(5) + DatedLedger.localDate(f[5], zone).toEpochDay().toString() + f[6]).joinToString("|")
            }
        }
        assertEquals(load("/dated-epoch.tsv"), normalized)
    }

    @Test fun collectionProjections() {
        assertEquals(5, verifyCollections())
    }
}
