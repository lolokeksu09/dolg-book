package dev.ventry.raschet

import java.util.Locale

/** Shared text/phone lookup for the book, customer directory and operation history. */
object ClientSearch {
    private val spaces = Regex("\\s+")
    private val nonDigits = Regex("[^0-9]")
    private val phoneQuery = Regex("[0-9+() .\\-]+")
    @JvmStatic
    fun normalize(value: String?): String =
        value.orEmpty().trim { it <= ' ' }.lowercase(Locale.ROOT).replace('ё', 'е').replace(spaces, " ")

    @JvmStatic
    fun phoneMatches(stored: String?, query: String?): Boolean {
        if (query == null || !phoneQuery.matches(query)) return false
        val wanted = digits(query)
        if (wanted.isEmpty()) return false
        val actual = digits(stored)
        return actual.contains(wanted) || sameDigits(actual, wanted)
    }

    @JvmStatic
    fun sameNumber(first: String?, second: String?): Boolean = sameDigits(digits(first), digits(second))

    private fun digits(value: String?) = value.orEmpty().replace(nonDigits, "")
    private fun sameDigits(actual: String, wanted: String): Boolean =
        wanted.length >= 7 && (actual == wanted || russian(wanted) && russian(actual) && wanted.substring(1) == actual.substring(1))

    private fun russian(digits: String): Boolean = digits.length == 11 && (digits[0] == '7' || digits[0] == '8')
}
