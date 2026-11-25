package dev.ventry.raschet.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionParityTest {
    private data class Row(
        override val entryId: String, override val ownerId: String, override val operation: String,
        override val amountKopecks: Long, override val dueDate: String, override val reversedId: String
    ) : LedgerRecord

    @Test fun sameFixtureAsRust() {
        val rows = mutableListOf<Row>()
        val expected = mutableMapOf<String, CollectionProjection.Status>()
        var today: LocalDate? = null
        fun date(value: String) = value.takeIf { it.isNotEmpty() }?.let { LocalDate.ofEpochDay(it.toLong()) }
        val input = checkNotNull(javaClass.getResourceAsStream("/collections-epoch.tsv"))
            .bufferedReader().use { it.readLines() }
        for (line in input) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('|')
            when (f[0]) {
                "today" -> today = date(f[1])
                "row" -> rows += Row(f[1], f[2], f[3], f[4].toLong(), date(f[5])?.toString().orEmpty(), f[6])
                "expected" -> expected[f[1]] = CollectionProjection.Status(f[2].toLong(), f[3].toLong(), f[4].toLong(), date(f[5]), f[6].toInt())
                else -> error(line)
            }
        }
        assertEquals(3, expected.size)
        assertEquals(expected, CollectionProjection.analyze(rows, checkNotNull(today)))
    }
}
