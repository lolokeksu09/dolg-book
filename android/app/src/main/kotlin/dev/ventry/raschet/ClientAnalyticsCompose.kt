package dev.ventry.raschet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.presentation.AnalyticsResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.function.BiConsumer

@Composable
internal fun ClientAnalyticsContent(result: AnalyticsResult, onHistory: BiConsumer<LocalDate, LocalDate>) {
    val data = result.period
    val customer = result.customer
    val name = result.names[result.request.customerId]
    if (customer == null || name == null) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Клиент не найден", color = ledgerText, fontSize = 22.sp)
            Text("Книга могла измениться после восстановления. Вернитесь к списку клиентов.", color = ledgerMuted)
        }
        return
    }
    val effective = customer.effective
    val average = if (effective.debtCount == 0) null else BigDecimal.valueOf(effective.debts)
        .divide(BigDecimal.valueOf(effective.debtCount.toLong()), 0, RoundingMode.HALF_UP).longValueExact()
    LazyColumn(Modifier.fillMaxSize().testTag("client-analytics-list"),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("heading") {
            Text("Статистика клиента", color = ledgerMuted, fontSize = 13.sp)
            Text(name, color = ledgerText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(analyticsRange(data.range.from, data.range.to), color = ledgerMuted, fontSize = 13.sp)
        }
        item("current") {
            Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("Остаток долга на сегодня", color = ledgerMuted, fontSize = 13.sp)
                Text(analyticsMoney(customer.current.balance), color = ledgerText, fontSize = 36.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium)
                AnalyticsValue("Просрочено", analyticsMoney(customer.current.overdue), ledgerAlert)
                AnalyticsValue("К оплате в ближайшие 7 дней", analyticsMoney(customer.current.upcoming))
            }
        }
        item("effective") {
            Column {
                AnalyticsHeading("За выбранный период")
                AnalyticsValue("Всего брал в долг", analyticsMoney(effective.debts))
                AnalyticsValue("Записей долга", effective.debtCount.toString())
                AnalyticsValue("Всего оплатил", analyticsMoney(effective.payments), ledgerAccent)
                AnalyticsValue("Записей оплаты", effective.paymentCount.toString())
                HorizontalDivider(color = ledgerBorder)
                AnalyticsValue("Средняя запись долга", average?.let(::analyticsMoney) ?: "Нет данных")
                AnalyticsValue("Максимальная запись", effective.maximumDebt?.let(::analyticsMoney) ?: "Нет данных")
                Text("Суммы и количество без записей, отменённых к концу периода.", color = ledgerMuted, fontSize = 12.sp)
            }
        }
        item("movement") {
            Column {
                AnalyticsHeading("Движение задолженности")
                AnalyticsValue("Остаток на начало", analyticsMoney(customer.opening))
                AnalyticsValue("Записано долгов", analyticsMoney(customer.movement.debts))
                AnalyticsValue("Получено оплат", analyticsMoney(customer.movement.payments))
                AnalyticsValue("Отменено долгов", analyticsMoney(customer.movement.debtReversals))
                AnalyticsValue("Отменено оплат", analyticsMoney(customer.movement.paymentReversals))
                AnalyticsValue("Изменение остатка", analyticsMoney(customer.movement.net))
                AnalyticsValue("Остаток на конец", analyticsMoney(customer.closing))
                Text("Отмены учитываются по дате регистрации.", color = ledgerMuted, fontSize = 12.sp)
            }
        }
        item("dates") {
            Column {
                AnalyticsHeading("История к концу периода")
                AnalyticsValue("Первый действительный долг", customer.firstDebtAsOfEnd?.let(analyticsDate::format) ?: "Нет данных")
                AnalyticsValue("Последний действительный долг", customer.lastDebtAsOfEnd?.let(analyticsDate::format) ?: "Нет данных")
                AnalyticsValue("Последняя действительная оплата", customer.lastPaymentAsOfEnd?.let(analyticsDate::format) ?: "Нет данных")
                Text("Даты за всю историю до " + analyticsDate.format(data.range.to), color = ledgerMuted, fontSize = 12.sp)
            }
        }
        item("chart") {
            Column {
                AnalyticsHeading("Динамика долга клиента")
                AnalyticsChart(result.series)
            }
        }
        val previous = result.previousCustomer
        val previousPeriod = result.previous
        if (previous != null && previousPeriod != null) item("comparison") {
            Column {
                AnalyticsHeading("Сравнение периодов")
                Text(analyticsRange(previousPeriod.range.from, previousPeriod.range.to), color = ledgerMuted, fontSize = 12.sp)
                AnalyticsChange("Действительные долги", effective.debts, previous.effective.debts)
                AnalyticsChange("Действительные оплаты", effective.payments, previous.effective.payments)
                AnalyticsChange("Остаток на конец", customer.closing, previous.closing)
                AnalyticsValue("Записей долга ранее", previous.effective.debtCount.toString())
                AnalyticsValue("Записей оплаты ранее", previous.effective.paymentCount.toString())
            }
        }
        item("history") {
            OutlinedButton(onClick = { onHistory.accept(data.range.from, data.range.to) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Операции клиента за период") }
        }
    }
}
