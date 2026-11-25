package dev.ventry.raschet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.ZoneId
import java.util.function.IntConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ReportActions(val period: () -> Unit, val history: () -> Unit,
    val export: () -> Unit, val limit: (Int) -> Unit)

object ReportCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, data: BookScreenData, from: LocalDate, to: LocalDate,
        initialLimit: Int, onPeriod: Runnable, onHistory: Runnable,
        onExport: Runnable, onLimit: IntConsumer): ComposeView {
        val actions = ReportActions(onPeriod::run, onHistory::run, onExport::run, onLimit::accept)
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    var report by remember(data, from, to) { mutableStateOf<PeriodReport?>(null) }
                    var failed by remember(data, from, to) { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    LaunchedEffect(data, from, to, attempt) {
                        try {
                            val result = withContext(Dispatchers.Default) {
                                PeriodReport.build(data.snapshot.entries, from, to, ZoneId.systemDefault())
                            }
                            coroutineContext.ensureActive()
                            report = result
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
                    }
                    val current = report
                    when {
                        current != null -> ReportContent(current, initialLimit, actions)
                        failed -> Column(Modifier.fillMaxSize().background(ledgerBg).padding(16.dp)) {
                            Text("Не удалось построить отчёт.", color = ledgerText)
                            OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                            OutlinedButton(onClick = actions.period) { Text("Изменить период") }
                        }
                        else -> Box(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportValue(title: String, amount: Long, color: Color = ledgerText) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ledgerMuted, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(ledgerMoney(amount), color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ReportContent(report: PeriodReport, initialLimit: Int, actions: ReportActions) {
    var limit by remember { mutableIntStateOf(initialLimit) }
    Box(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("report-list"),
            contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("title") {
                Text("Финансовый отчёт", color = ledgerText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Движение задолженности, не выручка и не прибыль", color = ledgerMuted, fontSize = 13.sp)
            }
            item("period") {
                LedgerMetric("Период отчёта", ledgerLongDate.format(report.from) + " — " + ledgerLongDate.format(report.to),
                    action = actions.period)
            }
            item("summary") {
                LedgerTotal("Остаток на конец", report.closing)
                ReportValue("Остаток на начало", report.opening)
                ReportValue("Изменение задолженности", report.totals.net())
                Text("Операций: " + report.totals.count + " · клиентов: " + report.activeCustomers, color = ledgerMuted, fontSize = 13.sp)
            }
            item("actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = actions.history, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Операции периода") }
                    OutlinedButton(onClick = actions.export, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Экспорт отчёта CSV") }
                }
            }
            item("flow") {
                Text("Движение за период", color = ledgerText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                ReportValue("Записано долгов", report.totals.debts)
                ReportValue("Получено оплат", report.totals.payments, ledgerAccent)
                ReportValue("Отменено долгов", report.totals.debtReversals, ledgerAlert)
                ReportValue("Отменено оплат", report.totals.paymentReversals, ledgerAlert)
                Text("Отмены учитываются по дате отмены. Суммы долгов и оплат показаны до вычета отмен.",
                    color = ledgerMuted, fontSize = 13.sp)
            }
            item("daily-title") {
                Text("По дням", color = ledgerText, fontWeight = FontWeight.Bold)
                Text("Дни, в которые были операции", color = ledgerMuted, fontSize = 12.sp)
            }
            if (report.daily.isEmpty()) item("empty") {
                Text("За выбранный период операций нет.", color = ledgerMuted)
            }
            items(report.daily.entries.take(limit), key = { "day-" + it.key }) { day ->
                val totals = day.value
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ledgerLongDate.format(day.key), color = ledgerText, fontWeight = FontWeight.SemiBold)
                    Text("Долги " + ledgerMoney(totals.debts) + " · оплаты " + ledgerMoney(totals.payments),
                        color = ledgerMuted, fontSize = 13.sp)
                    Text("Отмены долгов " + ledgerMoney(totals.debtReversals) + " · отмены оплат " + ledgerMoney(totals.paymentReversals),
                        color = ledgerMuted, fontSize = 13.sp)
                    Text("Изменение: " + ledgerMoney(totals.net()), color = ledgerAccent, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = ledgerBorder)
                }
            }
            if (report.daily.size > limit) item("more") {
                OutlinedButton(onClick = { limit += 14; actions.limit(limit) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать ещё дни")
                }
            }
        }
    }
}
