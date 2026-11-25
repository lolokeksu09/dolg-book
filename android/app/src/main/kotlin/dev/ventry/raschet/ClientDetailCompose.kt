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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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

private val bg = Color(LedgerTheme.BG)
private val panel = Color(LedgerTheme.PANEL)
private val ink = Color(LedgerTheme.TEXT)
private val dim = Color(LedgerTheme.MUTED)
private val green = Color(LedgerTheme.ACCENT)
private val amber = Color(LedgerTheme.ALERT)
private val red = Color(LedgerTheme.RED)
private val line = Color(LedgerTheme.BORDER)
private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yy", Locale("ru", "RU"))
private fun rubles(cents: Long) = String.format(Locale("ru", "RU"), "%,.2f ₽", cents / 100.0).replace('\u00a0', ' ')

internal data class DetailNotice(val text: String, val color: Color)
internal data class DetailEntry(
    val entry: LedgerStore.Entry, val date: LocalDate, val canceled: Boolean, val remaining: Long?
)
internal data class DetailDraft(val kind: String, val caption: String)
internal data class DetailData(
    val status: CollectionsInsights.CustomerStatus,
    val archived: Boolean,
    val notices: List<DetailNotice>,
    val drafts: List<DetailDraft>,
    val rows: List<DetailEntry>,
    val historyCount: Int
)
internal data class DetailActions(
    val back: () -> Unit, val more: (Boolean) -> Unit, val debt: () -> Unit,
    val payment: () -> Unit, val restore: () -> Unit, val filter: () -> Unit,
    val open: (String) -> Unit, val purchase: (LedgerStore.Entry) -> Unit,
    val limit: (Int) -> Unit
)

/** Financial projection shares the exact existing statement, promise and allocation rules. */
internal fun detailProjection(
    snapshot: BookScreenData, id: String, filter: String, directory: ClientDirectory,
    drafts: LedgerDrafts, zone: ZoneId
): DetailData? {
    val status = snapshot.statuses.firstOrNull { it.client.id == id } ?: return null
    val all = snapshot.snapshot.entries
    val statement = ClientStatement.build(all, id, zone)
    val amount = status.balance
    val archived = directory.archived(id, amount)
    val notices = mutableListOf<DetailNotice>()
    notices += if (status.overdue > 0) DetailNotice("Просрочено: " + rubles(status.overdue), red)
        else DetailNotice(if (amount == 0L) "Долга нет" else "Нет просрочки", dim)
    if (status.upcoming > 0) notices += DetailNotice(
        "Ближайший срок: " + status.nextDue + " · за 7 дней: " + rubles(status.upcoming), dim
    )
    val limit = directory.debtLimit(id)
    if (limit > 0) notices += DetailNotice("Лимит: " + rubles(limit) + " · " +
        if (amount > limit) "Превышение " + rubles(amount - limit)
        else "Доступно " + rubles(DebtLimits.remaining(limit, amount)),
        if (amount >= limit) amber else dim)
    val plan = directory.followUp(id)
    if (plan != null && plan.amount > 0) {
        val progress = PaymentPromises.calculate(all, id, plan, snapshot.today, zone)
        notices += DetailNotice("Обещано " + rubles(plan.amount) + " до " + dateFormat.format(plan.date), ink)
        if (!progress.valid) notices += DetailNotice("Нужно обновить обещание: исходная операция не найдена.", amber)
        else {
            val caption = if (progress.fulfilled()) {
                if (progress.onTime >= plan.amount) "Выполнено" else "Выполнено после срока"
            } else if (progress.missed) "Срок обещания прошёл" else "Ожидается оплата"
            notices += DetailNotice(caption + " · получено " + rubles(minOf(progress.paid, plan.amount)) +
                " из " + rubles(plan.amount), if (progress.fulfilled()) green else if (progress.missed) amber else dim)
            if (!progress.fulfilled()) notices += DetailNotice("Осталось по обещанию: " + rubles(plan.amount - progress.paid), dim)
        }
        if (plan.note.isNotEmpty()) notices += DetailNotice(plan.note, dim)
    } else if (amount > 0 && plan != null) {
        notices += DetailNotice("Связаться: " + dateFormat.format(plan.date) +
            if (plan.note.isEmpty()) "" else " · " + plan.note,
            if (plan.date.isAfter(snapshot.today)) dim else amber)
    }
    val pending = if (archived) emptyList() else listOf("debt", "payment").mapNotNull { kind ->
        val draft = drafts.get(id, kind) ?: return@mapNotNull null
        if (draft.id in statement.ids || (kind == "payment" && amount <= 0)) null
        else DetailDraft(kind, if (draft.amount.trim().isEmpty()) "Продолжить" else draft.amount + " ₽")
    }
    val rows = statement.select(filter).map { entry ->
        DetailEntry(entry, PeriodReport.dateOf(entry, zone), entry.id in statement.canceled, statement.remaining[entry.id])
    }
    return DetailData(status, archived, notices.toList(), pending, rows, statement.entries.size)
}

