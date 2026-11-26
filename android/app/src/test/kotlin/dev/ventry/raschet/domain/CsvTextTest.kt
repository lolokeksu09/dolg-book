package dev.ventry.raschet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTextTest {
    @Test fun neutralizesWhitespaceAndFormulaPrefixes(){
        for (value in listOf("=1+1", " +1", "  -2", "@SUM(1;2)", "\ttext", "\rtext", "\ntext"))
            assertEquals("\"'$value\"", CsvText.cell(value))
    }
    @Test fun preservesPlainTextAndQuotes(){
        assertEquals("\"Яблоки; груши\"", CsvText.cell("Яблоки; груши"))
        assertEquals("\"a\"\"b\"", CsvText.cell("a\"b"))
        assertEquals("\"\"", CsvText.cell(""))
    }
}
