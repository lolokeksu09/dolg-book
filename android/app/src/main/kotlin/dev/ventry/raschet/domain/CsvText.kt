package dev.ventry.raschet.domain

/** One escaping policy for untrusted text in every CSV export. Numeric columns
 * that are generated internally may use separately validated unquoted values. */
object CsvText {
    fun cell(value: String): String {
        val first = value.trimStart().firstOrNull()
        val safe = if (first != null && first in "=+-@" || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
