package dev.ventry.raschet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ventry.raschet.presentation.BookScreenData
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal val ledgerLongDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru", "RU"))

internal fun projectHistory(data: BookScreenData, people: Map<String, LedgerStore.Client>,
    clientId: String?, query: String, from: LocalDate?, to: LocalDate?, filter: String,
    zone: ZoneId): List<DetailEntry> {
    val entries = data.snapshot.entries
    val canceled = LedgerStore.canceled(entries)
    return HistoryRows.select(entries, people, clientId, query, from, to, filter, zone).map {
        DetailEntry(it, PeriodReport.dateOf(it, zone), it.id in canceled, null)
    }
}

internal data class HistoryActions(
    val filter: (String) -> Unit, val period: () -> Unit, val clearDates: () -> Unit,
    val clearClient: () -> Unit, val search: (String) -> Unit, val limit: (Int) -> Unit,
    val clearAll: () -> Unit, val add: () -> Unit, val open: (String) -> Unit,
    val purchase: (LedgerStore.Entry) -> Unit
)

object HistoryCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, data: BookScreenData, clientId: String?, initialQuery: String,
        from: LocalDate?, to: LocalDate?, filter: String, initialLimit: Int, largeText: Boolean,
        onFilter: Consumer<String>, onPeriod: Runnable, onClearDates: Runnable, onClearClient: Runnable,
        onSearch: Consumer<String>, onLimit: IntConsumer, onClearAll: Runnable, onAdd: Runnable,
        onOpen: Consumer<String>, onPurchase: Consumer<LedgerStore.Entry>): ComposeView {
        val actions = HistoryActions(onFilter::accept, onPeriod::run, onClearDates::run, onClearClient::run,
            onSearch::accept, onLimit::accept, onClearAll::run, onAdd::run, onOpen::accept, onPurchase::accept)
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    HistoryContent(data, clientId, initialQuery, from, to, filter, initialLimit, largeText, actions)
                }
            }
        }
    }
}

@Composable
internal fun HistoryContent(data: BookScreenData, clientId: String?, initialQuery: String,
    from: LocalDate?, to: LocalDate?, filter: String, initialLimit: Int, largeText: Boolean,
    actions: HistoryActions) {
    val people = remember(data) { data.snapshot.clients.associateBy { it.id } }
    var query by remember { mutableStateOf(initialQuery) }
    var limit by remember { mutableIntStateOf(initialLimit) }
    var visible by remember(data, clientId, query, from, to, filter) { mutableStateOf<List<DetailEntry>?>(null) }
    var failed by remember(data, clientId, query, from, to, filter) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(data, clientId, query, from, to, filter, attempt) {
        try {
            val result = withContext(Dispatchers.Default) {
                projectHistory(data, people, clientId, query, from, to, filter, zone)
            }
            coroutineContext.ensureActive()
            visible = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
    }
    val result = visible
    BoxWithConstraints(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
        val compact = maxWidth < 365.dp || largeText || LocalDensity.current.fontScale > 1.2f
        LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("history-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("История", color = ledgerText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text("Операций: " + data.snapshot.entries.size, color = ledgerMuted, fontSize = 13.sp)
                    }
                    TextButton(onClick = actions.period, modifier = Modifier.heightIn(min = 48.dp)) { Text("Период") }
                }
            }
            if (data.snapshot.entries.isEmpty()) {
                item("empty-book") {
                    Column(Modifier.fillMaxWidth().background(ledgerPanel, RoundedCornerShape(12.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("История пока пустая", color = ledgerText, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                        Text("Каждая записанная задолженность, оплата и отмена появятся здесь.", color = ledgerMuted, fontSize = 14.sp)
                        Button(onClick = actions.add, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (people.isEmpty()) "Добавить первого клиента" else "Записать долг")
                        }
                    }
                }
            } else {
                item("filters") {
                    val choices = listOf("Все", "Долги", "Оплаты", "Отмены")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (group in choices.chunked(if (compact) 2 else 4)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (choice in group) {
                                    val modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                        selected = choice == filter; contentDescription = "Фильтр: " + choice
                                    }
                                    val click = { if (choice != filter) actions.filter(choice) }
                                    Column(modifier.clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp), contentAlignment = Alignment.Center) {
                                            Text(choice, color = if (choice == filter) ledgerText else ledgerMuted,
                                                fontWeight = if (choice == filter) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp)
                                        }
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (choice == filter) ledgerAccent else ledgerBorder.copy(alpha = .45f)))
                                    }
                                }
                            }
                        }
                    }
                }
                item("period") {
                    LedgerMetric("Период истории", if (from == null) "Все даты" else
                        ledgerLongDate.format(from) + " — " + ledgerLongDate.format(to ?: from), action = actions.period)
                    if (from != null) OutlinedButton(onClick = actions.clearDates, modifier = Modifier.fillMaxWidth()) { Text("Сбросить даты") }
                }
                if (clientId != null) item("client") {
                    OutlinedButton(onClick = actions.clearClient, modifier = Modifier.fillMaxWidth()) {
                        Text("Клиент: " + (people[clientId]?.name ?: "—") + " · сбросить")
                    }
                }
                item("search") {
                    Text("Поиск операций", color = ledgerMuted, fontSize = 13.sp)
                    OutlinedTextField(value = query, onValueChange = {
                        query = it; limit = 100; actions.search(it)
                    }, singleLine = true, placeholder = { Text("Имя, телефон или комментарий") },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Имя, телефон или комментарий" },
                        shape = RoundedCornerShape(8.dp))
                }
                when {
                    failed -> item("error") {
                        Text("Не удалось прочитать историю.", color = ledgerMuted)
                        OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                    }
                    result == null -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    result.isEmpty() -> item("empty-filter") {
                        Text("Нет операций в фильтре", color = ledgerText)
                        Text("Выберите другой тип операции или посмотрите всю историю.", color = ledgerMuted)
                        OutlinedButton(onClick = actions.clearAll, modifier = Modifier.fillMaxWidth()) { Text("Показать все") }
                    }
                    else -> {
                        val shown = result.take(limit)
                        itemsIndexed(shown, key = { _, row -> "history-" + row.entry.id }) { index, row ->
                            if (index == 0 || shown[index - 1].date != row.date) {
                                Text(ledgerLongDate.format(row.date), color = ledgerMuted, fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp))
                            }
                            DetailOperationRow(row, largeText, { actions.open(row.entry.id) },
                                { actions.purchase(row.entry) }, people[row.entry.clientId]?.name)
                        }
                        if (result.size > limit) item("more") {
                            OutlinedButton(onClick = { limit = minOf(100000, limit + 100); actions.limit(limit) },
                                modifier = Modifier.fillMaxWidth()) { Text("Показать ещё операции") }
                        }
                    }
                }
            }
        }
    }
}
