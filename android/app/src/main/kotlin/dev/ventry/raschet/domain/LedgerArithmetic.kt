package dev.ventry.raschet.domain

/** Pure arithmetic. SQLite remains responsible for transactional validation and writes. */
object LedgerArithmetic {
    const val MAX_AMOUNT: Long = 100_000_000_000L

    @JvmStatic
    fun correctedBalance(available: Long, type: String?, oldAmount: Long, replacement: Long): Long {
        require(type == "debt" || type == "payment") { "Неверная операция." }
        require(available in 0..MAX_AMOUNT && oldAmount in 1..MAX_AMOUNT && replacement in 0..MAX_AMOUNT) {
            "Неверная операция."
        }
        // Bounded inputs guarantee all intermediate sums fit signed 64-bit integers.
        val withoutOld = if (type == "debt") available - oldAmount else available + oldAmount
        require(withoutOld >= 0) { "Этот долг уже погашался. Сначала исправьте последнюю оплату." }
        val updated = if (type == "debt") withoutOld + replacement else withoutOld - replacement
        require(updated in 0..MAX_AMOUNT) { "Исправление создаёт переплату или превышение." }
        return updated
    }
}