object ClientDetailCompose {
    @JvmStatic
    fun create(owner: ComponentActivity, data: BookScreenData, id: String, filter: String,
        initialLimit: Int, largeText: Boolean, fromCollections: Boolean, onBack: Runnable,
        onMore: Consumer<Boolean>, onDebt: Runnable, onPayment: Runnable, onRestore: Runnable,
        onFilter: Runnable, onOpen: Consumer<String>, onPurchase: Consumer<LedgerStore.Entry>,
        onLimit: IntConsumer): ComposeView {
        val app = owner.applicationContext
        val actions = DetailActions(onBack::run, onMore::accept, onDebt::run, onPayment::run,
            onRestore::run, onFilter::run, onOpen::accept, onPurchase::accept, onLimit::accept)
        return ComposeView(owner).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                LedgerMaterialTheme {
                    var model by remember(data, id, filter) { mutableStateOf<DetailData?>(null) }
                    var missing by remember(data, id, filter) { mutableStateOf(false) }
                    var failed by remember(data, id, filter) { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    LaunchedEffect(data, id, filter, attempt) {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                detailProjection(data, id, filter, ClientDirectory(app), LedgerDrafts(app), ZoneId.systemDefault())
                            }
                            coroutineContext.ensureActive()
                            if (result == null) missing = true else model = result
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            coroutineContext.ensureActive()
                            failed = true
                        }
                    }
                    val current = model
                    when {
                        current != null -> ClientDetailContent(current, filter, initialLimit, largeText, fromCollections, actions)
                        missing || failed -> Column(Modifier.fillMaxSize().background(bg).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (missing) "Клиент не найден" else "Ошибка чтения клиента", color = ink)
                            if (failed) OutlinedButton(onClick = { failed = false; attempt++ }) { Text("Повторить") }
                            OutlinedButton(onClick = actions.back) { Text("К списку клиентов") }
                        }
                        else -> Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClientDetailContent(model: DetailData, filter: String, initialLimit: Int,
    largeText: Boolean, fromCollections: Boolean, actions: DetailActions) {
    var visibleLimit by remember { mutableIntStateOf(initialLimit) }
    val client = model.status.client
    BoxWithConstraints(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.TopCenter) {
        val stacked = largeText || LocalDensity.current.fontScale > 1.2f || maxWidth < 340.dp
        LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("client-detail-list"),
            contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item("context") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (fromCollections) "←  Возврат долгов" else "←  Все клиенты",
                        color = green, modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = actions.back).padding(vertical = 12.dp))
                    Text("Ещё действия", color = green, modifier = Modifier.heightIn(min = 48.dp)
                        .clickable { actions.more(model.archived) }.padding(12.dp))
                }
                Text(client.name, color = ink, fontSize = if (largeText) 24.sp else 22.sp, fontWeight = FontWeight.Bold)
                Text(client.phone.ifEmpty { "Карточка клиента" }, color = dim, fontSize = 14.sp)
            }
            item("balance") {
                Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Остаток долга", color = dim, fontSize = 14.sp)
                    Text(rubles(model.status.balance), color = ink, fontSize = if (largeText) 36.sp else 32.sp, fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                    model.notices.forEach { Text(it.text, color = it.color, fontSize = if (largeText) 15.sp else 13.sp) }
                }
            }
            item("actions") {
                if (model.archived) {
                    Text("Клиент в архиве. История сохранена.", color = dim)
                    Button(onClick = actions.restore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Вернуть из архива") }
                } else if (model.status.balance == 0L) {
                    Button(onClick = actions.debt, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ Долг") }
                } else if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = actions.payment, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Оплата") }
                        OutlinedButton(onClick = actions.debt, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ Долг") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.debt, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("+ Долг") }
                        Button(onClick = actions.payment, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Оплата") }
                    }
                }
            }
            items(model.drafts, key = { "draft-" + it.kind }) { draft ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = if (draft.kind == "debt") actions.debt else actions.payment)
                    .padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Черновик " + if (draft.kind == "debt") "долга" else "оплаты", color = ink)
                    Text(draft.caption, color = green)
                }
            }
            item("history-filter") {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = actions.filter)
                    .semantics { contentDescription = "История клиента" }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("История клиента", color = ink, fontWeight = FontWeight.Bold)
                    Text(filter + " · " + model.rows.size, color = if (filter == ClientStatement.ALL) dim else green)
                }
                HorizontalDivider(color = line)
            }
            if (model.rows.isEmpty()) item("empty-history") {
                Text(if (model.historyCount == 0) "Пока нет операций." else "Нет операций по выбранному фильтру.", color = dim)
            }
            val shown = model.rows.take(visibleLimit)
            itemsIndexed(shown, key = { _, row -> "entry-" + row.entry.id }) { index, row ->
                if (index == 0 || shown[index - 1].date != row.date) {
                    Text(dateFormat.format(row.date), color = dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(4.dp)).padding(6.dp))
                }
                DetailOperationRow(row, largeText, { actions.open(row.entry.id) }, { actions.purchase(row.entry) })
            }
            if (model.rows.size > visibleLimit) item("more-history") {
                OutlinedButton(onClick = { visibleLimit = minOf(100000, visibleLimit + 80); actions.limit(visibleLimit) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Показать ещё операции") }
            }
        }
    }
}

