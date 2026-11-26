package dev.ventry.raschet.domain

/** Release and native-enabled debug fail closed on disagreement. No fallback or partial write. */
object CorrectionParity {
    private const val INVALID = -1L
    private const val PAID = -2L
    private const val RANGE = -3L

    fun verify(
        available: Long,
        type: String?,
        oldAmount: Long,
        replacement: Long,
        native: (Int, Long, Long, Long) -> Long
    ): Long {
        val kind = when (type) {
            "debt" -> 1
            "payment" -> 2
            else -> 0
        }
        val expected = try {
            LedgerArithmetic.correctedBalance(available, type, oldAmount, replacement)
        } catch (failure: IllegalArgumentException) {
            val code = when (failure.message) {
                "Неверная операция." -> INVALID
                "Этот долг уже погашался. Сначала исправьте последнюю оплату." -> PAID
                "Исправление создаёт переплату или превышение." -> RANGE
                else -> throw failure
            }
            check(native(kind, available, oldAmount, replacement) == code) { "Несовпадение финансовых расчётов Kotlin и Rust." }
            throw failure
        }
        val actual = native(kind, available, oldAmount, replacement)
        check(actual == expected) { "Несовпадение финансовых расчётов Kotlin и Rust." }
        return actual
    }
}
