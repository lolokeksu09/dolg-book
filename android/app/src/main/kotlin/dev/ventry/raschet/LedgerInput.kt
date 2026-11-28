package dev.ventry.raschet

/** Keep Java trim semantics and one contact validation for edits and streamed restore. */
internal object LedgerInput {
    @JvmStatic fun trim(value: String): String = value.trim { it <= ' ' }

    @JvmStatic fun checkClient(name: String?, phone: String?) {
        require(name != null && trim(name).isNotEmpty() && trim(name).length <= 80 && phone != null && trim(phone).length <= 30) {
            "Имя: до 80 знаков. Телефон: до 30."
        }
    }
}