@Composable
internal fun DetailOperationRow(row: DetailEntry, largeText: Boolean, open: () -> Unit, purchase: () -> Unit, clientName: String? = null) {
    val e = row.entry
    val color = if (row.canceled) dim else when (e.type) { "payment" -> green; "reversal" -> amber; else -> ink }
    val kind = when (e.type) { "debt" -> "Долг"; "payment" -> "Оплата"; else -> "Отмена" }
    val marker = when (e.type) { "debt" -> "+"; "payment" -> "−"; else -> "↶ " }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = open)
        .semantics { contentDescription = "Запись · " + rubles(e.amount) }.padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(marker, color = color, fontSize = 18.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (clientName != null) Text(clientName, color = ink, fontWeight = FontWeight.Bold)
            Text(kind + if (row.canceled) " · отменено" else "", color = color, fontWeight = FontWeight.SemiBold)
            if (row.remaining != null && !row.canceled) Text(
                if (row.remaining == 0L) "Погашено" else "Осталось: " + rubles(row.remaining),
                color = if (row.remaining == 0L) dim else amber, fontSize = 12.sp)
            if (e.note.isNotEmpty()) Text(e.note, color = dim, fontSize = 12.sp)
            if (e.due.isNotEmpty()) Text("Вернуть до " + e.due, color = amber, fontSize = 12.sp)
            if (e.purchase != null) Text("Состав покупки · " + e.purchase.items.size + " поз.", color = green,
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = purchase).padding(vertical = 10.dp))
        }
        Text(marker + rubles(e.amount), color = color, fontWeight = FontWeight.Bold,
            fontSize = if (largeText) 18.sp else 16.sp,
            textDecoration = if (row.canceled) TextDecoration.LineThrough else null)
    }
    HorizontalDivider(color = line)
}
