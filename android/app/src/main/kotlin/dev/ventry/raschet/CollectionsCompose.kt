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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class CollectionUiModel(
    val work: List<CollectionWorklist.Item>, val total: Long, val overdue: Long,
    val upcoming: Long, val today: LocalDate
)

internal fun projectCollections(data: BookScreenData, directory: ClientDirectory, zone: ZoneId): CollectionUiModel {
    val promises = PaymentPromises.project(data.statuses, directory, data.snapshot.entries, data.today, zone)
    val work = CollectionWorklist.build(data.statuses, directory, data.today, promises)
    return CollectionUiModel(work, work.sumOf { it.status.balance },
        work.sumOf { it.status.overdue }, work.sumOf { it.status.upcoming }, data.today)
}

internal data class CollectionActions(
    val search: (String) -> Unit, val urgent: (Boolean) -> Unit, val limit: (Int) -> Unit,
    val filter: (String) -> Unit, val open: (String) -> Unit, val pay: (String) -> Unit,
    val plan: (LedgerStore.Client) -> Unit
)

object CollectionsCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, data: BookScreenData, query: String, urgent: Boolean,
        initialLimit: Int, largeText: Boolean, onSearch: Consumer<String>, onUrgent: Consumer<Boolean>,
        onLimit: IntConsumer, onFilter: Consumer<String>, onOpen: Consumer<String>,
        onPay: Consumer<String>, onPlan: Consumer<LedgerStore.Client>): ComposeView {
        val app = owner.applicationContext
        val actions = CollectionActions(onSearch::accept, onUrgent::accept, onLimit::accept,
            onFilter::accept, onOpen::accept, onPay::accept, onPlan::accept)
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    var model by remember { mutableStateOf<CollectionUiModel?>(null) }
                    var failed by remember { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    LaunchedEffect(data, attempt) {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                projectCollections(data, ClientDirectory(app), ZoneId.systemDefault())
                            }
                            coroutineContext.ensureActive()
                            model = result
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
                    }
                    val current = model
                    when {
                        current != null -> CollectionContent(current, query, urgent, initialLimit, largeText, actions)
                        failed -> Column(Modifier.fillMaxSize().background(ledgerBg).padding(16.dp)) {
                            Text("Ошибка чтения книги", color = ledgerText)
                            OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
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
internal fun CollectionContent(model: CollectionUiModel, initialQuery: String, initialUrgent: Boolean,
    initialLimit: Int, largeText: Boolean, actions: CollectionActions) {
    var query by remember { mutableStateOf(initialQuery) }
    var urgent by remember { mutableStateOf(initialUrgent) }
    var limit by remember { mutableIntStateOf(initialLimit) }
    var visible by remember(model, query, urgent) { mutableStateOf<List<CollectionWorklist.Item>?>(null) }
    var failed by remember(model, query, urgent) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(model, query, urgent, attempt) {
        try {
            val result = withContext(Dispatchers.Default) { CollectionWorklist.select(model.work, query, urgent) }
            coroutineContext.ensureActive()
            visible = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ledgerBg), contentAlignment = Alignment.TopCenter) {
        val stacked = largeText || maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f
        val result = visible
        LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("collections-list"),
            contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item("title") {
                Text("Возврат долгов", color = ledgerText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("План работы с непогашенными долгами", color = ledgerMuted, fontSize = 13.sp)
            }
            item("total") { LedgerTotal("К возврату всего", model.total, "Должников: " + model.work.size) }
            item("overdue") {
                LedgerMetric("Просрочено", ledgerMoney(model.overdue), if (model.overdue > 0) ledgerRed else ledgerMuted) {
                    actions.filter(CollectionsInsights.LATE)
                }
            }
            item("upcoming") {
                LedgerMetric("В ближайшие 7 дней", ledgerMoney(model.upcoming), if (model.upcoming > 0) ledgerAlert else ledgerMuted) {
                    actions.filter(CollectionsInsights.SOON)
                }
            }
            item("search") {
                OutlinedTextField(value = query, onValueChange = {
                    query = it; limit = 60; actions.search(it)
                }, singleLine = true, placeholder = { Text("Имя или телефон") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Имя или телефон" },
                    shape = RoundedCornerShape(8.dp))
            }
            item("filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value) in listOf("Все должники" to false, "Требуют внимания" to true)) {
                        val modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            selected = urgent == value; contentDescription = label
                        }
                        val click = { if (urgent != value) { urgent = value; limit = 60; actions.urgent(value) } }
                        if (urgent == value) Button(onClick = click, modifier = modifier) { Text(label, fontSize = 12.sp) }
                        else OutlinedButton(onClick = click, modifier = modifier) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
            when {
                failed -> item("error") {
                    Text("Не удалось отобразить должников.", color = ledgerMuted)
                    OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                }
                result == null -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                result.isEmpty() -> item("empty") {
                    Text(if (model.work.isEmpty()) "Непогашенных долгов нет." else "Нет должников по выбранным условиям.", color = ledgerMuted)
                }
                else -> {
                    val shown = result.take(limit)
                    itemsIndexed(shown, key = { _, item -> "collection-" + item.status.client.id }) { index, item ->
                        if (index == 0 || shown[index - 1].priority != item.priority) {
                            Text(CollectionWorklist.title(item.priority), fontWeight = FontWeight.Bold, color = ledgerText,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                        CollectionRow(item, model.today, stacked, actions)
                    }
                    if (result.size > limit) item("more") {
                        OutlinedButton(onClick = { limit += 60; actions.limit(limit) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Показать ещё должников")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(item: CollectionWorklist.Item, today: LocalDate, stacked: Boolean, actions: CollectionActions) {
    val status = item.status
    val person = status.client
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.open(person.id) }
            .semantics { contentDescription = person.name + ", остаток " + ledgerMoney(status.balance) }
            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(person.name, color = ledgerText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(ledgerMoney(status.balance), color = ledgerText, fontWeight = FontWeight.Bold)
        }
        if (status.overdue > 0) Text("Просрочено: " + ledgerMoney(status.overdue), color = ledgerRed, fontSize = 12.sp)
        if (status.upcoming > 0) Text("За 7 дней: " + ledgerMoney(status.upcoming) + " · ближайший срок " + status.nextDue,
            color = ledgerAlert, fontSize = 12.sp)
        if (item.limit > 0 && status.balance > item.limit)
            Text("Сверх лимита: " + ledgerMoney(status.balance - item.limit), color = ledgerAlert, fontSize = 12.sp)
        item.followUp?.let { plan ->
            promiseNotices(plan, item.promise, today).forEach { notice -> Text(notice.text, color = notice.color, fontSize = 12.sp) }
        }
        val planLabel = if (item.followUp == null) "Запланировать" else "Изменить план"
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { actions.pay(person.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Принять оплату") }
            OutlinedButton(onClick = { actions.plan(person) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(planLabel) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { actions.pay(person.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Принять оплату") }
            OutlinedButton(onClick = { actions.plan(person) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(planLabel) }
        }
    }
    HorizontalDivider(color = ledgerBorder)
}
