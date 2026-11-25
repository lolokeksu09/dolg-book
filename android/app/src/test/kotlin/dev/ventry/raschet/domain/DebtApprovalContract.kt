package dev.ventry.raschet.domain

fun verifyDebtApproval(): Int {
    val approval = DebtApproval.key("a", 8000, 10000, 3000)
    val cases = listOf(
        DebtApproval.matches(approval, "a", 8000, 10000, 3000) to true,
        DebtApproval.matches("", "a", 8000, 10000, 3000) to false,
        DebtApproval.matches(approval, "b", 8000, 10000, 3000) to false,
        DebtApproval.matches(approval, "a", 8001, 10000, 3000) to false,
        DebtApproval.matches(approval, "a", 8000, 10001, 3000) to false,
        DebtApproval.matches(approval, "a", 8000, 10000, 3001) to false
    )
    check(cases.all { (actual, expected) -> actual == expected })
    return cases.size
}

fun main() { println("Debt approval contract: ${verifyDebtApproval()}/6 PASS") }
