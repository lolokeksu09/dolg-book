package dev.ventry.raschet

/** Keep Java trim semantics and one contact validation for edits and streamed restore. */
internal object LedgerInput {
    @JvmStatic fun trim(value: String): String = value.trim { it <= ' ' }

    @JvmStatic fun checkClient(name: String?, phone: String?) {
        require(name != null && trim(name).isNotEmpty() && trim(name).length <= 80 && phone != null && trim(phone).length <= 30) {
            "Имя: до 80 знаков. Телефон: до 30."
        }
    }
    private val moneyPattern = Regex("\\d{1,10}([.,]\\d{1,2})?")

    @JvmStatic fun parseAmount(value: String?): Long {
        require(value != null && moneyPattern.matches(trim(value).replace(" ", ""))) {
            "Введите корректную сумму с точностью до копеек."
        }
        try {
            val result = java.math.BigDecimal(value.replace(" ", "").replace(',', '.')).movePointRight(2).longValueExact()
            if (result <= 0 || result > LedgerStore.MAX_AMOUNT) throw ArithmeticException("out of range")
            return result
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Сумма должна быть от 0,01 до 1 000 000 000 ₽.")
        }
    }
}
