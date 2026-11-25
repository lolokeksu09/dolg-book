package dev.ventry.raschet.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Shared export model: the same projections feed both formats, never raw personal records. */
object AnalyticsExport {
    data class Spec(
        val from: LocalDate, val to: LocalDate, val previousFrom: LocalDate?,
        val previousTo: LocalDate?, val customerId: String?, val pdf: Boolean
    ) {
        init {
            require(!from.isAfter(to))
            require((previousFrom == null) == (previousTo == null))
            if (previousFrom != null && previousTo != null) {
                require(!previousFrom.isAfter(previousTo) && previousTo.isBefore(from))
            }
        }
    }

    data class Line(val label: String, val value: String, val numeric: Boolean = false)

    fun lines(
        period: AnalyticsProjection.Snapshot, previous: AnalyticsProjection.Snapshot?,
        customerId: String?, customerName: String?, series: List<AnalyticsSeries.Bucket>
    ): List<Line> {
        val client = customerId?.let { id ->
            requireNotNull(period.customers.firstOrNull { it.id == id }) { "Клиент не найден." }
        }
        require(customerId == null || customerName != null) { "Клиент не найден." }
        val current = client?.current ?: period.current
        val movement = client?.movement ?: period.movement
        val effective = client?.effective ?: period.effective
        val opening = client?.opening ?: period.opening
        val closing = client?.closing ?: period.closing
        return buildList {
            fun text(label: String, value: String) { add(Line(label, value)) }
            fun count(label: String, value: Int) { add(Line(label, value.toString(), true)) }
            fun money(label: String, value: Long) { add(Line(label + " (руб.)", rubles(value), true)) }
            text("Отчёт", if (client == null) "Общая статистика" else "Статистика клиента")
            if (client != null) text("Клиент", requireNotNull(customerName))
            text("Дата формирования", period.today.toString())
            text("Начало периода", period.range.from.toString())
            text("Конец периода", period.range.to.toString())
            text("Раздел", "На дату формирования")
            money("Текущий остаток", current.balance)
            money("Текущая просрочка", current.overdue)
            money("К оплате в ближайшие 7 дней", current.upcoming)
            if (client == null) {
                count("Должников сегодня", period.currentDebtors)
                count("С просрочкой сегодня", period.overdueDebtors)
            }
            text("Раздел", "Движение по датам регистрации")
            money("Остаток на начало", opening)
            money("Записано долгов", movement.debts)
            count("Записей долга до вычета отмен", movement.debtCount)
            money("Получено оплат", movement.payments)
            count("Записей оплаты до вычета отмен", movement.paymentCount)
            money("Отменено долгов", movement.debtReversals)
            count("Отмен долгов", movement.debtReversalCount)
            money("Отменено оплат", movement.paymentReversals)
            count("Отмен оплат", movement.paymentReversalCount)
            money("Изменение остатка", movement.net)
            money("Остаток на конец", closing)
            if (client == null) {
                count("Должников на начало", period.openingDebtors)
                count("Должников на конец", period.closingDebtors)
                count("Клиентов с операциями", period.activeCustomers)
                count("Брали в долг", period.borrowingCustomers)
                count("Впервые брали в долг", period.firstTimeBorrowers)
                count("Погасили остаток", period.settledCustomers)
            }
            text("Раздел", "Действительные записи на конец периода")
            money("Действительные долги", effective.debts)
            count("Число действительных долгов", effective.debtCount)
            money("Действительные оплаты", effective.payments)
            count("Число действительных оплат", effective.paymentCount)
            if (effective.debtCount > 0) money("Средний долг", BigDecimal.valueOf(effective.debts)
                .divide(BigDecimal.valueOf(effective.debtCount.toLong()), 0, RoundingMode.HALF_UP).longValueExact())
            else text("Средний долг", "Нет данных")
            effective.maximumDebt?.let { money("Максимальный долг", it) } ?: text("Максимальный долг", "Нет данных")
            if (client != null) {
                text("Первый действительный долг к концу периода", client.firstDebtAsOfEnd?.toString() ?: "Нет данных")
                text("Последний действительный долг к концу периода", client.lastDebtAsOfEnd?.toString() ?: "Нет данных")
                text("Последняя действительная оплата к концу периода", client.lastPaymentAsOfEnd?.toString() ?: "Нет данных")
            }
            if (previous != null) {
                val priorClient = customerId?.let { id -> requireNotNull(previous.customers.firstOrNull { it.id == id }) }
                val prior = priorClient?.effective ?: previous.effective
                text("Раздел", "Сравнение")
                text("Предыдущий период", previous.range.from.toString() + " — " + previous.range.to)
                money("Действительные долги ранее", prior.debts)
                money("Действительные оплаты ранее", prior.payments)
                money("Остаток на конец ранее", priorClient?.closing ?: previous.closing)
                fun change(label: String, now: Long, before: Long) {
                    val delta = AnalyticsPeriods.Change.between(now, before)
                    money("Изменение: " + label, delta.absolute)
                    text("Изменение (%): " + label, delta.percent?.toPlainString()?.replace('.', ',') ?: "Нет базы")
                }
                change("долги", effective.debts, prior.debts)
                change("оплаты", effective.payments, prior.payments)
                change("остаток", closing, priorClient?.closing ?: previous.closing)
            }
            text("Методика", "Отмены отражаются по дате регистрации; действительные записи не включают отменённые к концу периода. Статистика не является выручкой или прибылью.")
            text("Раздел", "Динамика по интервалам (руб.)")
            for (bucket in series) {
                text(bucket.from.toString() + " — " + bucket.to,
                    "Начало " + rubles(bucket.opening) + "; долги " + rubles(bucket.movement.debts) +
                        "; оплаты " + rubles(bucket.movement.payments) + "; отмены долгов " + rubles(bucket.movement.debtReversals) +
                        "; отмены оплат " + rubles(bucket.movement.paymentReversals) + "; конец " + rubles(bucket.closing))
            }
        }
    }

    private fun rubles(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

    /** Neutralize untrusted text cells, including formulas preceded by whitespace. */
    private fun cell(value: String): String {
        val first = value.trimStart().firstOrNull()
        val safe = if (first != null && first in "=+-@" || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun csv(lines: List<Line>): String = buildString {
        append("\uFEFFПоказатель;Значение\r\n")
        for (line in lines) {
            append(cell(line.label)).append(';')
            // All numeric values are constructed from integer financial data, not user strings.
            if (line.numeric) {
                require(line.value.matches(Regex("-?[0-9]+(,[0-9]+)?")))
                append(line.value)
            } else append(cell(line.value))
            append("\r\n")
        }
    }
}
