package dev.ventry.raschet

/** Advisory credit limit for new debt. The ledger's accounting rules remain authoritative. */
object DebtLimits {
    @JvmStatic fun remaining(limit: Long, balance: Long): Long = maxOf(0L, limit - balance)
    @JvmStatic fun requiresConfirmation(limit: Long, balance: Long, addition: Long): Boolean =
        limit > 0 && addition > 0 && addition > remaining(limit, balance)
    @JvmStatic fun requireConsent(limit: Long, balance: Long, addition: Long, confirmed: Boolean) {
        require(!requiresConfirmation(limit, balance, addition) || confirmed) {
            "Превышен лимит задолженности. Подтвердите превышение или уменьшите сумму."
        }
    }
}
