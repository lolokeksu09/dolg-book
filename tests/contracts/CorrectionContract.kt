import dev.ventry.raschet.domain.LedgerArithmetic
import java.io.File

fun main(args: Array<String>) {
    var checked = 0
    File(args.single()).forEachLine { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachLine
        val (available, type, old, replacement, expected) = line.split(' ')
        val actual = try {
            LedgerArithmetic.correctedBalance(available.toLong(), type, old.toLong(), replacement.toLong()).toString()
        } catch (ex: IllegalArgumentException) {
            when (ex.message) {
                "Неверная операция." -> "invalid"
                "Этот долг уже погашался. Сначала исправьте последнюю оплату." -> "paid"
                "Исправление создаёт переплату или превышение." -> "range"
                else -> throw ex
            }
        }
        check(expected == actual) { "$line -> $actual" }
        checked++
    }
    check(checked >= 18)
    println("Correction contract: $checked PASS")
}
