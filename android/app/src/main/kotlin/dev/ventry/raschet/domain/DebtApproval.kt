package dev.ventry.raschet.domain

/** Consent is valid only for the exact displayed balance, limit and additional debt. */
object DebtApproval {
    @JvmStatic fun key(client: String?, balance: Long, limit: Long, amount: Long): String =
        "$client:$balance:$limit:$amount"

    @JvmStatic fun matches(approval: String, client: String, balance: Long, limit: Long, amount: Long): Boolean =
        approval.isNotEmpty() && approval == key(client, balance, limit, amount)
}
