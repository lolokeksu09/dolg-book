package dev.ventry.raschet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PromiseHistoryContent(rows: List<PromiseHistory.Row>, today: LocalDate, onBack: () -> Unit) {
    var days by remember { mutableStateOf<Int?>(30) }
    var query by remember { mutableStateOf("") }
    val summary by produceState<PromiseHistory.Summary?>(null, rows, days, query) {
        value = null
        value = withContext(Dispatchers.Default) { PromiseHistory.select(rows, today, days, query) }
    }
    val date = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale("ru")) }
    LazyColumn(Modifier.fillMaxSize().background(ledgerBg).testTag("promise_history"),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") {
            TextButton(onClick = onBack, modifier = Modifier.testTag("promise_history_back")) { Text("← Возврат долгов") }
            Text("История обещаний", color = ledgerText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Период по согласованному сроку возврата", color = ledgerMuted, fontSize = 13.sp)
        }
        item("period") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(listOf(7 to "7 дней", 30 to "30 дней"), listOf(90 to "90 дней", null to "Все сроки")).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { (value, label) ->
                            val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("promise_period_" + (value?.toString() ?: "all"))
                                .semantics { selected = days == value }
                            if (days == value) Button(onClick = { days = value }, modifier = modifier) { Text(label) }
                            else OutlinedButton(onClick = { days = value }, modifier = modifier) { Text(label) }
                        }
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("promise_history_search"),
                    singleLine = true, label = { Text("Имя клиента") })
            }
        }
        val current = summary
        if (current == null) item("loading") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            item("summary") {
                Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerTotal("Получено по обещаниям", current.received, "Обещано " + ledgerMoney(current.promised))
                    Text("Обещаний: " + current.rows.size, color = ledgerMuted, modifier = Modifier.testTag("promise_history_count"))
                    for (status in PromiseHistory.Status.entries) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(status.caption, color = if (status == PromiseHistory.Status.MISSED) ledgerRed else ledgerMuted,
                                modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text(current.count(status).toString(), color = ledgerText, fontWeight = FontWeight.Medium)
                        }
                    }
                    Text("Суммы обещаний не равны общему долгу. Платёж учитывается только в своём интервале. Отменённые операции уточняют историю.", color = ledgerMuted, fontSize = 12.sp)
                }
            }
            if (current.rows.isEmpty()) item("empty") { Text("Нет обещаний за выбранный период.", color = ledgerMuted) }
            items(current.rows, key = { it.record.id }) { row ->
                val record = row.record
                Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.name, color = ledgerText, fontWeight = FontWeight.SemiBold)
                    Text(row.status.caption, color = when (row.status) {
                        PromiseHistory.Status.TIMELY -> ledgerAccent
                        PromiseHistory.Status.MISSED -> ledgerRed
                        else -> ledgerMuted
                    }, fontSize = 13.sp)
                    Text("Получено " + ledgerMoney(row.received) + " из " + ledgerMoney(record.amount), color = ledgerText)
                    Text("Срок: " + date.format(record.due) + if (record.originalDue != record.due)
                        " · первоначально " + date.format(record.originalDue) else "", color = ledgerMuted, fontSize = 12.sp)
                    Text(if (record.opened == null) "Перенесено из старого плана" else "Создано: " + date.format(record.opened),
                        color = ledgerMuted, fontSize = 12.sp)
                    if (record.closed != null) Text("Закрыто: " + date.format(record.closed) + " · " +
                        when(record.reason) { "replaced" -> "заменено"; "removed" -> "удалено"; else -> "завершено" },
                        color = ledgerMuted, fontSize = 12.sp)
                    if (record.note.isNotBlank()) Text(record.note, color = ledgerMuted, fontSize = 13.sp)
                }
            }
        }
    }
}
